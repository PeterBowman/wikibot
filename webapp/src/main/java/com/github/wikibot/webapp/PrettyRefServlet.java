package com.github.wikibot.webapp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkSpan;
import org.nibor.autolink.LinkType;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class PrettyRefServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final Pattern CITE_LANG_MERGER_RE = Pattern.compile("\\{\\{(cytuj [^\\{\\}]+?)\\}\\}\\s*\\{\\{lang\\|([a-z-]+)\\}\\}", Pattern.CASE_INSENSITIVE);
	private static final Pattern CONSEC_R_RE = Pattern.compile("\\{\\{[rR]\\|([^\\}]+)\\}\\}\\s*\\{\\{[rR]\\|([^\\}]+)\\}\\}");
	private static final Pattern SOURCES_RE = Pattern.compile("(=+ *(?:Przypisy|Uwagi) *=+\\s+)?(<references[^/]*/>|\\{\\{(?:Przypisy|Uwagi)([^\\{\\}]*|\\{\\{[^\\{\\}]+\\}\\})+\\}\\}|<references[^/]*>(.+?)</references\\s*>)", Pattern.CASE_INSENSITIVE);
	
	private static final String JSP_DISPATCH_TARGET = "/jsp/pretty-ref.jsp";

	private static Wiki wiki;

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public PrettyRefServlet() {
		super();
		wiki = new Wiki("pl.wikipedia.org");
		
		// populate the namespace cache, or at least attempt that
		try {
			wiki.getNamespaces();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		request.setCharacterEncoding("UTF-8");
		
		String title = request.getParameter("title");
		String text = request.getParameter("text");
		String format = request.getParameter("format");
		String callback = request.getParameter("callback");
		
		boolean isGui = request.getParameter("gui") != null; // default: "on"
		
		if (format == null) {
			format = "plain";
		}
		
		response.setHeader("Access-Control-Allow-Origin", "*");
		
		if ((title == null || title.trim().isEmpty()) && (text == null || text.trim().isEmpty())) {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			dispatcher.forward(request, response);
		} else {
			try {
				if (text == null || text.trim().isEmpty()) {
					title = title.trim();
					
					synchronized (wiki) {
						String resolved = wiki.resolveRedirect(title);
						title = resolved != null ? resolved : title;
						text = wiki.getPageText(title);
					}
				}
				
				String output = cleanRefs(text);
				String contentType;
				
				switch (format) {
					case "json":
					case "jsonp":
						contentType = format.equals("json") ? "application/json" : "text/javascript";
						
						JSONObject json = new JSONObject();
						json.put("status", 200);
						json.put("content", output);
						
						output = json.toString();
						
						if (format.equals("jsonp")) {
							output = String.format("%s(%s)", callback, output);
						}
						
						break;
					case "plain":
					default:
						contentType = "text/plain";
						break;
				}
				
				if (isGui) {
					RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
					request.setAttribute("output", output);
					dispatcher.forward(request, response);
				} else {
					response.setCharacterEncoding("UTF-8");
					response.setHeader("Content-Type", contentType);
					response.getWriter().append(output);
				}
			} catch (Exception e) {
				if (!isGui && (format.equals("json") || format.equals("jsonp"))) {
					String contentType = format.equals("json") ? "application/json" : "text/javascript";
					
					JSONObject json = new JSONObject();
					json.put("status", 500);
					json.put("error", e.toString());
					
					List<String> backTrace = new ArrayList<>();
					backTrace.add(e.toString());
					
					StackTraceElement[] stackTraceElements = e.getStackTrace();
					
					for (int i = 0; i < stackTraceElements.length; i++) {
						StackTraceElement el = stackTraceElements[i];
						backTrace.add(el.toString());
					}
					
					json.put("backtrace", StringUtils.join(backTrace, '\n'));
					
					response.setCharacterEncoding("UTF-8");
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
		Matcher m = Pattern.compile(Ref.REF_OPEN_RE.pattern() + "([\\s\\S]+?)" + Ref.REF_CLOSE_RE.pattern()).matcher(text);
		List<Ref> refs = new ArrayList<>();
		RefBuilder refBuilder = new RefBuilder();
		
		while (m.find()) {
			String group = m.group();
			refs.add(refBuilder.createRef(group, false));
		}
		
		// check for name conflicts
		Set<Ref> set = new HashSet<>(refs);
		
		if (set.size() != refs.size()) {
			Map<String, Integer> map = new HashMap<>();
			
			for (Ref ref : refs) {
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
			Ref ri = refs.get(i);
			
			for (int j = 0; j < refs.size(); j++) {
				Ref rj = refs.get(j);
				
				if (i == j) {
					continue;
				}
				
				String temp1 = ri.toString().replace("\"" + ri.name + "\"", "");
				String temp2 = rj.toString().replace("\"" + rj.name + "\"", "");
				
				if (temp1.equals(temp2)) {
					// convert the other to shorttag; this anso ensures it's not matched as a dupe again
					rj.content = null;
					rj.name = ri.name;
				}
			}
		}
		
		// add the shorttags
		m = Ref.REF_RETAG_R.matcher(text);
		StringBuffer sb = new StringBuffer(text.length());
		
		while (m.find()) {
			String group = m.group();
			String replacement = group.replace("|", "}}{{r|").replaceAll("\\{\\{[rR]\\}\\}", "");
			
			// FIXME: make multi-{{r}} sort-of work
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		
		text = m.appendTail(sb).toString();
		
		m = Ref.REF_SHORTTAG.matcher(text);
		List<Ref> shortRefs = new ArrayList<>();
		
		while (m.find()) {
			String group = m.group();
			shortRefs.add(refBuilder.createRef(group, true));
		}
		
		m = Ref.REF_RETAG.matcher(text);
		
		while (m.find()) {
			String group = m.group();
			shortRefs.add(refBuilder.createRef(group, true));
		}
		
		for (Ref shortRef : shortRefs) {
			Ref other = null;
			
			for (Ref ref : refs) {
				if (ref.origName != null && ref.origName.equals(shortRef.origName)) {
					other = ref;
					break;
				}
			}
			
			if (other == null) {
				throw new RuntimeException("Short tag with dangling name: " + shortRef);
			}
			
			shortRef.name = other.name;
			// group need not to be set if the ref was inside <references/>
			// if it's set on any, copy it
			shortRef.group = other.group = (shortRef.group != null ? shortRef.group : other.group);
		}
		
		refs.addAll(shortRefs);
		
		// replace refs in text with {{r}} calls
		// this might also change the inside of references section - will deal with it later
		for (Ref ref : refs) {
			text = text.replaceFirst(Pattern.quote(ref.orig), Matcher.quoteReplacement(ref.toShortTag()));
		}
		
		// clean up multiple consecutive {{r}}
		String temp;
		
		do {
			temp = text;
			text = CONSEC_R_RE.matcher(text).replaceAll("{{r|$1|$2}}");
		} while (!temp.equals(text));
		
		// place refs in the section
		// TODO: if both {{Uwagi}} and {{Przypisy}} are present, only the first one found is parsed
		m = SOURCES_RE.matcher(text);
		
		if (m.find()) {
			String oldRefSection = m.group();
			
			// figure out the heading level used for ref sections and probably thoughout the article
			String level = "==";
			
			if (
				(m = Pattern.compile("^(={2,})").matcher(oldRefSection)).find() ||
				(m = Pattern.compile("stopień\\s*=\\s*(={2,})").matcher(oldRefSection)).find()
			) {
				level = m.group(1);
			}
			
			// figure out the column count
			int cols = 1;
			
			if (
				(m = Pattern.compile("\\|\\s*(\\d+)\\s*(\\||\\}\\})").matcher(oldRefSection)).find() ||
				(m = Pattern.compile("l\\. *kolumn\\s*=\\s*(\\d+)").matcher(oldRefSection)).find()
			) {
				cols = Integer.parseInt(m.group(1));
			}
			
			// get only refs with content (ie. not shorttags) and sort them
			List<Ref> references = new ArrayList<>(refs);
			Iterator<Ref> i = references.iterator();
			
			while (i.hasNext()) {
				if (i.next().content == null) {
					i.remove();
				}
			}
			
			final Collator coll = Collator.getInstance(new Locale("pl"));
			coll.setStrength(Collator.SECONDARY);
			
			Collections.sort(references, new Comparator<Ref>() {
				@Override
				public int compare(Ref r1, Ref r2) {
					return coll.compare(r1.name, r2.name);
				}
			});
			
			// then group them by their group info (that is, by headings they belong to), sort headings
			final Map<String, String> sectionMapping = new HashMap<>();
			sectionMapping.put(null, "Przypisy");
			sectionMapping.put("uwaga", "Uwagi");
			
			final List<String> sectionHeaders = Arrays.asList("Uwagi", "Przypisy");
					
			Map<String, List<Ref>> groupedRefs = new TreeMap<>(new Comparator<String>() {
				@Override
				public int compare(String s1, String s2) {
					String temp1 = sectionMapping.get(s1);
					String temp2 = sectionMapping.get(s2);
					return Integer.compare(sectionHeaders.indexOf(temp1), sectionHeaders.indexOf(temp2));
				}
			});
			
			for (Ref ref : references) {
				if (groupedRefs.containsKey(ref.group)) {
					List<Ref> list = groupedRefs.get(ref.group);
					list.add(ref);
				} else {
					List<Ref> list = new ArrayList<>();
					list.add(ref);
					groupedRefs.put(ref.group, list);
				}
			}
			
			// churn out the wikicode for each section
			List<String> sections = new ArrayList<>();
			
			for (Map.Entry<String, List<Ref>> entry : groupedRefs.entrySet()) {
				String group = entry.getKey();
				List<Ref> refsInGroup = entry.getValue();
				
				List<String> lines = new ArrayList<>();
				lines.add(String.format("%1$s %2$s %1$s", level, sectionMapping.get(group)));
				lines.add(String.format(
					"{{Przypisy-lista|%s%s",
					group != null ? "grupa=" + group + "|" : "",
					cols > 1 ? "l. kolumn=" + cols + "|" : ""
				));
				
				for (Ref ref : refsInGroup) {
					lines.add(ref.toString());
				}
				
				lines.add("}}");
				
				sections.add(StringUtils.join(lines, '\n'));
			}
			
			// insert new refs section(s) into page code
			// remove all encountered sections, replace first one with ours
			m = SOURCES_RE.matcher(text);
			sb = new StringBuffer(text.length());
			boolean replacedOnce = false;
			
			while (m.find()) {
				if (!replacedOnce) {
					String replacement = Matcher.quoteReplacement(StringUtils.join(sections, "\n\n"));
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
		private static final List<String> LOWERCASE_CAPITALISATION = Arrays.asList(
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
			
			Matcher m = NAME_RE.matcher(text);
			
			if (m.matches()) {
				name = m.group(1).trim();
				List<String> templates = ParseUtils.getTemplates(name, text);
				
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
			Map<String, String> map = new LinkedHashMap<>(params);
			map.remove("templateName");
			return map;
		}
		
		String getTemplateName() {
			return StringUtils.capitalize(name);
		}
		
		@Override
		public String toString() {
			HashMap<String, String> clonedMap = new LinkedHashMap<>(params);
			String templateName = clonedMap.get("templateName");
			
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
		
		private static final List<String> URI_SCHEMES = Arrays.asList("http", "https", "ftp");
		
		private static final List<String> TLD = Arrays.asList(
			"biz", "com", "info", "name", "net", "org", "pro", "aero", "asia", "cat", "coop", "edu", "gov", "int",
			"jobs", "mil", "mobi", "museum", "tel", "travel", "xxx", "co"
		);
		
		private static final List<String> CCTLD = Arrays.asList(
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
		
		private static final List<String> IGNORED_URI_EXTENSIONS = Arrays.asList("cgi-bin", "html", "shtml", "jhtml");
		
		private static final Pattern TLD_RE, CCTLD_RE;
		
		private String orig, name, origName, group, content;
		
		private final Map<String, Integer> groupRefCounter;
		
		static {
			TLD_RE = Pattern.compile("\\.(" + StringUtils.join(TLD, '|') + ")$");
			CCTLD_RE = Pattern.compile("\\.(" + StringUtils.join(CCTLD, '|') + ")$");
		}
		
		private Ref(String str, boolean isShortTag, Map<String, Integer> groupRefCounter) {
			this.groupRefCounter = groupRefCounter;
			orig = str;
			
			if (!isShortTag) {
				Matcher m = REF_OPEN_RE.matcher(str);
				
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
					
					String params = m.group(1).trim();
					List<String> list = new ArrayList<>(Arrays.asList(params.split("\\s*\\|\\s*", 0)));
					Iterator<String> i = list.iterator();
					
					while (i.hasNext()) {
						if (i.next().trim().isEmpty()) {
							i.remove();
						}
					}
					
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
		}
		
		private void parseAttributes(String str) {
			Matcher m = ATTR_RE.matcher(str);
			
			while (m.find()) {
				String key = m.group(1);
				String value = m.group(2).trim();
				
				// strip quotes
				if (
					value.charAt(0) == value.charAt(value.length() - 1) &&
					StringUtils.startsWithAny(value, "'", "\"")
				) {
					value = value.substring(1, value.length() - 1).trim();
				}
				
				switch (key) {
					case "name":
						name = origName = value;
						break;
					case "group":
						group = value;
						Integer count = groupRefCounter.get(group);
						
						if (count == null) {
							count = 0;
						}
						
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
				Template tpl = new Template(str);
				Map<String, String> params = tpl.getParamMap();
				String templateName = tpl.getTemplateName();
				
				// keep this switch synced with the one in toString()
				switch (templateName) {
					case "Cytuj":
					case "Cytuj grę komputerową":
					case "Cytuj książkę":
					case "Cytuj odcinek":
					case "Cytuj pismo":
					case "Cytuj stronę":
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
						
						String title = params.get("tytuł");
						
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
								String temp = normalizeUri(params.get("url"));
								URI uri = new URI(temp);
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
							ident = extractNameFromWords(clearWikitext(StringUtils.join(params.values(), ' ')));
						}
						
						break;
					case "Dziennik Ustaw":
					case "Monitor Polski":
						ident = templateName.equals("Dziennik Ustaw") ? "DzU" : "MP";
						ident += " ";
						
						List<String> list = new ArrayList<>();
						Matcher m = Pattern.compile("\\d+").matcher(str);
						
						while (m.find()) {
							list.add(m.group());
						}
						
						ident += StringUtils.join(list, '-');
						break;
					case "Ludzie nauki":
						String capture = str.replaceFirst(".*?(\\d+).*", "$1");
						ident = String.format("ludzie-nauki-%s", capture);
						break;
					case "Simbad":
						String id = params.get("ParamWithoutName1");
						String description = params.get("ParamWithoutName2");
						ident = templateName;
						
						if (id != null) {
							ident += "-" + id;
						}
						
						if (description != null) {
							ident += "-" + description;
						}
						
						break;
					default:
						throw new RuntimeException("Unsupported cite template: " + templateName);
				}
			} else {
				LinkExtractor linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
				URI uri = null;
				
				for (LinkSpan linkSpan : linkExtractor.extractLinks(str)) {
					int start = linkSpan.getBeginIndex();
					int end = linkSpan.getEndIndex();
					
					String temp = str.substring(start, end);
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
			
			if (ident.trim().isEmpty()) {
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
			return str.replaceFirst("^https?://web.archive.org/web/(\\*|\\d+)/", "");
		}
		
		private static String extractNameFromUri(URI uri) {
			String host = uri.getHost();
			
			if (host == null) {
				return "";
			}
			
			host = host.replaceFirst("^www?\\d*\\.", "");
			host = TLD_RE.matcher(host).replaceFirst("");
			host = CCTLD_RE.matcher(host).replaceFirst("");
			
			String path = uri.getPath();
			String query = uri.getQuery();
			
			if (path == null) {
				path = "";
			}
			
			path += "?";
			
			if (query != null) {
				path += query;
			}
			
			Matcher m = Pattern.compile("[\\w\\d_-]{4,}").matcher(path);
			List<String> list = new ArrayList<>();
			
			while (m.find()) {
				String group = m.group();
				
				if (!IGNORED_URI_EXTENSIONS.contains(group) && !group.endsWith("_id")) {
					group = group.replace("_", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
					list.add(group);
				}
			}
			
			String ident = host.replaceAll(".", "-") + "-" + StringUtils.join(list, '-');
			
			while (ident.length() > IDENT_MAX_LEN && !list.isEmpty()) {
				list.remove(0);
				ident = host + "-" + StringUtils.join(list, '-');
			}
			
			return ident;
		}
		
		private static String extractNameFromWords(String str) {
			str = Normalizer.normalize(str, Normalizer.Form.NFC);
			Matcher m = POSIX_CATEGORIES_RE.matcher(str);
			String result = "";
			
			while (m.find()) {
				result += " " + m.group();
				
				if (result.length() >= IDENT_MAX_LEN) {
					break;
				}
			}
			
			return result;
		}
		
		String toShortTag() {
			if (group == null) {
				return String.format("{{r|%s}}", name);
			} else if (group.equals("uwaga")) {
				return String.format("{{u|%s}}", name);
			} else {
				return String.format("{{r|%s|grupa1=%s}}", name, group);
			}
		}
		
		@Override
		public String toString() {
			if (content == null) {
				return String.format("<ref name=\"%s\" />", name);
			}
			
			String fmt = "<ref name=\"%s\">%s</ref>";
			String cont;
			
			if (isOneTemplateCall(content)) {
				Template tpl = new Template(content);
				String templateName = tpl.getTemplateName();
				
				// keep this switch synced with the one in extractName()
				switch (templateName) {
					case "Cytuj":
					case "Cytuj grę komputerową":
					case "Cytuj książkę":
					case "Cytuj odcinek":
					case "Cytuj pismo":
					case "Cytuj stronę":
					case "Dziennik Ustaw":
					case "Monitor Polski":
					case "Simbad":
						cont = tpl.toString();
						break;
					case "Ludzie nauki":
						// TODO: parse template
						cont = content;
						break;
					default:
						throw new RuntimeException("Invalid template: " + templateName);	
				}
			} else {
				cont = content;
			}
			
			return String.format(fmt, name, cont);
		}
		
		@Override
		public int hashCode() {
			return name.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			
			if (!(o instanceof Ref)) {
				return false;
			}
			
			Ref r = (Ref) o;
			return name.equals(r.name);
		}
	}

}
