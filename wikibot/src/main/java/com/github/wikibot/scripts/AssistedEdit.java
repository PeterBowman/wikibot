package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.AccountLockedException;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class AssistedEdit {
	private static final Path LOCATION = Paths.get("./data/scripts/AssistedEdit/");
	private static final Path TITLES = LOCATION.resolve("titles.txt");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path DONELIST = LOCATION.resolve("done.txt");
	private static final Path TIMESTAMPS = LOCATION.resolve("timestamps.xml");
	private static final Path HASHCODES = LOCATION.resolve("hash.xml");
	private static final String WORKLIST_FILTERED_FORMAT = "worklist-%s.txt";
	private static final String DONELIST_FILTERED_FORMAT = "done-%s.txt";
	
	private static XStream xstream = new XStream();
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		var options = new Options();
		options.addRequiredOption("o", "operation", true, "mode of operation: query|apply|edit");
		options.addRequiredOption("d", "domain", true, "wiki domain name");
		options.addOption("s", "summary", true, "edit summary");
		options.addOption("m", "minor", false, "mark edits as minor");
		options.addOption("t", "throttle", true, "set edit throttle [ms]");
		options.addOption("x", "section", true, "section name");
		options.addOption("l", "language", true, "language section short name (only pl.wiktionary.org)");
		options.addOption("f", "field", true, "field type (only pl.wiktionary.org)");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		var parser = new DefaultParser();
		var line = parser.parse(options, args);
		var domain = line.getOptionValue("domain");
		
		var handler = switch (domain) {
			case "pl.wiktionary.org" -> new PlwiktFragmentHandler(line.getOptionValue("language"), line.getOptionValue("field"));
			default -> new GenericFragmentHandler(line.getOptionValue("section"));
		};
		
		wb = Login.createSession(domain);
		wb.setThrottle(Integer.parseInt(line.getOptionValue("throttle", "5000")));
		
		switch (line.getOptionValue("operation")) {
			case "query" -> getContents(handler);
			case "apply" -> applyFragments(handler);
			case "edit" -> editEntries(handler, line.getOptionValue("summary"), line.hasOption("minor"));
			default -> {
				new HelpFormatter().printHelp(AssistedEdit.class.getName(), options);
				throw new IllegalArgumentException();
			}
		}
	}
	
	private static void getContents(FragmentHandler handler) throws IOException {
		var titles = Files.readAllLines(TITLES).stream()
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.distinct()
			.toList();
		
		System.out.printf("Size: %d%n", titles.size());
		
		if (titles.isEmpty()) {
			return;
		}

		var pages = wb.getContentOfPages(titles);
		
		var map = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getText,
				(a, b) -> a,
				LinkedHashMap::new
			));
		
		Files.write(WORKLIST, List.of(Misc.makeList(map)));
		
		var timestamps = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Files.writeString(TIMESTAMPS, xstream.toXML(timestamps));
		
		var hashcodes = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				pc -> pc.getText().hashCode()
			));
		
		Files.writeString(HASHCODES, xstream.toXML(hashcodes));
		
		if (handler.isEnabled()) {
			var filteredMap = handler.extractFragments(pages);
			var filename = String.format(WORKLIST_FILTERED_FORMAT, handler.getFileFragment());
			Files.writeString(LOCATION.resolve(filename), Misc.makeList(filteredMap));
		}
	}
	
	private static void applyFragments(FragmentHandler handler) throws IOException {
		if (!handler.isEnabled()) {
			throw new IllegalArgumentException("Fragment parser not enabled.");
		}
		
		var map = Misc.readList(Files.readString(WORKLIST));
		System.out.printf("Size: %d%n", map.size());
		
		var filename = String.format(WORKLIST_FILTERED_FORMAT, handler.getFileFragment());
		var fragmentMap = Misc.readList(Files.readString(LOCATION.resolve(filename)));
		System.out.printf("Fragments: %d%n", fragmentMap.size());
		
		handler.applyFragments(map, fragmentMap);
	}
	
	private static void editEntries(FragmentHandler handler, String summary, boolean minor) throws IOException {
		var map = Misc.readList(Files.readString(WORKLIST));
		final var initialSize = map.size();
		System.out.printf("Size: %d%n", initialSize);
		
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
		
		var errors = new ArrayList<>();
		
		for (var entry : map.entrySet()) {
			var title = entry.getKey();
			var text = entry.getValue();
			
			try {
				wb.edit(title, text, summary, minor, true, -2, timestamps.get(title));
			} catch (Throwable t) {
    			t.printStackTrace();
    			errors.add(title);
    			
    			if (t instanceof AssertionError || t instanceof AccountLockedException) {
    				break;
    			}
    		}
		}
		
		System.out.printf("Edited: %d%n", map.size() - errors.size());
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errors: %s%n", errors.size(), errors.toString());
		}
		
		Files.move(WORKLIST, DONELIST, StandardCopyOption.REPLACE_EXISTING);
		
		if (handler.isEnabled()) {
			var oldFilename = String.format(WORKLIST_FILTERED_FORMAT, handler.getFileFragment());
			var newFilename = String.format(DONELIST_FILTERED_FORMAT, handler.getFileFragment());
			Files.move(WORKLIST.resolveSibling(oldFilename), WORKLIST.resolveSibling(newFilename), StandardCopyOption.REPLACE_EXISTING);
		}
	}
	
	private static interface FragmentHandler {
		Map<String, String> extractFragments(List<PageContainer> pages);
		void applyFragments(Map<String, String> map, Map<String, String> fragmentMap);
		String getFileFragment();
		boolean isEnabled();
	}
	
	private static class GenericFragmentHandler implements FragmentHandler {
		private String sectionName = null;
		
		public GenericFragmentHandler(String sectionName) {
			this.sectionName = sectionName;
		}
		
		@Override
		public Map<String, String> extractFragments(List<PageContainer> pages) {
			if (!isEnabled()) {
				return Collections.emptyMap();
			}
			
			return pages.stream()
				.map(com.github.wikibot.parsing.Page::wrap)
				.map(p -> p.findSectionsWithHeader(sectionName))
				.filter(sections -> !sections.isEmpty())
				.map(sections -> sections.get(0))
				.collect(Collectors.toMap(
					s -> s.getContainingPage().get().getTitle(),
					s -> s.toString(),
					(a, b) -> a,
					LinkedHashMap::new
				));
		}
		
		@Override
		public void applyFragments(Map<String, String> map, Map<String, String> fragmentMap) {
			if (isEnabled()) {
				fragmentMap.entrySet().forEach(e -> map.computeIfPresent(e.getKey(), (title, oldText) -> {
					var page = com.github.wikibot.parsing.Page.store(title, oldText);
					var section = page.findSectionsWithHeader(e.getKey()).get(0);
					var newSection = com.github.wikibot.parsing.Section.parse(e.getValue());
					section.replaceWith(newSection);
					return page.toString();
				}));
			}
		}
		
		@Override
		public String getFileFragment() {
			return sectionName;
		}
		
		@Override
		public boolean isEnabled() {
			return sectionName != null;
		}
	}
	
	private static class PlwiktFragmentHandler implements FragmentHandler {
		private String sectionName = null;
		private FieldTypes fieldType = null;
		
		public PlwiktFragmentHandler(String sectionName, String fieldName) {
			this.sectionName = sectionName;
			
			if (fieldName != null) {
				fieldType =  Stream.of(FieldTypes.values())
					.filter(type -> type.localised().equals(fieldName))
					.findAny()
					.orElseThrow(() -> new IllegalArgumentException("Unsupported field name: " + fieldName));
			}
		}
		
		@Override
		public Map<String, String> extractFragments(List<PageContainer> pages) {
			if (!isEnabled()) {
				return Collections.emptyMap();
			}
			
			var stream = pages.stream()
				.map(com.github.wikibot.parsing.plwikt.Page::wrap)
				.flatMap(p -> p.getAllSections().stream());
			
			if (sectionName != null) {
				stream = stream.filter(s -> s.getLangShort().equals(sectionName));
			}
			
			if (fieldType != null) {
				return stream
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
				return stream.collect(Collectors.toMap(
					s -> String.format("%s # %s",
						s.getContainingPage().get().getTitle(),
						s.getLangShort()),
					s -> s.toString(),
					(a, b) -> a,
					LinkedHashMap::new
				));
			}
		}
		
		@Override
		public void applyFragments(Map<String, String> map, Map<String, String> fragmentMap) {
			if (isEnabled()) {
				fragmentMap.entrySet().forEach(e -> map.computeIfPresent(
					e.getKey().substring(0,  e.getKey().indexOf('#') - 1),
					(title, oldText) -> {
						var page = com.github.wikibot.parsing.plwikt.Page.store(title, oldText);
						var langShort = e.getKey().substring(e.getKey().indexOf('#') + 2);
						var section = page.getSection(langShort, true).get();
						
						if (fieldType != null) {
							var field = section.getField(fieldType).get();
							field.editContent(e.getValue());
						} else {
							var newSection = com.github.wikibot.parsing.plwikt.Section.parse(e.getValue());
							section.replaceWith(newSection);
						}
						
						return page.toString();
					}
				));
			}
		}
		
		@Override
		public String getFileFragment() {
			return Optional.ofNullable(fieldType)
				.map(FieldTypes::localised)
				.map(fieldName -> String.format("%s-%s", sectionName, fieldName))
				.orElse(sectionName);
		}
		
		@Override
		public boolean isEnabled() {
			return sectionName != null || fieldType != null;
		}
	}
}