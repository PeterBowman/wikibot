package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wikipedia.org");
		
		var cli = readOptions(args);
		
		final List<String> articles;
		
		if (cli.hasOption("cron")) {
			var cron = cli.getOptionValue("cron");
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
		} else if (cli.hasOption("file")) {
			var file = cli.getOptionValue("file");
			articles = Files.readAllLines(LOCATION.resolve(file));
		} else {
			throw new RuntimeException("missing mandatory CLI parameters");
		}
		
		System.out.printf("Got %d articles.%n", articles.size());
		
		if (!articles.isEmpty()) {
			if (!cli.hasOption("file")) {
				Files.write(LOCATION.resolve("latest.txt"), articles);
			}
			
			if (cli.hasOption("process") || cli.hasOption("file")) {
				; // TODO
			}
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
}
