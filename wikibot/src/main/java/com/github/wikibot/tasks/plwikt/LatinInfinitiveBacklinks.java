package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.utils.Login;

public class LatinInfinitiveBacklinks {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/LatinInfinitiveBacklinks/");
	private static final String TARGET_PAGE = "Wikisłownikarz:PBbot/łacińskie bezokoliczniki w etymologii";
	
	private static final List<String> LATIN_SHORTS = List.of("łac", "łaciński", "łacińskie", "łacina", "stłac");
	private static final List<String> LATIN_DESINENCES = List.of("are", "āre", "ere", "ēre", "ĕre", "ire", "īre");
	
	private static final String INTRO;
	
	static {
		INTRO = """
			Prawdopodobne wystąpienia łacińskich bezokoliczników w wywołaniach szablonów etymologii
			({{s|etym}}, {{s|etym2}}, {{s|etymn}} oraz {{s|etymn2}}):
			[[Specjalna:Niezmienny link/7662696#Łacińskie bezokoliczniki|dyskusja]], [[/mapowania|mapowania]].
			
			Dane na podstawie %s. Aktualizacja: ~~~~~.
			----
			""";
	}
	
	public static void main(String[] args) throws Exception {
		var wb = Login.createSession("pl.wiktionary.org");
		var reader = getXMLReader(args);
		var occurrences = new TreeMap<String, Set<String>>();
		
		try (var stream = reader.getStAXReaderStream()) {
			stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.forEach(rev -> mapOccurrences(rev.getTitle(), rev.getText(), occurrences));
		}
		
		System.out.printf("Got %d unfiltered occurrences.%n", occurrences.size());
		
		var titles = occurrences.keySet().stream().toList();
		occurrences.clear();
		
		wb.getContentOfPages(titles).stream().forEach(pc -> mapOccurrences(pc.getTitle(), pc.getText(), occurrences));
		System.out.printf("Got %d filtered occurrences.%n", occurrences.size());
		
		var hash = LOCATION.resolve("hash.txt");
		
		if (!Files.exists(hash) || Integer.parseInt(Files.readString(hash)) != occurrences.hashCode()) {
			var list = occurrences.entrySet().stream()
				.map(e -> String.format("#[[%s]]: %s", e.getKey(), e.getValue().stream().collect(Collectors.joining(", "))))
				.toList();
			
			Files.write(LOCATION.resolve("results.txt"), list);
			
			var text = String.format(INTRO, reader.getPathToDump().getFileName().toString()) + String.join("\n", list);
			wb.edit(TARGET_PAGE, text, "aktualizacja", false, false, -2, null);
			
			Files.writeString(hash, Integer.toString(occurrences.hashCode()));
		} else {
			System.out.println("No changes detected.");
		}
	}
	
	private static XMLDumpReader getXMLReader(String[] args) throws ParseException, IOException {
		if (args.length != 0) {
			var options = new Options();
			options.addOption("d", "dump", true, "read from dump file");
			
			var parser = new DefaultParser();
			var line = parser.parse(options, args);
			
			if (line.hasOption("dump")) {
				var pathToFile = line.getOptionValue("dump");
				return new XMLDumpReader(Paths.get(pathToFile));
			} else {
				new HelpFormatter().printHelp(LatinInfinitiveBacklinks.class.getName(), options);
				throw new IllegalArgumentException();
			}
		} else {
			return new XMLDumpReader("plwiktionary");
		}
	}
	
	private static void mapOccurrences(String title, String text, Map<String, Set<String>> occurrences) {
		Stream.of(
				ParseUtils.getTemplates("etym", text).stream(),
				ParseUtils.getTemplates("etymn", text).stream(),
				ParseUtils.getTemplates("etym2", text).stream(),
				ParseUtils.getTemplates("etymn2", text).stream()
			)
			.flatMap(Function.identity())
			.map(ParseUtils::getTemplateParametersWithValue)
			.filter(params -> StringUtils.equalsAny(params.getOrDefault("ParamWithoutName1", ""), LATIN_SHORTS.toArray(new String[0])))
			.filter(params -> params.getOrDefault("ParamWithoutName2", "").length() > 3)
			.map(params -> params.get("ParamWithoutName2"))
			.filter(param -> !param.contains("-"))
			.filter(param -> StringUtils.endsWithAny(param, LATIN_DESINENCES.toArray(new String[0])))
			.forEach(param -> occurrences.computeIfAbsent(title, item -> new TreeSet<>()).add(param));
	}
}
