package com.github.wikibot.tasks.plwiki;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONObject;
import org.json.JSONPointer;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class AuthorityControl {
	private static final Path LOCATION = Paths.get("./data/tasks.plwiki/AuthorityControl/");
	
	private static final List<String> TEMPLATES = List.of("Kontrola autorytatywna", "Authority control", "Ka");
	
	// https://pl.wikipedia.org/wiki/Szablon:Kontrola_autorytatywna#Lista_wspieranych_baz
	private static final List<String> PROPERTIES = List.of(
		"P213", "P496", "P214", "P245", "P727", "P244", "P227", "P349", "P5587", "P906", "P268", "P269", "P396",
		"P409", "P508", "P691", "P723", "P947", "P950", "P1003", "P1006", "P1015", "P270", "P271", "P648", "P7293",
		"P1695", "P1207", "P1415", "P949", "P1005", "P1273", "P1368", "P1375", "P3788", "P1280", "P1890", "P3348",
		"P4619", "P5034", "P7699", "P3133", "P5504", "P7859",
		// bonus
		"P7305", "P2038", "P1053", "P3829", "P3124", "P2036", "P838", "P830", "P6177", "P1747", "P1727", "P6094",
		"P846", "P1421", "P3151", "P961", "P815", "P685", "P6034", "P1070", "P5037", "P3105", "P960", "P1772"
	);
	
	private static final Properties defaultSQLProperties = new Properties();
	private static final String SQL_WDWIKI_URI = "jdbc:mysql://wikidatawiki.analytics.db.svc.wikimedia.cloud:3306/wikidatawiki_p";
	private static final String SQL_PLWIKI_URI = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
	
	private static Wikibot wb;
	
	static {
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
				articles = null; // TODO
			} else if (cli.hasOption("dump")) {
				var dump = cli.getOptionValue("dump");
				var reader = new XMLDumpReader(dump);
				
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
			
			var transclusions = retrieveTemplateTransclusions();
			articles.removeAll(transclusions);
			System.out.printf("Got %d filtered articles", articles.size());
			
			if (!articles.isEmpty()) {
				Files.write(LOCATION.resolve("latest-filtered.txt"), articles);
			}
		}
		
		if (!articles.isEmpty() && (cli.hasOption("process") || cli.hasOption("file"))) {
			wb = Login.createSession("pl.wikipedia.org");
			; // TODO
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
	
	private static Set<Long> retrieveRevids(Path path) throws IOException {
		var reader = new XMLDumpReader(path);
		final Map<Long, Long> newestRevids;
		
		try (var stream = reader.getStAXReader().stream()) {
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
	
	private static List<String> processDumpFile(XMLDumpReader reader) {
		return processDumpFile(reader, 0, 0, rev -> true);
	}
	
	private static List<String> processDumpFile(XMLDumpReader reader, Predicate<XMLRevision> pred) {
		return processDumpFile(reader, 0, 0, pred);
	}
	
	private static List<String> processDumpFile(XMLDumpReader reader, long offset, int size, Predicate<XMLRevision> pred) {
		final var qPatt = Pattern.compile("^Q\\d+$");
		
		try (var stream = reader.getStAXReader(offset, size).stream()) {
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
		} catch (IOException e) {
			throw new UncheckedIOException(e);
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
		
		var backlinks = new HashMap<Long, String>(600000);
		
		try (var connection = DriverManager.getConnection(SQL_WDWIKI_URI, prepareSQLProperties())) {
			var properties = PROPERTIES.stream()
				.map(template -> String.format("'%s'", template))
				.collect(Collectors.joining(","));
			
			var query = "SELECT DISTINCT(page_id), ips_site_page"
				+ " FROM page"
				+ " INNER JOIN pagelinks on pl_from = page_id"
				+ " INNER JOIN wb_items_per_site on page_namespace = 0 AND CONCAT('Q', ips_item_id) = page_title"
				+ " WHERE pl_from_namespace = 0"
				+ " AND ips_site_id = 'plwiki'"
				+ " AND pl_namespace = 120" // Property
				+ " AND pl_title in (" + properties + ");";
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var id = rs.getLong("page_id");
				var sitePage = rs.getString("ips_site_page").replace('_', ' ');
				backlinks.put(id, sitePage);
			}
		}
		
		System.out.printf("Got %d property backlinks with plwiki usage.%n", backlinks.size());
		return backlinks;
	}
	
	private static Map<Path, Map.Entry<List<Long>, SortedSet<Long>>> prepareWorklist(Path base, JSONObject files) throws IOException {
		SortedSet<Long> pageids;
		
		try {
			var backlinks = retrievePropertyBacklinks();
			var transclusions = retrieveTemplateTransclusions();
			backlinks.values().removeAll(transclusions);
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
			.collect(Collectors.toList());
		
		var worklist = new LinkedHashMap<Path, Map.Entry<List<Long>, SortedSet<Long>>>(indexes.size(), 1.0f);
		
		for (var mr : indexes) {
			var subset = pageids.subSet(Long.parseLong(mr.group(1)), Long.parseLong(mr.group(2)) + 1);
			
			if (!subset.isEmpty()) {
				var filename = mr.group();
				var offsets = retrieveOffsets(base.resolve(filename), subset);
				var dump = filename.replaceFirst("-index(\\d+)\\.txt", "$1.xml");
				worklist.put(base.resolve(dump), Map.entry(offsets, subset));
			}
		}
		
		return worklist;
	}
	
	private static List<Long> retrieveOffsets(Path path, Set<Long> pageids) throws IOException {
		var offsets = new HashSet<Long>(5000);
		var file = path.toFile();
		
		try (var reader = new BufferedReader(new InputStreamReader(new BZip2CompressorInputStream(new FileInputStream(file))))) {
			reader.lines()
				.map(line -> Map.entry(
					Long.parseLong(line.substring(0, line.indexOf(':'))),
					Long.parseLong(line.substring(line.indexOf(':') + 1, line.indexOf(':', line.indexOf(':') + 1)))
				))
				.filter(e -> !offsets.contains(e.getKey()))
				.filter(e -> pageids.contains(e.getValue()))
				.map(Map.Entry::getKey)
				.forEach(offsets::add);
		}
		
		System.out.printf("Inspected %s: %d offsets (%d pageids).%n", path.getFileName(), offsets.size(), pageids.size());
		return offsets.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
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
			var reader = new XMLDumpReader(dumpEntry.getKey());
			var offsets = dumpEntry.getValue().getKey();
			var pageids = dumpEntry.getValue().getValue();
			
			for (var offset : offsets) {
				var batch = processDumpFile(reader, offset, 100, rev -> pageids.contains(rev.getPageid()));
				results.addAll(batch);
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
		
		if (!cnf.toFile().exists()) {
			cnf = LOCATION.resolve(".my.cnf");
		}
		
		Files.lines(cnf)
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
			
			var query = "SELECT page_title"
				+ " FROM page INNER JOIN templatelinks on tl_from = page_id"
				+ " WHERE tl_from_namespace = 0"
				+ " AND tl_namespace = 10"
				+ " AND tl_title in (" + templates + ");";
			
			var rs = connection.createStatement().executeQuery(query);
			
			while (rs.next()) {
				var title = rs.getString("page_title").replace('_', ' ');
				transclusions.add(title);
			}
		}
		
		System.out.printf("Got %d template transclusions on plwiki.%n", transclusions.size());
		return transclusions;
	}
}
