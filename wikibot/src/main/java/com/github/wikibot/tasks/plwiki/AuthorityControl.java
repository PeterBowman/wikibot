package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.security.auth.login.AccountLockedException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.json.JSONPointer;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class AuthorityControl {
	private static final Path LOCATION = Paths.get("./data/tasks.plwiki/AuthorityControl/");
	
	private static final List<String> TEMPLATES = List.of("Kontrola autorytatywna", "Authority control", "Ka");
	private static final List<String> PROPERTIES;
	
	private static final Properties defaultSQLProperties = new Properties();
	private static final String SQL_WDWIKI_URI = "jdbc:mysql://wikidatawiki.analytics.db.svc.wikimedia.cloud:3306/wikidatawiki_p";
	private static final String SQL_PLWIKI_URI = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
	
	private static final Pattern P_TEXT = Pattern.compile(
		"(?:\\{{2}\\s*+(?:SORTUJ|DOMYŚLNIESORTUJ|DEFAULTSORT|DEFAULTSORTKEY|DEFAULTCATEGORYSORT):[^\\}]*+\\}{2})?+(?:\\s*+\\[{2} *+(?i:Kategoria|Category) *+:[^\\]\\{\\}\n]*+\\]{2})*+(?:\\s*+(?i:__NOINDEX__|__NIEINDEKSUJ__|__NOTOC__|__BEZSPISU__))?+(?:\\s*+\\[{2} *+[a-z-]++ *+:[^\\]\\{\\}\n]*+\\]{2})*+$"
	);
	
	static {
		var patt = Pattern.compile("^(P\\d+) *+(?=#|$)");
		
		try {
			// https://pl.wikipedia.org/wiki/Szablon:Kontrola_autorytatywna#Lista_wspieranych_baz
			PROPERTIES = Files.readAllLines(LOCATION.resolve("properties.txt")).stream()
				.map(patt::matcher)
				.flatMap(Matcher::results)
				.map(m -> m.group(1))
				.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		
		defaultSQLProperties.setProperty("autoReconnect", "true");
		defaultSQLProperties.setProperty("useUnicode", "yes");
		defaultSQLProperties.setProperty("characterEncoding", StandardCharsets.UTF_8.name());
		defaultSQLProperties.setProperty("sslMode", "DISABLED");
	}
	
	public static void main(String[] args) throws Exception {
		var cli = readOptions(args);
		final List<String> articles;
		
		if (cli.hasOption("file")) {
			var file = cli.getOptionValue("file");
			articles = Files.readAllLines(LOCATION.resolve(file));
			System.out.printf("Retrieved %d articles from list.%n", articles.size());
		} else if (cli.hasOption("articles")) {
			var status = cli.getOptionValue("articles");
			articles = processDumpCollection(Paths.get(status));
		} else {
			if (cli.hasOption("cron")) {
				var cron = cli.getOptionValue("cron");
				articles = processCronDumps(Paths.get(cron));
			} else if (cli.hasOption("dump")) {
				var dump = cli.getOptionValue("dump");
				var reader = new XMLDumpReader(Paths.get(dump));
				
				if (cli.hasOption("stub")) {
					var stub = cli.getOptionValue("stub");
					var revids = retrieveRevids(Paths.get(stub));
					articles = processDumpFile(reader, rev -> revids.contains(rev.getRevid()));
				} else {
					articles = processDumpFile(reader);
				}
			} else {
				throw new RuntimeException("missing mandatory CLI parameters");
			}
			
			System.out.printf("Got %d unfiltered articles.%n", articles.size());
			Files.write(LOCATION.resolve("latest-unfiltered.txt"), articles);
			
			var wiki = Wiki.newSession("pl.wikipedia.org");
			articles.removeAll(retrieveTemplateTransclusions());
			articles.retainAll(retrieveNonRedirects());
			articles.removeIf(title -> wiki.namespace(title) != Wiki.MAIN_NAMESPACE);
			System.out.printf("Got %d filtered articles.%n", articles.size());
			
			if (!articles.isEmpty()) {
				Files.write(LOCATION.resolve("latest-filtered.txt"), articles);
			}
		}
		
		if (!articles.isEmpty() && (cli.hasOption("process") || cli.hasOption("file"))) {
			var wb = Login.createSession("pl.wikipedia.org");
			var warnings = new ArrayList<String>();
			var errors = new ArrayList<String>();
			
			for (var page : wb.getContentOfPages(articles)) {
				try {
					var optText = prepareText(page.getText());
					
					if (optText.isPresent()) {
						wb.edit(page.getTitle(), optText.get(), "wstawienie {{Kontrola autorytatywna}}", page.getTimestamp());
					}
				} catch (UnsupportedOperationException e) {
					warnings.add(page.getTitle());
					System.out.printf("Parse exception in %s: %s%n", page.getTitle(), e.getMessage());
				} catch (Throwable t) {
					errors.add(page.getTitle());
					t.printStackTrace();
					
					if (t instanceof AccountLockedException | t instanceof AssertionError) {
						break;
					}
				}
			}
			
			if (!warnings.isEmpty()) {
				System.out.printf("%d warnings: %s%n", warnings.size(), warnings);
			}
			
			updateWarningsList(warnings);
			
			if (!errors.isEmpty()) {
				System.out.printf("%d errors: %s%n", errors.size(), errors);
				throw new RuntimeException("Errors: " + errors.size());
			}
		}
	}
	
	private static CommandLine readOptions(String[] args) throws ParseException {
		var options = new Options();
		
		options.addOption("c", "cron", true, "inspect latest daily incr dump");
		options.addOption("d", "dump", true, "inspect dump file");
		options.addOption("s", "stub", true, "retrieve unique item identifiers from provided stub file");
		options.addOption("a", "articles", true, "process full articles dump from provided dumpstatus.json");
		options.addOption("p", "process", false, "process articles on wiki");
		options.addOption("f", "file", true, "process list of wiki articles saved on disk");
		
		if (args.length == 0) {
			System.out.print("Option(s): ");
			String input = Misc.readLine();
			args = input.split(" ");
		}
		
		try {
			return new DefaultParser().parse(options, args);
		} catch (ParseException e) {
			new HelpFormatter().printHelp(AuthorityControl.class.getName(), options);
			throw e;
		}
	}
	
	private static List<String> processCronDumps(Path base) throws IOException {
		var date = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
		var dir = base.resolve(date);
		var status = dir.resolve("status.txt");
		
		if (!Files.readString(status).equals("done:all")) {
			throw new RuntimeException("daily incr dumps not ready yet");
		}
		
		var stubs = dir.resolve(String.format("wikidatawiki-%s-stubs-meta-hist-incr.xml.gz", date));
		var pages = dir.resolve(String.format("wikidatawiki-%s-pages-meta-hist-incr.xml.bz2", date));
		
		var revids = retrieveRevids(stubs);
		var reader = new XMLDumpReader(pages);
		
		return processDumpFile(reader, rev -> revids.contains(rev.getRevid()));
	}
	
	private static Set<Long> retrieveRevids(Path path) throws IOException {
		var reader = new XMLDumpReader(path);
		final Map<Long, Long> newestRevids;
		
		try (var stream = reader.getStAXReaderStream()) {
			newestRevids = stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> rev.getTitle().matches("^Q\\d+$"))
				.collect(Collectors.toMap(
					XMLRevision::getPageid,
					XMLRevision::getRevid,
					(a, b) -> b
				));
		}
		
		var revids = new HashSet<>(newestRevids.values());
		System.out.printf("Retrieved %d revisions.%n", revids.size());
		return revids;
	}
	
	private static List<String> processDumpFile(XMLDumpReader reader) throws IOException {
		return processDumpFile(reader, rev -> true);
	}
	
	private static List<String> processDumpFile(XMLDumpReader reader, Predicate<XMLRevision> pred) throws IOException {
		final var qPatt = Pattern.compile("^Q\\d+$");
		
		try (var stream = reader.getStAXReaderStream()) {
			return stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> qPatt.matcher(rev.getTitle()).matches())
				.filter(pred)
				.filter(rev -> !rev.isRevisionDeleted())
				.map(rev -> new JSONObject(rev.getText()))
				// https://doc.wikimedia.org/Wikibase/master/php/md_docs_topics_json.html
				.filter(json -> json.optString("type").equals("item"))
				.filter(json -> Optional.ofNullable(json.optJSONObject("sitelinks")).filter(sl -> sl.has("plwiki")).isPresent())
				.filter(json -> Optional.ofNullable(json.optJSONObject("claims")).filter(AuthorityControl::testClaims).isPresent())
				.map(json -> json.getJSONObject("sitelinks").getJSONObject("plwiki").getString("title"))
				.distinct() // hist-incr dumps may list several revisions per page
				.collect(Collectors.toCollection(ArrayList::new));
		}
	}
	
	private static boolean testClaims(JSONObject json) {
		var count = PROPERTIES.stream().filter(json::has).count();
		
		if (count >= 3) {
			return true;
		} else if (count == 0 || !json.has("P31")) {
			return false;
		}
		
		var isHuman = StreamSupport.stream(json.getJSONArray("P31").spliterator(), false)
			.map(obj -> ((JSONObject)obj).getJSONObject("mainsnak"))
			.filter(mainsnak -> mainsnak.getString("snaktype").equals("value"))
			.map(mainsnak -> mainsnak.getJSONObject("datavalue"))
			.filter(snakvalue -> snakvalue.getString("type").equals("wikibase-entityid"))
			.map(snakvalue -> snakvalue.getJSONObject("value"))
			.anyMatch(value -> value.getString("id").equals("Q5"));
		
		// P214: VIAF, P1207: NUKAT
		return isHuman && (count > 1 || json.has("P214") || json.has("P1207"));
	}
	
	private static Map<Long, String> retrievePropertyBacklinks() throws ClassNotFoundException, SQLException, IOException {
		Class.forName("com.mysql.cj.jdbc.Driver");
		
		var wiki = Wiki.newSession("pl.wikipedia.org");
		var backlinks = new HashMap<Long, String>(600000);
		
		try (var connection = DriverManager.getConnection(SQL_WDWIKI_URI, prepareSQLProperties())) {
			var properties = PROPERTIES.stream()
				.map(template -> String.format("'%s'", template))
				.collect(Collectors.joining(","));
			
			var query = String.format("""
				SELECT
					DISTINCT(page_id),
					ips_site_page
				FROM page
					INNER JOIN pagelinks ON pl_from = page_id
					INNER JOIN wb_items_per_site ON page_namespace = 0 AND CONCAT('Q', ips_item_id) = page_title
				WHERE
					pl_from_namespace = 0 AND
					ips_site_id = 'plwiki' AND
					pl_namespace = 120 AND
					pl_title in (%s);
				""", properties);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var id = rs.getLong("page_id");
				var sitePage = rs.getString("ips_site_page").replace('_', ' ');
				
				if (wiki.namespace(sitePage) == Wiki.MAIN_NAMESPACE) {
					backlinks.put(id, sitePage);
				}
			}
		}
		
		System.out.printf("Got %d property backlinks with plwiki usage.%n", backlinks.size());
		return backlinks;
	}
	
	private static Map<Map.Entry<Path, Path>, SortedSet<Long>> prepareWorklist(Path base, JSONObject files) throws IOException {
		SortedSet<Long> pageids;
		
		try {
			var backlinks = retrievePropertyBacklinks();
			backlinks.values().removeAll(retrieveTemplateTransclusions());
			backlinks.values().retainAll(retrieveNonRedirects());
			pageids = new TreeSet<>(backlinks.keySet());
		} catch (ClassNotFoundException | SQLException | IOException e) {
			throw new RuntimeException(e);
		}
		
		System.out.printf("Got %d backlink candidates with no transclusions on plwiki.%n", pageids.size());
		
		final var patt = Pattern.compile("^.+?\\bindex\\d+\\.txt-p(\\d+)p(\\d+)\\b.+?$");
		
		var indexes = files.keySet().stream()
			.map(patt::matcher)
			.flatMap(Matcher::results)
			.sorted((mr1, mr2) -> Long.compare(Long.parseLong(mr1.group(1)), Long.parseLong(mr2.group(1))))
			.toList();
		
		var worklist = new LinkedHashMap<Map.Entry<Path, Path>, SortedSet<Long>>(indexes.size(), 1.0f);
		
		for (var mr : indexes) {
			var subset = pageids.subSet(Long.parseLong(mr.group(1)), Long.parseLong(mr.group(2)) + 1);
			
			if (!subset.isEmpty()) {
				var index = mr.group();
				var dump = index.replaceFirst("-index(\\d+)\\.txt\\b", "$1.xml");
				worklist.put(Map.entry(base.resolve(dump), base.resolve(index)), subset);
			}
		}
		
		return worklist;
	}
	
	private static List<String> processDumpCollection(Path path) throws IOException {
		var statusText = Files.readString(path);
		var json = new JSONObject(statusText);
		var pStatus = new JSONPointer("/jobs/articlesmultistreamdump/status");
		var pFiles = new JSONPointer("/jobs/articlesmultistreamdump/files");
		
		if (!"done".equals(pStatus.queryFrom(json))) {
			throw new RuntimeException("articlesmultistreamdump not ready yet");
		}
		
		var files = (JSONObject)pFiles.queryFrom(json);
		var worklist = prepareWorklist(path.getParent(), files);
		var results = new ArrayList<String>();
		
		System.out.printf("Reading from %d dump files.%n", worklist.size());
		
		for (var dumpEntry : worklist.entrySet()) {
			var paths = dumpEntry.getKey();
			var pageids = dumpEntry.getValue();
			var reader = new XMLDumpReader(paths.getKey(), paths.getValue());
			
			try {
				var batch = processDumpFile(reader.seekIds(pageids));
				results.addAll(batch);
				System.out.printf("Got %d articles in last batch.%n", batch.size());
			} catch (IOException e) {
				System.out.printf("IOException at file %s: %s.%n", paths.getKey(), e.getMessage());
				System.out.printf("Caused by: %s.%n", e.getCause());
			}
		}

		System.out.printf("Got %d filtered articles.%n", results.size());
		Files.write(LOCATION.resolve("articlesdump.txt"), results);
		return results;
	}
	
	private static Properties prepareSQLProperties() throws IOException {
		var properties = new Properties(defaultSQLProperties);
		var patt = Pattern.compile("(.+)='(.+)'");
		var cnf = Paths.get("./replica.my.cnf");
		
		if (!Files.exists(cnf)) {
			cnf = LOCATION.resolve(".my.cnf");
		}
		
		Files.readAllLines(cnf).stream()
			.map(patt::matcher)
			.filter(Matcher::matches)
			.forEach(m -> properties.setProperty(m.group(1), m.group(2)));
		
		return properties;
	}
	
	private static Set<String> retrieveTemplateTransclusions() throws ClassNotFoundException, SQLException, IOException {
		Class.forName("com.mysql.cj.jdbc.Driver");
		
		var transclusions = new HashSet<String>(500000);
		
		try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, prepareSQLProperties())) {
			var templates = TEMPLATES.stream()
				.map(template -> String.format("'%s'", template.replace(' ', '_')))
				.collect(Collectors.joining(","));
			
			var query = String.format("""
				SELECT
					page_title
				FROM page
					INNER JOIN templatelinks on tl_from = page_id
				WHERE
					tl_from_namespace = 0 AND
					tl_namespace = 10 AND
					tl_title in (%s);
				""", templates);
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var title = rs.getString("page_title").replace('_', ' ');
				transclusions.add(title);
			}
		}
		
		System.out.printf("Got %d template transclusions on plwiki.%n", transclusions.size());
		return transclusions;
	}
	
	private static Set<String> retrieveNonRedirects() throws ClassNotFoundException, SQLException, IOException {
		Class.forName("com.mysql.cj.jdbc.Driver");
		
		var articles = new HashSet<String>(2000000);
		
		try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, prepareSQLProperties())) {
			var query = """
				SELECT page_title
				FROM page
				WHERE page_namespace = 0 AND page_is_redirect = 0;
				""";
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var title = rs.getString("page_title").replace('_', ' ');
				articles.add(title);
			}
		}
		
		System.out.printf("Got %d non-redirect articles on plwiki.%n", articles.size());
		return articles;
	}
	
	private static Optional<String> prepareText(String text) {
		if (TEMPLATES.stream().anyMatch(template -> !ParseUtils.getTemplatesIgnoreCase(template, text).isEmpty())) {
			return Optional.empty();
		}
		
		var m = P_TEXT.matcher(text);
		
		if (!m.find()) {
			throw new UnsupportedOperationException("no match found");
		}
		
		var sb = new StringBuilder(text.length());
		var body = text.substring(0, m.start()).stripTrailing();
		var footer = text.substring(m.start()).stripLeading();
		
		if (StringUtils.containsAnyIgnoreCase(body, "#PATRZ", "#PRZEKIERUJ", "#TAM", "#REDIRECT")) {
			return Optional.empty(); // ignore redirs, there's nothing we can do
		}
		
		if (body.matches("(?s).*?(?:SORTUJ|DOMYŚLNIESORTUJ|DEFAULTSORT|DEFAULTSORTKEY|DEFAULTCATEGORYSORT).*")) {
			throw new UnsupportedOperationException("sort magic word found in article body");
		}
		
		if (body.matches("(?s).*?\\[{2} *+(?i:Kategoria|Category) *+:[^\\]\\{\\}\n]*+\\]{2}.*")) {
			throw new UnsupportedOperationException("category found in article body");
		}
		
		if (footer.matches("(?s).*?\\[{2} *+(?i:Plik|File|Image) *+:.+")) {
			throw new UnsupportedOperationException("file found in article footer");
		}
		
		var pre = body.endsWith("-->") ? "\n" : "\n\n";
		sb.append(body).append(pre).append("{{Kontrola autorytatywna}}").append("\n\n").append(footer);
		return Optional.of(sb.toString().stripTrailing());
	}
	
	private static void updateWarningsList(List<String> titles) throws ClassNotFoundException, SQLException, IOException {
		var log = LOCATION.resolve("warnings.txt");
		var set = new TreeSet<>(titles);
		
		if (Files.exists(log)) {
			set.addAll(Files.readAllLines(log));
		}
		
		set.removeAll(retrieveTemplateTransclusions());
		Files.write(log, set);
	}
}
