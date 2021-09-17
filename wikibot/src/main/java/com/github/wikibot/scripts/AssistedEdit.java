package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class AssistedEdit {
	private static final Path LOCATION = Paths.get("./data/scripts/AssistedEdit/");
	private static final Path TITLES = LOCATION.resolve("titles.txt");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path TIMESTAMPS = LOCATION.resolve("timestamps.xml");
	private static final Path HASHCODES = LOCATION.resolve("hash.xml");
	private static final String WORKLIST_FILTERED_FORMAT = "worklist-%s-%s.txt";
	
	private static Wikibot wb;
	private static XStream xstream;
	
	public static void main(String[] args) throws Exception {
		Options options = new Options();
		options.addRequiredOption("o", "op", true, "mode of operation: query|apply");
		options.addRequiredOption("d", "domain", true, "wiki domain name");
		options.addOption("s", "summary", true, "edit summary");
		options.addOption("m", "minor", false, "mark edits as minor");
		options.addOption("t", "throttle", true, "set edit throttle [ms]");
		options.addOption("l", "language", true, "short language name (only pl.wiktionary.org)");
		options.addOption("f", "field", true, "field type (only pl.wiktionary.org)");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		CommandLineParser parser = new DefaultParser();
		CommandLine line = parser.parse(options, args);
		
		String domain = line.getOptionValue("domain");
		
		if (line.hasOption("language") && !domain.equals("pl.wiktionary.org")) {
			throw new IllegalArgumentException("Section parser was requested, but project is not pl.wiktionary.org.");
		}
		
		if (line.hasOption("field") && !domain.equals("pl.wiktionary.org")) {
			throw new IllegalArgumentException("Field parser was requested, but project is not pl.wiktionary.org.");
		}
		
		String sectionName = line.getOptionValue("language");
		
		String fieldName = line.getOptionValue("field");
		FieldTypes fieldType = null;
		
		if (fieldName != null) {
			fieldType = Stream.of(FieldTypes.values())
				.filter(type -> type.localised().equals(fieldName))
				.findAny()
				.orElseThrow();
		}
		
		wb = Login.createSession(domain);
		xstream = new XStream();
		
		String throttle = line.getOptionValue("throttle", "5000");
		wb.setThrottle(Integer.parseInt(throttle));
		
		switch (line.getOptionValue("op")) {
			case "query":
				getContents(sectionName, fieldType);
				break;
			case "apply":
				applyChanges(sectionName, fieldType, line.getOptionValue("summary"), line.hasOption("minor"));
				break;
			default:
				new HelpFormatter().printHelp(AssistedEdit.class.getName(), options);
				throw new IllegalArgumentException();
		}
	}
	
	public static void getContents(String sectionName, FieldTypes fieldType) throws IOException {
		List<String> titles = Files.readAllLines(TITLES).stream()
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.distinct()
			.toList();
		
		System.out.printf("Size: %d%n", titles.size());
		
		if (titles.isEmpty()) {
			return;
		}

		List<PageContainer> pages = wb.getContentOfPages(titles);
		
		Map<String, String> map = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getText,
				(a, b) -> a,
				LinkedHashMap::new
			));
		
		Files.write(WORKLIST, List.of(Misc.makeList(map)));
		
		Map<String, OffsetDateTime> timestamps = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Files.writeString(TIMESTAMPS, xstream.toXML(timestamps));
		
		Map<String, Integer> hashcodes = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				pc -> pc.getText().hashCode()
			));
		
		Files.writeString(HASHCODES, xstream.toXML(hashcodes));
		
		if (sectionName != null || fieldType != null) {
			Map<String, String> filteredMap;
			
			var stream = pages.stream()
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream());
			
			if (sectionName != null) {
				stream = stream.filter(s -> s.getLangShort().equals(sectionName));
			}
			
			if (fieldType != null) {
				filteredMap = stream
					.flatMap(s -> s.getField(fieldType).stream())
					.filter(f -> !f.isEmpty())
					.collect(Collectors.toMap(
						f -> String.format("%s # %s",
							f.getContainingSection().get().getContainingPage().get().getTitle(),
							f.getContainingSection().get().getLangShort()),
						Field::getContent,
						(a, b) -> a,
						LinkedHashMap::new
					));
			} else {
				filteredMap = stream.collect(Collectors.toMap(
					s -> String.format("%s # %s",
						s.getContainingPage().get().getTitle(),
						s.getLangShort()),
					Section::toString,
					(a, b) -> a,
					LinkedHashMap::new
				));
			}
			
			String fieldName = Optional.ofNullable(fieldType).map(FieldTypes::localised).orElse(null);
			String filename = String.format(WORKLIST_FILTERED_FORMAT, sectionName, fieldName);
			Files.writeString(LOCATION.resolve(filename), Misc.makeList(filteredMap));
		}
	}
	
	public static void applyChanges(String sectionName, FieldTypes fieldType, String summary, boolean minor) throws IOException {
		Map<String, String> map = Misc.readList(Files.readAllLines(WORKLIST).toArray(String[]::new));
		final var initialSize = map.size();
		System.out.printf("Size: %d%n", initialSize);
		
		if (sectionName != null || fieldType != null) {
			String fieldName = Optional.ofNullable(fieldType).map(FieldTypes::localised).orElse(null);
			String filename = String.format(WORKLIST_FILTERED_FORMAT, sectionName, fieldName);
			Map<String, String> filteredMap = Misc.readList(Files.readAllLines(LOCATION.resolve(filename)).toArray(String[]::new));
			
			filteredMap.entrySet().forEach(e -> map.computeIfPresent(
				e.getKey().substring(0,  e.getKey().indexOf('#') - 1),
				(title, oldText) -> {
					var page = Page.store(title, oldText);
					var langShort = e.getKey().substring(e.getKey().indexOf('#') + 2);
					var section = page.getSection(langShort, true).get();
					
					if (fieldType != null) {
						var field = section.getField(fieldType).get();
						field.editContent(e.getValue());
					} else {
						var newSection = Section.parse(e.getValue());
						section.replaceWith(newSection);
					}
					
					return page.toString();
				}
			));
		}
		
		@SuppressWarnings("unchecked")
		var timestamps = (Map<String, OffsetDateTime>) xstream.fromXML(Files.readString(TIMESTAMPS));
		
		@SuppressWarnings("unchecked")
		var hashcodes = (Map<String, Integer>) xstream.fromXML(Files.readString(HASHCODES));
		map.entrySet().removeIf(e -> e.getValue().hashCode() == hashcodes.get(e.getKey()));
		
		if (map.size() != initialSize) {
			System.out.printf("Revised size: %d%n", map.size());
		}
		
		if (map.isEmpty()) {
			return;
		}
		
		List<String> errors = new ArrayList<>();
		
		for (var entry : map.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			
			try {
				wb.edit(title, text, summary, minor, true, -2, timestamps.get(title));
			} catch (Throwable t) {
    			t.printStackTrace();
    			errors.add(title);
    		}
		}
		
		System.out.printf("Edited: %d%n", map.size() - errors.size());
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errors: %s%n", errors.size(), errors.toString());
		}
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
		
		if (sectionName != null || fieldType != null) {
			String fieldName = Optional.ofNullable(fieldType).map(FieldTypes::localised).orElse(null);
			String oldFilename = String.format(WORKLIST_FILTERED_FORMAT, sectionName, fieldName);
			String newFilename = String.format("done-%s-%s.txt", sectionName, fieldName);
			Files.move(WORKLIST.resolveSibling(oldFilename), WORKLIST.resolveSibling(newFilename), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}