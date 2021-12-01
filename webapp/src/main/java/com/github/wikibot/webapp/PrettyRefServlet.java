package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class PrettyRefServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final Pattern CITE_LANG_MERGER_RE = Pattern.compile("\\{\\{(cytuj [^\\{\\}]+?)\\}\\}\\s*\\{\\{lang\\|([a-z-]+)\\}\\}", Pattern.CASE_INSENSITIVE);
	private static final Pattern SOURCES_RE = Pattern.compile("(=+ *(?:Przypisy|Uwagi) *=+\\s+)?(<references[^/]*>(.+?)</references\\s*>|<references[^/]*/>|\\{\\{(?:Przypisy|Uwagi)([^\\{\\}]*|\\{\\{[^\\{\\}]+\\}\\})+\\}\\})", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	
	private static final String JSP_DISPATCH_TARGET = "/jsp/pretty-ref.jsp";

	private static Wiki wiki;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public PrettyRefServlet() {
		super();
		wiki = Wiki.newSession("pl.wikipedia.org");
		
		// populate the namespace cache, or at least attempt that
		try {
			wiki.getNamespaces();
		} catch (UncheckedIOException e) {
			wiki.getNamespaces(); //retry once
		}
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		request.setCharacterEncoding(StandardCharsets.UTF_8.name());
		
		String title = request.getParameter("title");
		String text = request.getParameter("text");
		String format = request.getParameter("format");
		String callback = request.getParameter("callback");
		
		boolean isGui = request.getParameter("gui") != null; // default: "on"
		
		if (format == null) {
			format = "plain";
		}
		
		final var contentType = switch (format) {
			case "json" -> "application/json";
			case "jsonp" -> "text/javascript";
			case "plain" -> "text/plain";
			default -> throw new RuntimeException("Unsupported format parameter: " + format);
		};
		
		response.setHeader("Access-Control-Allow-Origin", "*");
		
		if ((title == null || title.isBlank()) && (text == null || text.isBlank())) {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			dispatcher.forward(request, response);
		} else {
			try {
				if (text == null || text.isBlank()) {
					title = title.trim();
					
					synchronized (wiki) {
						title = Optional.ofNullable(wiki.resolveRedirects(List.of(title)).get(0)).orElse(title);
						text = wiki.getPageText(List.of(title)).get(0);
					}
				} else {
					text = text.replace("\r\n", "\n"); // beware of CRLFs in web text mode
				}
				
				if (text == null) {
					throw new RuntimeException("Page does not exist: " + title);
				}
				
				var output = cleanRefs(text);
				
				if (!contentType.equals("text/plain")) {
					var json = new JSONObject(Map.of("status", 200, "content", output));
					output = json.toString();
					
					if (format.equals("jsonp")) {
						output = String.format("%s(%s)", callback, output);
					}
				}
				
				if (isGui) {
					var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
					request.setAttribute("output", output);
					dispatcher.forward(request, response);
				} else {
					response.setCharacterEncoding(StandardCharsets.UTF_8.name());
					response.setHeader("Content-Type", contentType);
					response.getWriter().append(output);
				}
			} catch (Exception e) {
				if (!isGui && !contentType.equals("text/plain")) {
					var json = new JSONObject(Map.of("status", 500, "error", e.toString()));
					var backTrace = new ArrayList<String>();
					backTrace.add(e.toString());
					
					Stream.of(e.getStackTrace()).map(Object::toString).forEach(backTrace::add);
					json.put("backtrace", String.join("\n", backTrace));
					
					response.setCharacterEncoding(StandardCharsets.UTF_8.name());
					response.setHeader("Content-Type", contentType);
					response.getWriter().append(json.toString());
				} else {
					throw e;
				}
			}
		}
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	
	private static String cleanRefs(String text) {
		// TODO: detect {{odn}} templates (see article "Wambola")
		text = text.replace("<!-- Tytuł wygenerowany przez bota -->", "");
		
		// merge {{lang}} inside cite templates
		text = CITE_LANG_MERGER_RE.matcher(text).replaceAll("{{$1 | język = $2}}");
		
		// build list of refs
		final var refBuilder = new RefBuilder();
		
		var refs = Pattern.compile(Ref.REF_OPEN_RE.pattern() + "([\\s\\S]+?)" + Ref.REF_CLOSE_RE.pattern()).matcher(text).results()
			.map(mr -> refBuilder.createRef(mr.group(), false))
			.collect(Collectors.toCollection(ArrayList::new));
		
		// check for name conflicts
		if (refs.size() != refs.stream().distinct().count()) {
			var map = new HashMap<String, Integer>();
			
			for (var ref : refs) {
				if (map.containsKey(ref.name)) {
					int count = map.get(ref.name);
					map.put(ref.name, ++count);
					ref.name += count;
					
					if (map.containsKey(ref.name)) {
						throw new RuntimeException("Solving name conflicts is not yet implemented: " + ref.name);
					}
				} else {
					map.put(ref.name, 1);
				}
			}
		}
		
		// check for dupes
		for (int i = 0; i < refs.size(); i++) {
			var ri = refs.get(i);
			
			for (int j = 0; j < refs.size(); j++) {
				var rj = refs.get(j);
				
				if (i == j || ri.content == null || rj.content == null) {
					continue;
				}
				
				var temp1 = ri.toString().replace("\"" + ri.name + "\"", "");
				var temp2 = rj.toString().replace("\"" + rj.name + "\"", "");
				
				if (temp1.equals(temp2)) {
					// convert the other to shorttag; this also ensures it's not matched as a dupe again
					rj.content = null;
					rj.name = ri.name;
				}
			}
		}
		
		// add the shorttags
		var m = Ref.REF_RETAG_R.matcher(text);
		var sb = new StringBuilder(text.length());
		
		while (m.find()) {
			var group = m.group();
			var replacement = group.replace("|", "}}{{r|").replaceAll("\\{\\{[rR]\\}\\}", "");
			
			// FIXME: make multi-{{r}} sort-of work
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		
		text = m.appendTail(sb).toString();
		
		var shortRefs = Ref.REF_SHORTTAG.matcher(text).results()
			.map(mr -> refBuilder.createRef(mr.group(), true))
			.collect(Collectors.toCollection(ArrayList::new));
		
		Ref.REF_RETAG.matcher(text).results()
			.map(mr -> refBuilder.createRef(mr.group(), true))
			.forEach(shortRefs::add);
		
		for (var shortRef : shortRefs) {
			var other = refs.stream()
				.filter(r -> Optional.ofNullable(r.origName).filter(name -> name.equals(shortRef.origName)).isPresent())
				.findAny()
				.orElseThrow(() -> new RuntimeException("Short tag with dangling name: " + shortRef));
			
			shortRef.name = other.name;
			// group need not to be set if the ref was inside <references/>
			// if it's set on any, copy it
			shortRef.group = other.group = (shortRef.group != null ? shortRef.group : other.group);
		}
		
		refs.addAll(shortRefs);
		
		// normalize refs in text ({{r}} calls and <ref> tags)
		// this might also change the inside of references section - will deal with it later
		for (var ref : refs) {
			text = text.replaceFirst(Pattern.quote(ref.orig), Matcher.quoteReplacement(ref.toShortTag()));
		}
		
		// place refs in the section
		// TODO: if both {{Uwagi}} and {{Przypisy}} are present, only the first one found is parsed
		m = SOURCES_RE.matcher(text);
		
		if (m.find()) {
			// figure out the heading level used for ref sections and probably thoughout the article
			var level = Pattern.compile("^(={2,})").matcher(m.group()).results()
				.map(mr -> mr.group(1))
				.findFirst()
				.orElse("==");
			
			final var coll = Collator.getInstance(new Locale("pl"));
			coll.setStrength(Collator.SECONDARY);
			
			// get only refs with content (ie. not shorttags) and sort them
			var references = refs.stream()
				.filter(r -> r.content != null)
				.sorted((r1, r2) -> coll.compare(r1.name, r2.name))
				.toList();
			
			// then group them by their group info (that is, by headings they belong to), sort headings
			var sectionMapping = Map.of("", "Przypisy", "uwaga", "Uwagi");
			var sectionHeaders = List.of("Uwagi", "Przypisy");
					
			var groupedRefs = new TreeMap<String, List<Ref>>((s1, s2) -> Integer.compare(
					sectionHeaders.indexOf(sectionMapping.get(s1)),
					sectionHeaders.indexOf(sectionMapping.get(s2))
				));
			
			for (var ref : references) {
				var group = Optional.ofNullable(ref.group).orElse(""); // Map.of() dislikes null keys
				groupedRefs.computeIfAbsent(group, k -> new ArrayList<>()).add(ref);
			}
			
			// churn out the wikicode for each section
			var sections = new ArrayList<String>();
			
			for (var entry : groupedRefs.entrySet()) {
				var group = entry.getKey();
				var refsInGroup = entry.getValue();
				
				var lines = new ArrayList<String>();
				lines.add(String.format("%1$s %2$s %1$s", level, sectionMapping.get(group)));
				
				if (!group.isEmpty()) {
					lines.add("<references group=\"uwaga\" responsive>");
				} else {
					lines.add("<references responsive>");
				}
				
				refsInGroup.forEach(r -> lines.add(r.toString()));
				lines.add("</references>");
				sections.add(String.join("\n", lines));
			}
			
			// insert new refs section(s) into page code
			// remove all encountered sections, replace first one with ours
			m = SOURCES_RE.matcher(text);
			sb = new StringBuilder(text.length());
			boolean replacedOnce = false;
			
			while (m.find()) {
				if (!replacedOnce) {
					var replacement = Matcher.quoteReplacement(String.join("\n\n", sections));
					m.appendReplacement(sb, replacement);
					replacedOnce = true;
				} else {
					m.appendReplacement(sb, "");
				}
			}
			
			text = m.appendTail(sb).toString();
			// extra newlines are added when previous replacement occurs more than once
			text = text.replaceAll("\n{3,}", "\n\n");
		} else {
			throw new RuntimeException("No references section present?");
		}
		
		return text;
	}

	private static class Template {
		private static final List<String> LOWERCASE_CAPITALISATION = List.of(
			"Cytuj", "Cytuj grę komputerową", "Cytuj książkę", "Cytuj odcinek", "Cytuj pismo", "Cytuj stronę"
		);
		
		private static final Pattern NAME_RE = Pattern.compile("^\\{\\{([^\\|\\}]+).*\\}\\}$", Pattern.DOTALL);
		
		private String name;
		
		private HashMap<String, String> params;
		
		Template(String str) {
			parse(str);
		}
		
		private void parse(String text) {
			text = text.trim();
			
			var m = NAME_RE.matcher(text);
			
			if (m.matches()) {
				name = m.group(1).trim();
				var templates = ParseUtils.getTemplates(name, text);
				
				if (templates.size() == 1 && templates.get(0).equals(text)) {
					params = ParseUtils.getTemplateParametersWithValue(templates.get(0));
				} else {
					throw new RuntimeException("Unsupported template syntax: " + text);
				}
			} else {
				throw new RuntimeException("Unable to extract name from template " + text);
			}
		}
		
		Map<String, String> getParamMap() {
			var map = new LinkedHashMap<String, String>(params);
			map.remove("templateName");
			return map;
		}
		
		String getTemplateName() {
			return StringUtils.capitalize(name);
		}
		
		@Override
		public String toString() {
			var clonedMap = new LinkedHashMap<String, String>(params);
			var templateName = clonedMap.get("templateName");
			
			if (templateName != null && LOWERCASE_CAPITALISATION.contains(templateName)) {
				clonedMap.put("templateName", StringUtils.uncapitalize(templateName));
			}
			
			return ParseUtils.templateFromMap(clonedMap);
		}
	}

	private static class RefBuilder {
		private final Map<String, Integer> groupRefCounter;
		
		RefBuilder() {
			groupRefCounter = new HashMap<>();
		}
		
		Ref createRef(String str, boolean isShortTag) {
			return new Ref(str, isShortTag, groupRefCounter);
		}
	}

	private static class Ref {
		// Matches a HTML-like key=value attribute.
		private static final Pattern ATTR_RE = Pattern.compile("(\\w+) *= *(\"[^\">\n]+\"|'[^'>\n]+'|[^\\s'\"<>/]+)");
		// Matches opening ref tag with its attributes as matching group.
		private static final Pattern REF_OPEN_RE = Pattern.compile("< *ref((?: *" + ATTR_RE.pattern() + ")*) *>", Pattern.CASE_INSENSITIVE);
		// Matches closing ref tag.
		private static final Pattern REF_CLOSE_RE = Pattern.compile("< */ *ref *>", Pattern.CASE_INSENSITIVE);
		// Matches self-closing ref tag (shorttag), or a regular tag with no content.
		private static final Pattern REF_SHORTTAG = Pattern.compile("< *ref((?: *" + ATTR_RE.pattern() + ")*) *(?:/ *>|>" + REF_CLOSE_RE.pattern() + ")", Pattern.CASE_INSENSITIVE);
		// Matches {{r}} or {{u}} template.
		private static final Pattern REF_RETAG = Pattern.compile("\\{\\{\\s*[rRuU]\\s*((?:\\|[^\\|\\}]*)+)\\}\\}");
		private static final Pattern REF_RETAG_U = Pattern.compile("\\{\\{\\s*[uU]\\s*((?:\\|[^\\|\\}]*)+)\\}\\}");
		private static final Pattern REF_RETAG_R = Pattern.compile("\\{\\{\\s*[rR]\\s*((?:\\|[^\\|\\}]*)+)\\}\\}");
		
		// Optimal length for ref name. Does not apply in some cases.
		private static final int IDENT_MAX_LEN = 25;
		
		private static final Pattern POSIX_CATEGORIES_RE = Pattern.compile("(?:\\p{L}|\\p{M}|\\p{N})+", Pattern.UNICODE_CHARACTER_CLASS);
		
		private static final List<String> URI_SCHEMES = List.of("http", "https", "ftp");
		
		private static final List<String> TLD = List.of(
			"biz", "com", "info", "name", "net", "org", "pro", "aero", "asia", "cat", "coop", "edu", "gov", "int",
			"jobs", "mil", "mobi", "museum", "tel", "travel", "xxx", "co"
		);
		
		private static final List<String> CCTLD = List.of(
			"ac", "ad", "ae", "af", "ag", "ai", "al", "am", "ao", "aq", "ar", "as", "at", "au", "aw", "ax", "az",
			"ba", "bb", "bd", "be", "bf", "bg", "bh", "bi", "bj", "bm", "bn", "bo", "br", "bs", "bt", "bw", "by",
			"bz", "ca", "cc", "cd", "cf", "cg", "ch", "ci", "ck", "cl", "cm", "cn", "co", "cr", "cu", "cv", "cx",
			"cy", "cz", "de", "dj", "dk", "dm", "do", "dz", "ec", "ee", "eg", "er", "es", "et", "eu", "fi", "fj",
			"fk", "fm", "fo", "fr", "ga", "gd", "ge", "gf", "gg", "gh", "gi", "gl", "gm", "gn", "gp", "gq", "gr",
			"gs", "gt", "gu", "gw", "gy", "hk", "hm", "hn", "hr", "ht", "hu", "id", "ie", "il", "im", "in", "io",
			"iq", "ir", "is", "it", "je", "jm", "jo", "jp", "ke", "kg", "kh", "ki", "km", "kn", "kp", "kr", "kw",
			"ky", "kz", "la", "lb", "lc", "li", "lk", "lr", "ls", "lt", "lu", "lv", "ly", "ma", "mc", "md", "me",
			"mg", "mh", "mk", "ml", "mm", "mn", "mo", "mp", "mq", "mr", "ms", "mt", "mu", "mv", "mw", "mx", "my",
			"mz", "na", "nc", "ne", "nf", "ng", "ni", "nl", "no", "np", "nr", "nu", "nz", "om", "pa", "pe", "pf",
			"pg", "ph", "pk", "pl", "pm", "pn", "pr", "ps", "pt", "pw", "py", "qa", "re", "ro", "rs", "ru", "rw",
			"sa", "sb", "sc", "sd", "se", "sg", "sh", "si", "sk", "sl", "sm", "sn", "so", "sr", "ss", "st", "sv",
			"sy", "sz", "tc", "td", "tf", "tg", "th", "tj", "tk", "tl", "tm", "tn", "to", "tr", "tt", "tv", "tw",
			"tz", "ua", "ug", "uk", "us", "uy", "uz", "va", "vc", "ve", "vg", "vi", "vn", "vu", "wf", "ws", "ye",
			"za", "zm", "zw"
		);
		
		private static final List<String> IGNORED_URI_EXTENSIONS = List.of("cgi-bin", "html", "shtml", "jhtml");
		
		private static final Pattern TLD_RE, CCTLD_RE;
		
		private String orig, name, origName, group, content;
		
		private final Map<String, Integer> groupRefCounter;
		
		static {
			TLD_RE = Pattern.compile("\\.(" + String.join("|", TLD) + ")$");
			CCTLD_RE = Pattern.compile("\\.(" + String.join("|", CCTLD) + ")$");
		}
		
		private Ref(String str, boolean isShortTag, Map<String, Integer> groupRefCounter) {
			this.groupRefCounter = groupRefCounter;
			orig = str;
			
			if (!isShortTag) {
				var m = REF_OPEN_RE.matcher(str);
				
				if (m.find()) {
					parseAttributes(m.group(1));
					str = m.replaceFirst("");
					str = REF_CLOSE_RE.matcher(str).replaceFirst("");
					
					content = str.trim();
					
					if (name == null || name.matches("^(auto|autonazwa|test)\\d*$")) {
						name = extractName(content);
					}
				}
			} else {
				Matcher m;
				
				if ((m = REF_SHORTTAG.matcher(str)).find()) {
					parseAttributes(m.group(1));
					str = m.replaceFirst("");
				} else if ((m = REF_RETAG.matcher(str)).find()) {
					if (REF_RETAG_U.matcher(str).find()) {
						group = "uwaga";
					}
					
					var params = m.group(1).trim();
					
					var list = Arrays.stream(params.split("\\s*\\|\\s*", 0))
						.filter(item -> !item.isBlank())
						.toList();
					
					if (list.size() != 1) {
						throw new RuntimeException("Unsupported argument list size (Ref constructor): " + str);
					}
					
					name = list.get(0);
					
					if (name.contains("=")) {
						throw new RuntimeException("{{r}} tags with named attributes are not supported: " + str);
					}
					
					origName = name;
				}
				
				if (name == null) {
					throw new RuntimeException("Short tag and no name: " + str);
				} else {
					if (name.equals("test") || name.matches("^auto(nazwa)?\\d+$")) {
						// this means that the name will be determined later by scanning all other ref tags
						name = null;
					}
				}
			}
			
			if (name != null) {
				sanitizeName();
			}
		}
		
		private void parseAttributes(String str) {
			var m = ATTR_RE.matcher(str);
			
			while (m.find()) {
				var key = m.group(1);
				var value = m.group(2).trim();
				
				// strip quotes
				if (value.charAt(0) == value.charAt(value.length() - 1) && StringUtils.startsWithAny(value, "'", "\"")) {
					value = value.substring(1, value.length() - 1).trim();
				}
				
				switch (key) {
					case "name":
						name = origName = value;
						break;
					case "group":
						group = value;
						var count = groupRefCounter.getOrDefault(group, 0);
						groupRefCounter.put(group, ++count);
						name = group + count;
						break;
				}
			}
		}
		
		private static boolean isOneTemplateCall(String str) {
			return
				str.startsWith("{{") && str.endsWith("}}") &&
				StringUtils.countMatches(str, "{{") == 1 && StringUtils.countMatches(str, "}}") == 1;
		}
		
		private static String extractName(String str) {
			// Nazwę dla refa możemy wymyślić na kilka sposobów. Nie powinna ona przekraczać 25 (IDENT_MAX_LEN) znaków. 
			// W kolejności używamy do tego:
			// 
			// a) Jeśli ref zawiera jeden ze standardowych szablonów {{cytuj}}:
			//    1. PMID/DOI
			//    2. Nazwiska autora + roku
			//    3. Tytułu dzieła
			//    4. Adresu URL
			//    5. Samego nazwiska autora
			//    (+ stron, jeśli podano)
			// b) Jeśli ref zawiera jeden z szablonów szczegółowych:
			//    (nie zaimplementowane...)
			// c) Jeśli ref jest zwykłym tekstem:
			//    1. Adresu URL obecnego w tekście
			// 
			// Jeśli nie uda się utworzyć identyfikatora na żaden z powyższych sposobów, powstaje on z początkowych słów 
			// występujących w tekście refa.
			String ident;
			
			if (isOneTemplateCall(str)) {
				var tpl = new Template(str);
				var params = tpl.getParamMap();
				var templateName = tpl.getTemplateName();
				
				switch (templateName) {
					case "Cytuj":
					case "Cytuj grę komputerową":
					case "Cytuj książkę":
					case "Cytuj odcinek":
					case "Cytuj pismo":
					case "Cytuj stronę":
					{
						String year, author, pages;
						
						if (params.containsKey("rok")) {
							year = params.get("rok");
						} else if (params.containsKey("data")) {
							year = params.get("data").replaceFirst(".*?(\\d{3,4}).*", "$1");
						} else {
							year = null;
						}
						
						if (params.containsKey("nazwisko r")) {
							author = params.get("nazwisko r");
						} else if (params.containsKey("autor r")) {
							author = params.get("autor r");
						} else if (params.containsKey("nazwisko")) {
							author = params.get("nazwisko");
						} else if (params.containsKey("autor")) {
							author = params.get("autor");
						} else {
							author = null;
						}
						
						if (params.containsKey("strony")) {
							pages = params.get("strony");
						} else {
							pages = params.get("s");
						}
						
						var title = params.get("tytuł");
						
						if (author != null) {
							author = extractNameFromWords(clearWikitext(author));
						}
						
						if (title != null) {
							title = extractNameFromWords(clearWikitext(title));
						}
						
						if (pages != null) {
							pages = pages.replaceAll("[-–—]", "-").replaceAll("[^\\d-]", "");
						}
						
						if (params.containsKey("pmid")) {
							ident = "pmid" + params.get("pmid");
						} else if (params.containsKey("doi")) {
							ident = "doi" + params.get("doi");
						} else if (author != null && year != null) {
							ident = author + year;
						} else if (title != null) {
							ident = title;
						} else if (params.containsKey("url")) {
							try {
								var temp = normalizeUri(params.get("url"));
								var uri = new URI(temp);
								ident = extractNameFromUri(uri);
							} catch (URISyntaxException e) {
								e.printStackTrace();
								ident = "";
							}
						} else if (author != null) {
							ident = author;
						} else {
							ident = "";
						}
						
						if (!ident.isEmpty() && pages != null) {
							ident += "-s" + pages;
						}
						
						if (ident.isEmpty()) {
							ident = extractNameFromWords(clearWikitext(String.join(" ", params.values())));
						}
						
						break;
					}
					case "Dziennik Ustaw":
					case "Monitor Polski":
					{
						ident = templateName.equals("Dziennik Ustaw") ? "DzU" : "MP";
						ident += " ";
						
						var list = new ArrayList<String>();
						var m = Pattern.compile("\\d+").matcher(str);
						
						while (m.find()) {
							list.add(m.group());
						}
						
						ident += String.join("-", list);
						break;
					}
					case "Ludzie nauki":
					{
						var capture = str.replaceFirst(".*?(\\d+).*", "$1");
						ident = String.format("ludzie-nauki-%s", capture);
						break;
					}
					case "Simbad":
					{
						var id = params.get("ParamWithoutName1");
						var description = params.get("ParamWithoutName2");
						ident = templateName;
						
						if (id != null) {
							ident += "-" + id;
						}
						
						if (description != null) {
							ident += "-" + description;
						}
						
						break;
					}
					default:
					{
						// use last six digits of the hash, see https://stackoverflow.com/q/33219638
						int hash = str.hashCode() & 0xffffff;
						ident = templateName.replace(" ", "-") + "-" + Integer.toString(hash);
					}
				}
			} else {
				var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
				URI uri = null;
				
				for (var linkSpan : linkExtractor.extractLinks(str)) {
					int start = linkSpan.getBeginIndex();
					int end = linkSpan.getEndIndex();
					
					var temp = str.substring(start, end);
					temp = normalizeUri(temp);
					
					try {
						uri = new URI(temp);
					} catch (URISyntaxException e) {
						e.printStackTrace();
						continue;
					}
					
					if (URI_SCHEMES.contains(uri.getScheme())) {
						break;
					} else {
						uri = null;
					}
				}
				
				if (uri != null) {
					ident = extractNameFromUri(uri);
				} else {
					ident = extractNameFromWords(clearWikitext(str));
				}
			}
			
			if (ident.isBlank()) {
				ident = "autonazwa";
			}
			
			return ident.trim();
		}
		
		private static String clearWikitext(String str) {
			return str
				.replaceAll("\\[\\[([^\\|\\]]+)\\|([^\\]]+)\\]\\]", "$2")
				.replaceAll("'{2,}", "");
		}
		
		private static String normalizeUri(String str) {
			final var specialChars = new char[]{ '#', '|' };
			
			if (StringUtils.containsAny(str, specialChars)) {
				str = str.substring(0, StringUtils.indexOfAny(str, specialChars));
			}
			
			return str.replaceFirst("^https?://web.archive.org/web/(\\*|\\d+)/", "");
		}
		
		private static String extractNameFromUri(URI uri) {
			var host = uri.getHost();
			
			if (host == null) {
				return "";
			}
			
			host = host.replaceFirst("^www?\\d*\\.", "");
			host = TLD_RE.matcher(host).replaceFirst("");
			host = CCTLD_RE.matcher(host).replaceFirst("");
			
			var path = uri.getPath();
			var query = uri.getQuery();
			
			if (path == null) {
				path = "";
			}
			
			path += "?";
			
			if (query != null) {
				path += query;
			}
			
			var m = Pattern.compile("[\\w\\d_-]{4,}").matcher(path);
			var list = new ArrayList<String>();
			
			while (m.find()) {
				var group = m.group();
				
				if (!IGNORED_URI_EXTENSIONS.contains(group) && !group.endsWith("_id")) {
					group = group.replace("_", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
					list.add(group);
				}
			}
			
			var ident = host.replaceAll(".", "-") + "-" + String.join("-", list);
			
			while (ident.length() > IDENT_MAX_LEN && !list.isEmpty()) {
				list.remove(0);
				ident = host + "-" + String.join("-", list);
			}
			
			return ident;
		}
		
		private static String extractNameFromWords(String str) {
			str = Normalizer.normalize(str, Normalizer.Form.NFC);
			var m = POSIX_CATEGORIES_RE.matcher(str);
			var result = "";
			
			while (m.find()) {
				result += " " + m.group();
				
				if (result.length() >= IDENT_MAX_LEN) {
					break;
				}
			}
			
			return result;
		}
		
		private void sanitizeName() {
			if (name.matches("\\d+")) {
				name = "_" + name;
			}
		}
		
		String toShortTag() {
			if (group == null) {
				return String.format("<ref name=\"%s\" />", name);
			} else if (group.equals("uwaga")) {
				return String.format("<ref name=\"%s\" group=\"uwaga\" />", name);
			} else {
				return String.format("<ref name=\"%s\" group=\"%s\" />", name, group);
			}
		}
		
		@Override
		public String toString() {
			if (content == null) {
				return String.format("<ref name=\"%s\" />", name);
			}
			
			var cont = isOneTemplateCall(content) ? new Template(content).toString() : content;
			return String.format("<ref name=\"%s\">%s</ref>", name, cont);
		}
		
		@Override
		public int hashCode() {
			return name.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			} else if (o instanceof Ref r) {
				return name.equals(r.name);
			} else {
				return false;
			}
		}
	}

}
