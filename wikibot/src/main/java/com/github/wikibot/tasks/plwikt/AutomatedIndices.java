package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public final class AutomatedIndices {
	private static final String LOCATION = "./data/tasks.plwikt/AutomatedIndices/";
	private static final String WORKLIST = "Wikisłownikarz:Beau.bot/indeksy/lista";
	private static final String TEMPLATE = "Wikisłownikarz:Beau.bot/indeksy/szablon";
	
	private static Wikibot wb;

	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");
		
		String pageText = wb.getPageText(WORKLIST);
		
		List<Entry> entries = ParseUtils.getTemplatesIgnoreCase(TEMPLATE, pageText).stream()
			.map(Entry::parseTemplate)
			.collect(Collectors.toList());
		
		entries.forEach(System.out::println);
		
		Map<String, List<Entry>> langToEntries = entries.stream()
			.flatMap(entry -> entry.languageTemplates.stream())
			.distinct()
			.collect(Collectors.toMap(
				lang -> lang,
				lang -> entries.stream()
					.filter(e -> e.languageTemplates.contains(lang))
					.collect(Collectors.toList())
			));
		
		XMLDumpReader reader = getDumpReader(args);
		int stats = wb.getSiteStatistics().get("pages");
		ConcurrentMap<String, List<String>> map = new ConcurrentSkipListMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.filter(s -> langToEntries.containsKey(s.getLang()))
				.flatMap(s -> Utils.streamOpt(s.getField(FieldTypes.DEFINITIONS)))
				.forEach(f -> processDefinitionsField(f, langToEntries, map));
		}
		
		map.values().forEach(Collections::sort);
	}
	
	private static XMLDumpReader getDumpReader(String[] args) throws FileNotFoundException {
		if (args.length == 0) {
			return new XMLDumpReader("pl.wiktionary.org");
		} else {
			return new XMLDumpReader(new File(args[0].trim()));
		}
	}
	
	private static void processDefinitionsField(Field f, Map<String, List<Entry>> langToEntries, ConcurrentMap<String, List<String>> map) {
		String lang = f.getContainingSection().get().getLang();
		String title = f.getContainingSection().get().getContainingPage().get().getTitle();
		String text = f.getContent();
		
		for (Entry entry : langToEntries.get(lang)) {
			if (entry.templates.stream().anyMatch(template -> !ParseUtils.getTemplates(template, text).isEmpty())) {
				String shortLang = lang.replace("język ", "");
				String index = String.format("%s - %s", StringUtils.capitalize(shortLang), StringUtils.capitalize(entry.indexName));
				
				map.compute(index, (key, value) -> {
					if (value == null) {
						value = new ArrayList<>();
					}
					
					value.add(title);
					return value;
				});
			}
		}
	}
	
	private static final class Entry {
		String indexName;
		List<String> templates;
		List<String> languageTemplates;
		List<String> categories;
		
		private static final Pattern SEP = Pattern.compile(",");
		
		static Entry parseTemplate(String template) {
			Map<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
			
			Entry entry = new Entry();
			entry.indexName = params.getOrDefault("nazwa indeksu", "");
			entry.templates = SEP.splitAsStream(params.getOrDefault("szablony tematyczne", "")).map(String::trim).collect(Collectors.toList());
			entry.languageTemplates = SEP.splitAsStream(params.getOrDefault("szablony języków", "")).map(String::trim).collect(Collectors.toList());
			entry.categories = SEP.splitAsStream(params.getOrDefault("kategorie", "")).map(String::trim).collect(Collectors.toList());
			
			entry.validate();
			
			if (entry.indexName.isEmpty() || entry.templates.isEmpty() || entry.languageTemplates.isEmpty()) {
				throw new IllegalArgumentException("Unable to parse template " + template);
			}
			
			return entry;
		}
		
		private void validate() {
			templates.removeIf(String::isEmpty);
			languageTemplates.removeIf(String::isEmpty);
			categories.removeIf(String::isEmpty);
		}
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("[indexName=").append(indexName);
			sb.append(",templates=").append(templates);
			sb.append(",languageTemplates=").append(languageTemplates);
			sb.append(",categories=").append(categories);
			sb.append("]");
			return sb.toString();
		}
	}
}
