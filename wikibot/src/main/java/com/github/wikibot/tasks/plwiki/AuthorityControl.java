package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONObject;

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
			"P7305", "P2038", "P1053", "P3829", "P3124", "P2036", "P838", "P830", "P6177", "P1727", "P6094", "P846",
			"P1421", "P3151", "P961", "P815", "P685", "P1070", "P3105", "P960", "P1772"
		);
	
	private static final Properties defaultSQLProperties = new Properties();
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
		} else {
			if (cli.hasOption("cron")) {
				articles = null; // TODO
			} else if (cli.hasOption("dump")) {
				Set<Long> revids = Collections.emptySet();
				
				if (cli.hasOption("stub")) {
					var stub = cli.getOptionValue("stub");
					revids = retrieveRevids(Paths.get(stub));
					System.out.printf("Retrieved %d revisions.%n", revids.size());
				}
				
				var dump = cli.getOptionValue("dump");
				articles = processDump(Paths.get(dump), revids);
			} else {
				throw new RuntimeException("missing mandatory CLI parameters");
			}
			
			System.out.printf("Got %d unfiltered articles.%n", articles.size());
			Files.write(LOCATION.resolve("latest-unfiltered.txt"), articles);
			
			filterArticles(articles);
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
		options.addOption("s", "stub", true, "stub file to retrieve unique item identifiers from");
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
		
		return new HashSet<>(newestRevids.values());
	}
	
	private static List<String> processDump(Path path, Set<Long> revids) throws IOException {
		var reader = new XMLDumpReader(path);
		
		try (var stream = reader.getStAXReader().stream()) {
			return stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> rev.getTitle().matches("^Q\\d+$"))
				.filter(rev -> revids.isEmpty() || revids.contains(rev.getRevid()))
				.filter(rev -> !rev.isRevisionDeleted())
				.map(rev -> new JSONObject(rev.getText()))
				// https://doc.wikimedia.org/Wikibase/master/php/md_docs_topics_json.html
				.filter(json -> json.optString("type").equals("item"))
				.filter(json -> Optional.ofNullable(json.optJSONObject("sitelinks")).filter(sl -> sl.has("plwiki")).isPresent())
				.filter(json -> Optional.ofNullable(json.optJSONObject("claims")).filter(AuthorityControl::testClaims).isPresent())
				.map(json -> json.getJSONObject("sitelinks").getJSONObject("plwiki").getString("title"))
				.distinct() // hist-incr dumps may list several revisions per page
				.collect(Collectors.toList());
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
	
	private static void filterArticles(List<String> titles) throws ClassNotFoundException, SQLException, IOException {
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
		
		System.out.printf("Got %d template transclusions.%n", transclusions.size());
		titles.removeAll(transclusions);
	}
}
