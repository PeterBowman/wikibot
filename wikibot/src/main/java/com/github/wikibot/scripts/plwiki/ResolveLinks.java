package com.github.wikibot.scripts.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.CredentialExpiredException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class ResolveLinks {
	private static final Path LOCATION = Paths.get("./data/scripts.plwiki/ResolveLinks/");
	private static final Path PATH_SOURCES = LOCATION.resolve("sources.txt");
	private static final Path PATH_TARGETS = LOCATION.resolve("targets.txt");
	private static final Path PATH_BACKLINKS = LOCATION.resolve("backlinks.txt");
	private static final Path PATH_EDITED = LOCATION.resolve("edited.txt");
	private static final Path PATH_ERRORS = LOCATION.resolve("errors.txt");
	
	private static final Properties defaultSQLProperties = new Properties();
	private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
	private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";
		
	// from Linker::formatLinksInComment in Linker.php
	private static final String PATT_LINK = "\\[{2} *?:?(%s|%s) *?(#[^\\|\\]]*?)?(?:\\|((?:]?[^\\]])*+))?\\]{2}([a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)?";
	
	private static final List<String> SOFT_REDIR_TEMPLATES = List.of(
		"Osobny artykuł", "Osobna strona", "Główny artykuł", "Main", "Mainsec", "Zobacz też", "Seealso"
	);
	
	private static final List<String> IGNORED_REDIR_TEMPLATES = List.of(
		"Inne znaczenia", "DisambigR"
	);
	
	private static final Set<Integer> TARGET_NAMESPACES = Set.of(
		Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.PROJECT_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, 100 /* Portal */
	);
	
	private static final List<String> PROJECT_WHITELIST = List.of(
		"Wikipedia:Skarbnica Wikipedii", "Wikipedia:Indeks biografii"
	);
	
	private static final int USER_SUBPAGE_SIZE_LIMIT = 500000;
	
	private static Wikibot wb;

	static {
		defaultSQLProperties.setProperty("enabledTLSProtocols", "TLSv1.2");
	}
	
	public static void main(String[] args) throws Exception {
		var line = parseArguments(args);
		var useFile = line.hasOption("useFile");
		
		if (useFile && (line.hasOption("source") || line.hasOption("target"))) {
			throw new IllegalArgumentException("Incompatible useFile option and source/target");
		}
		
		var mode = line.getOptionValue("mode");
		
		wb = Login.createSession("pl.wikipedia.org");
		
		var sourceToTarget = new HashMap<String, String>();
		final String summary;
		
		if (mode.equals("redirsource")) {
			if (useFile) {
				var sources = Files.readAllLines(PATH_SOURCES).stream().distinct().toList();
				
				getRedirectTargets(sources, sourceToTarget);
				summary = String.format("podmiana przekierowań");
			} else {
				var source = line.getOptionValue("source");
				Objects.requireNonNull(source, "missing source");
				var target = wb.resolveRedirects(List.of(source)).get(0);
				
				if (target.equals(source)) {
					throw new IllegalArgumentException("Source page is not a redirect");
				}
				
				sourceToTarget.put(source, target);
				summary = String.format("zamiana linków z „[[%s]]” na „[[%s]]”", source, target);
			}
		} else if (mode.equals("redirtarget")) {
			if (useFile) {
				var targets = Files.readAllLines(PATH_TARGETS).stream().distinct().toList();
				
				getRedirectSources(targets, sourceToTarget);
				summary = String.format("podmiana przekierowań");
			} else {
				var target = line.getOptionValue("target");
				Objects.requireNonNull(target, "missing target");
				var sources = wb.whatLinksHere(List.of(target), true, false, Wiki.MAIN_NAMESPACE).get(0);
				
				sources.stream().forEach(source -> sourceToTarget.put(source, target));
				summary = String.format("podmiana przekierowań do „[[%s]]”", target);
			}
		} else if (mode.equals("disamb")) {
			if (useFile) {
				var sources = Files.readAllLines(PATH_SOURCES).stream().distinct().toList();
				var targets = Files.readAllLines(PATH_TARGETS).stream().distinct().toList();
				
				if (sources.size() != targets.size()) {
					throw new IllegalArgumentException("Sources and targets have different sizes after uniq step");
				}
				
				IntStream.range(0, sources.size()).forEach(i -> sourceToTarget.put(sources.get(i), targets.get(i)));
				summary = String.format("zamiana linków do ujednoznacznień");
			} else {
				var source = line.getOptionValue("source");
				var target = line.getOptionValue("target");
				
				Objects.requireNonNull(source, "missing source");
				Objects.requireNonNull(target, "missing target");
				
				sourceToTarget.put(source, target);
				summary = String.format("zamiana linków z „[[%s]]” na „[[%s]]”", source, target);
			}
		} else {
			throw new IllegalArgumentException("Illegal mode: " + mode);
		}
		
		sourceToTarget.keySet().removeIf(title -> wb.namespace(title) != Wiki.MAIN_NAMESPACE);
		
		System.out.printf("Got %d items to work on%n", sourceToTarget.size());
		
		if (sourceToTarget.isEmpty()) {
			return;
		}
		
		var backlinkToSources = getBacklinkMap(sourceToTarget);
		
		System.out.printf("%d unique backlinks found.%n", backlinkToSources.size());
		
		if (backlinkToSources.isEmpty()) {
			return;
		}
		
		Files.write(PATH_BACKLINKS, backlinkToSources.keySet());
		
		var infos = getBacklinkInfo(new ArrayList<>(backlinkToSources.keySet()));
		
		var edited = new ArrayList<String>();
		var errors = new ArrayList<String>();
		
		var isRedirMode = mode.equals("redirsource") || mode.equals("redirtarget");
		var isDisambMode = mode.equals("disamb");
		
		wb.setMarkMinor(true);
		
		for (var page : wb.getContentOfPages(backlinkToSources.keySet())) {
			// Wikipedystka: -> Wikipedysta: (in order to match SQL results)
			var title = wb.normalize(page.getTitle());
			
			if (!infos.containsKey(title)) {
				System.out.println("Missing info key: " + title);
				errors.add(title);
				continue;
			}
			
			if (infos.get(title).isRedirect()) {
				System.out.println("Page is a redirect: " + title);
				errors.add(title);
				continue;
			}
			
			if (isDisambMode && infos.get(title).isDisambiguation()) {
				System.out.println("Page is a disambiguation: " + title);
				errors.add(title);
				continue;
			}
			
			if (
				wb.namespace(title) == Wiki.USER_NAMESPACE &&
				infos.get(title).length() > USER_SUBPAGE_SIZE_LIMIT
			) {
				System.out.println("User subpage exceeds size limit: " + title);
				errors.add(title);
				continue;
			}
			
			var newText = prepareText(page.getText(), backlinkToSources.get(title), sourceToTarget, isRedirMode);
			
			if (!newText.equals(page.getText())) {
				try {
					if (!line.hasOption("dry")) {
						wb.edit(page.getTitle(), newText, summary, page.getTimestamp());
					}
					
					edited.add(title);
				} catch (Throwable t) {
					System.out.printf("Error in %s: %s%n", title, t.getMessage());
					errors.add(title);
					
					if (t instanceof AssertionError
							|| t instanceof CredentialExpiredException
							|| t instanceof AccountLockedException) {
						break;
					}
				}
			}
		}
		
		System.out.println("Edited: " + edited.size());
		System.out.println("Errors: " + errors.size());
		
		Files.write(PATH_EDITED, edited);
		Files.write(PATH_ERRORS, errors);
		
		if (!errors.isEmpty() && errors.size() < 25) {
			errors.forEach(System.out::println);
		}
	}
	
	private static CommandLine parseArguments(String[] args) throws ParseException {
		var options = new Options();
		options.addRequiredOption("m", "mode", true, "script mode (redirsource, redirtarget, disamb)");
		options.addOption("s", "source", true, "source page");
		options.addOption("t", "target", true, "target page");
		options.addOption("f", "useFile", false, "retrieve sources/targets from file (only main namespace!)");
		options.addOption("d", "dry", false, "dry run");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		return new DefaultParser().parse(options, args);
	}
	
	private static Properties prepareSQLProperties() throws IOException {
		var properties = new Properties(defaultSQLProperties);
		var patt = Pattern.compile("(.+)='(.+)'");
		
		Files.readAllLines(Paths.get("./data/sessions/replica.my.cnf")).stream()
			.map(patt::matcher)
			.filter(Matcher::matches)
			.forEach(m -> properties.setProperty(m.group(1), m.group(2)));
		
		return properties;
	}
	
	private static Connection getConnection() throws ClassNotFoundException, IOException, SQLException {
		Class.forName("com.mysql.cj.jdbc.Driver");
		
		var props = prepareSQLProperties();
		
		try {
			return DriverManager.getConnection(SQL_PLWIKI_URI_SERVER, props);
		} catch (SQLException e) {
			return DriverManager.getConnection(SQL_PLWIKI_URI_LOCAL, props);
		}
	}
	
	private static void getRedirectSources(List<String> targets, Map<String, String> sourceToTarget)
			throws ClassNotFoundException, SQLException, IOException {
		try (var connection = getConnection()) {
			var targetsString = targets.stream()
				.map(target -> String.format("'%s'", target.replace(' ', '_').replace("'", "\\'")))
				.collect(Collectors.joining(","));
			
			var query = String.format("""
				SELECT
					page_title, pl_title
				FROM page
					INNER JOIN pagelinks ON pl_from = page_id
				WHERE
					page_namespace = 0 AND
					page_is_redirect = 1 AND
					pl_namespace = 0 AND
					pl_title IN (%s);
				""", targetsString);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var source = rs.getString("page_title");
				var target = rs.getString("pl_title");
				
				sourceToTarget.put(wb.normalize(source), wb.normalize(target));
			}
		}
	}
	
	private static void getRedirectTargets(List<String> sources, Map<String, String> sourceToTarget)
			throws ClassNotFoundException, SQLException, IOException {
		try (var connection = getConnection()) {
			var sourcesString = sources.stream()
				.map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
				.collect(Collectors.joining(","));
			
			var query = String.format("""
				SELECT
					page_title, pl_title
				FROM page
					INNER JOIN pagelinks ON pl_from = page_id
				WHERE
					page_namespace = 0 AND
					page_is_redirect = 1 AND
					page_title IN (%s) AND
					pl_namespace = 0;
				""", sourcesString);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var source = rs.getString("page_title");
				var target = rs.getString("pl_title");
				
				sourceToTarget.put(wb.normalize(source), wb.normalize(target));
			}
		}
	}
	
	private static Map<String, List<String>> getBacklinkMap(Map<String, String> sourceToTarget) throws IOException {
		var comparator = Comparator.comparing(wb::namespace).thenComparing(Comparator.naturalOrder());
		var backlinkToSources = new TreeMap<String, List<String>>(comparator);
		var sources = sourceToTarget.keySet().stream().toList();
		
		try (var connection = getConnection()) {
			var sourcesString = sources.stream()
				.map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
				.collect(Collectors.joining(","));
		
			var query = String.format("""
				SELECT
					page_title, page_namespace, pl_title
				FROM page
					INNER JOIN pagelinks ON pl_from = page_id
				WHERE
					page_is_redirect = 0 AND
					pl_namespace = 0 AND
					pl_title IN (%s);
				""", sourcesString);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var title = rs.getString("page_title");
				var ns = rs.getInt("page_namespace");
				var backlink = wb.normalize(String.format("%s:%s", wb.namespaceIdentifier(ns), title));
				var source = wb.normalize(rs.getString("pl_title"));
				
				backlinkToSources.computeIfAbsent(backlink, k -> new ArrayList<>()).add(source);
			}
		} catch (ClassNotFoundException | SQLException e) {
			var backlinksPerSource = wb.whatLinksHere(sources, false, false);
			
			for (var i = 0; i < backlinksPerSource.size(); i++) {
				var source = sources.get(i);
				var backlinks = backlinksPerSource.get(i);
				
				for (var backlink : backlinks) {
					backlinkToSources.computeIfAbsent(wb.normalize(backlink), k -> new ArrayList<>()).add(source);
				}
			}
		}
		
		var bots = wb.allUsersInGroup("bot");
		
		backlinkToSources.keySet().removeIf(t -> !TARGET_NAMESPACES.contains(wb.namespace(t)));
		
		// retain user sandboxes (no bots)
		backlinkToSources.keySet().removeIf(t -> wb.namespace(t) == Wiki.USER_NAMESPACE && (
			wb.getRootPage(t).equals(t) || bots.contains(wb.removeNamespace(wb.getRootPage(t)))
		));
		
		// retain biography notes
		backlinkToSources.keySet().removeIf(t -> wb.namespace(t) == Wiki.PROJECT_NAMESPACE && !PROJECT_WHITELIST.contains(wb.getRootPage(t)));
		
		return backlinkToSources;
	}
	
	private static Map<String, PageInfo> getBacklinkInfo(List<String> titles) throws IOException {
		var map = new HashMap<String, PageInfo>(titles.size());
		
		try (var connection = getConnection()) {
			var titleSet = new HashSet<>(titles);
			
			var titlesString = titles.stream()
				.map(wb::removeNamespace)
				.map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
				.distinct()
				.collect(Collectors.joining(","));
			
			var query = String.format("""
				SELECT
					page_title, page_namespace, page_len, page_is_redirect, pp_propname
				FROM page
					LEFT JOIN page_props ON
						pp_page = page_id AND
						pp_propname = "disambiguation"
				WHERE
					page_title IN (%s);
				""", titlesString);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var title = rs.getString("page_title");
				var ns = rs.getInt("page_namespace");
				var pagename = wb.normalize(String.format("%s:%s", wb.namespaceIdentifier(ns), title));
				
				if (titleSet.contains(pagename)) {
					var length = rs.getInt("page_len");
					var isRedirect = rs.getBoolean("page_is_redirect");
					var isDisambiguation = "disambiguation".equals(rs.getString("pp_propname"));
					
					map.put(pagename, new PageInfo(isRedirect, isDisambiguation, length));
				}
			}
		} catch (ClassNotFoundException | SQLException e) {
			var disambs = wb.getPageProps(titles).stream()
				.filter(prop -> prop.containsKey("disambiguation"))
				.map(prop -> (String)prop.get("pagename"))
				.collect(Collectors.toSet());
			
			for (var info : wb.getPageInfo(titles)) {
				var title = (String)info.get("pagename");
				var isRedir = (Boolean)info.get("redirect");
				var length = (Integer)info.get("size");
				
				map.put(title, new PageInfo(isRedir, disambs.contains(title), length));
			}
		}
		
		return map;
	}
	
	private static String prepareText(String text, List<String> sources, Map<String, String> sourceToTarget, boolean isRedirMode) {
		var ignoredRanges = getIgnoredRanges(text);
		
		for (var source : sources) {
			var regex = String.format(PATT_LINK, Pattern.quote(StringUtils.capitalize(source)), Pattern.quote(StringUtils.uncapitalize(source)));
			var target = sourceToTarget.get(source);
			
			text = Utils.replaceWithIgnoredRanges(text, Pattern.compile(regex), ignoredRanges, makeReplacer(source, target, isRedirMode));
			text = replaceAdditionalOccurrences(text, source, target);
		}
		
		return text;
	}
	
	private static Function<MatchResult, String> makeReplacer(String source, String target, boolean isRedirMode) {
		return mr -> {
			var link = mr.group(1);
			var fragment = Optional.ofNullable(mr.group(2)).orElse("");
			var text = Optional.ofNullable(mr.group(3)).orElse(link);
			var trail = Optional.ofNullable(mr.group(4)).orElse("");
			
			final String replacement;
			
			if (isRedirMode && source.equals(text + trail)) {
				replacement = String.format("[[%s%s]]", target, fragment);
			} else if (fragment.isEmpty() && target.equals(text + trail)) {
				replacement = String.format("[[%s]]", target);
			} else if (fragment.isEmpty() && target.equals(text)) {
				replacement = String.format("[[%s]]%s", target, trail);
			} else {
				replacement = String.format("[[%s%s|%s]]", target, fragment, text + trail);
			}
			
			return Matcher.quoteReplacement(replacement);
		};
	}
	
	private static List<Range<Integer>> getIgnoredRanges(String text) {
		var ignoredRanges = IGNORED_REDIR_TEMPLATES.stream()
			.flatMap(templateName -> ParseUtils.getTemplatesIgnoreCase(templateName, text).stream())
			.distinct()
			.map(template -> Pattern.compile(template, Pattern.LITERAL))
			.map(patt -> Utils.findRanges(text, patt))
			.collect(Collectors.toCollection(ArrayList::new));
		
		ignoredRanges.add(Utils.getStandardIgnoredRanges(text));
		
		return Utils.getCombinedRanges(ignoredRanges);
	}
	
	private static String replaceAdditionalOccurrences(String text, String source, String target) {
		var sources = List.of(StringUtils.capitalize(source), StringUtils.uncapitalize(source));
		
		for (var templateName : SOFT_REDIR_TEMPLATES) {
			for (var template : ParseUtils.getTemplatesIgnoreCase(templateName, text)) {
				var params = ParseUtils.getTemplateParametersWithValue(template);
				var hash = params.hashCode();
				
				params.entrySet().stream()
					.filter(e -> StringUtils.equalsAny(templateName, "Zobacz też", "Seealso")
						? e.getKey().equals("ParamWithoutName1")
						: e.getKey().startsWith("ParamWithoutName"))
					.filter(e -> sources.contains(e.getValue()))
					.forEach(e -> e.setValue(target));
				
				if (params.hashCode() != hash) {
					text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
				}
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Link-interwiki", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			var local = params.getOrDefault("pl", params.getOrDefault("ParamWithoutName1", ""));
			
			if (sources.contains(local)) {
				if (params.containsKey("pl")) {
					params.put("pl", target);
				} else {
					params.put("ParamWithoutName1", target);
				}
				
				if (!params.containsKey("tekst") && !params.containsKey("ParamWithoutName4")) {
					if (!params.containsKey("ParamWithoutName3")) {
						params.put("tekst", local);
					} else {
						params.put("ParamWithoutName4", local);
					}
				}
				
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Sort", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			var key = params.getOrDefault("ParamWithoutName1", "");
			
			if (sources.contains(key) && !params.containsKey("ParamWithoutName2")) {
				params.put("ParamWithoutName2", String.format("[[%s|%s]]", target, key));
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Sortname", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			
			if (!params.containsKey("nolink")) {
				var key = "";
				
				if (params.containsKey("ParamWithoutName3")) {
					key = params.get("ParamWithoutName3");
				} else {
					var name = params.getOrDefault("ParamWithoutName1", "");
					var surname = params.getOrDefault("ParamWithoutName2", "");
					key = String.format("%s %s", name, surname);
				}
				
				if (sources.contains(key)) {
					params.put("ParamWithoutName3", target);
					text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
				}
			}
		}
		
		return text;
	}
	
	private record PageInfo(boolean isRedirect, boolean isDisambiguation, int length) {}
}
