package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;

public final class MissingUkrainianAudio {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingUkrainianAudio/");
	
	public static void main(String[] args) throws Exception {
		var reader = getXMLReader(args);
		final List<String> titles;
		
		try (var stream = reader.getStAXReader().stream()) {
			titles = stream
				.filter(XMLRevision::nonRedirect)
				.filter(XMLRevision::isMainNamespace)
				.map(Page::wrap)
				.flatMap(p -> p.getSection("ukraiński", true).stream())
				.flatMap(s -> s.getField(FieldTypes.PRONUNCIATION).stream())
				.filter(f -> f.isEmpty() || ParseUtils.getTemplates("audio", f.getContent()).isEmpty())
				.map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
				.sorted(Collator.getInstance(new Locale("uk")))
				.collect(Collectors.toList());
		}
		
		var path = LOCATION.resolve("titles.txt");
		
		Files.writeString(path, String.format("Generated from %s.%n%s%n", reader.getFile().getName(), "-".repeat(60)));
		Files.write(LOCATION.resolve("titles.txt"), titles, StandardOpenOption.APPEND);
	}
	
	private static XMLDumpReader getXMLReader(String[] args) throws ParseException, FileNotFoundException {
		if (args.length != 0) {
			Options options = new Options();
			options.addOption("d", "dump", true, "read from dump file");
			
			CommandLineParser parser = new DefaultParser();
			CommandLine line = parser.parse(options, args);
			
			if (line.hasOption("dump")) {
				String pathToFile = line.getOptionValue("dump");
				return new XMLDumpReader(new File(pathToFile));
			} else {
				new HelpFormatter().printHelp(MisusedRegTemplates.class.getName(), options);
				throw new IllegalArgumentException();
			}
		} else {
			return new XMLDumpReader("plwiktionary");
		}
	}
}
