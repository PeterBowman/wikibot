package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.ibm.icu.text.Collator;
import com.ibm.icu.util.ULocale;

public final class AutomatedIndices {
	private static final String LOCATION = "./data/tasks.plwikt/AutomatedIndices/";
	private static final String WORKLIST = "Wikisłownikarz:Beau.bot/indeksy/lista";
	private static final String TEMPLATE = "Wikisłownikarz:Beau.bot/indeksy/szablon";
	
	private static final ULocale POLISH_LOCALE;
	
	private static Map<String, String> languageToIcuCode;
	
	private static Wikibot wb;
	
	static {
		POLISH_LOCALE = new ULocale("pl");
		
		languageToIcuCode = new HashMap<>();
		languageToIcuCode.put("łaciński", "la");
		languageToIcuCode.put("nowogrecki", "el");
	}

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
		
		Map<String, ULocale> langToLocale = langToEntries.keySet().stream()
			.map(AutomatedIndices::stripLanguagePrefix)
			.collect(Collectors.toMap(
				lang -> lang,
				AutomatedIndices::getLocale
			));
		
		XMLDumpReader reader = getDumpReader(args);
		int stats = wb.getSiteStatistics().get("pages");
		ConcurrentMap<String, List<String>> indexToTitles = new ConcurrentSkipListMap<>();
		ConcurrentMap<String, String> indexToLang = new ConcurrentHashMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.map(AutomatedIndices::normalizeLangName)
				.filter(s -> langToEntries.containsKey(s.getLang()))
				.flatMap(s -> Utils.streamOpt(s.getField(FieldTypes.DEFINITIONS)))
				.forEach(f -> processDefinitionsField(f, langToEntries, indexToTitles, indexToLang));
		}
		
		indexToTitles.entrySet().parallelStream()
			.forEach(e -> Collections.sort(
				e.getValue(),
				Collator.getInstance(langToLocale.get(indexToLang.get(e.getKey())))
			));
		
		File fHash = new File(LOCATION + "hash.ser");
		Map<String, Integer> indexToHash;
		
		try {
			indexToHash = Misc.deserialize(fHash);
		} catch (Exception e1) {
			indexToHash = new HashMap<>();
		}
		
		final String summary = String.format("aktualizacja na podstawie zrzutu z bazy danych: %s", reader.getFile().getName());
		
		for (Map.Entry<String, List<String>> e : indexToTitles.entrySet()) {
			String index = e.getKey();
			List<String> titles = e.getValue();
			int newHash = titles.hashCode();
			
			if (!indexToHash.containsKey(index) || indexToHash.get(index) != newHash) {
				indexToHash.put(index, newHash);
				String lang = indexToLang.get(index);
				String text = makeIndexText(index, titles, langToLocale.get(lang), entries);
				wb.edit("Indeks:" + index, text, summary);
			}
		}
		
		Misc.serialize(indexToHash, fHash);
	}
	
	private static XMLDumpReader getDumpReader(String[] args) throws FileNotFoundException {
		if (args.length == 0) {
			return new XMLDumpReader("pl.wiktionary.org");
		} else {
			return new XMLDumpReader(new File(args[0].trim()));
		}
	}
	
	private static String stripLanguagePrefix(String lang) {
		return lang.replace("język ", "");
	}
	
	private static ULocale getLocale(String lang) {
		String code = Stream.of(ULocale.getAvailableLocales())
			.filter(locale -> locale.getDisplayLanguage(POLISH_LOCALE).equals(lang))
			.map(ULocale::getISO3Language)
			.distinct()
			.findFirst()
			.orElse(languageToIcuCode.getOrDefault(lang, ""));
		
		return ULocale.createCanonical(code);
	}
	
	private static Section normalizeLangName(Section s) {
		if (s.getLang().equals("termin obcy w języku polskim")) {
			s.setLang("język polski");
		}
		
		return s;
	}
	
	private static void processDefinitionsField(Field f, Map<String, List<Entry>> langToEntries, ConcurrentMap<String, List<String>> indexToTitles,
			ConcurrentMap<String, String> indexToLang) {
		String lang = f.getContainingSection().get().getLang();
		String title = f.getContainingSection().get().getContainingPage().get().getTitle();
		String text = f.getContent();
		
		for (Entry entry : langToEntries.get(lang)) {
			if (entry.templates.stream().anyMatch(template -> !ParseUtils.getTemplates(template, text).isEmpty())) {
				String shortLang = stripLanguagePrefix(lang);
				String index = String.format("%s - %s", StringUtils.capitalize(shortLang), StringUtils.capitalize(entry.indexName));
				
				indexToTitles.compute(index, (key, value) -> {
					if (value == null) {
						value = new ArrayList<>();
					}
					
					value.add(title);
					return value;
				});
				
				indexToLang.putIfAbsent(index, shortLang);
			}
		}
	}
	
	private static String makeIndexText(String index, List<String> titles, ULocale locale, List<Entry> entries) {
		final String separator = " - ";
		String langUpper = index.substring(0, index.indexOf(separator));
		String langLower = StringUtils.uncapitalize(langUpper);
		String indexType = index.substring(index.indexOf(separator) + separator.length());
		
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("{{język linków|%s}}", langLower)).append("{{TOCright}}").append("\n");
		
		titles.stream()
			.collect(Collectors.groupingBy(
				title -> String.valueOf(title.charAt(0)),
				() -> new TreeMap<>(Collator.getInstance(locale)),
				Collectors.mapping(
					title -> String.format("[[%s]]", title),
					Collectors.joining(" • ")
				)
			))
			.entrySet().stream()
			.map(e -> String.format("=== %s ===\n%s", e.getKey(), e.getValue()))
			.forEach(section -> sb.append(section).append("\n\n"));
		
		sb.append("[[Kategoria:Słowniki tworzone automatycznie]]").append("\n");
		sb.append(String.format("[[Kategoria:%s (słowniki tematyczne)|%s]]", langUpper, indexType)).append("\n");
		
		entries.stream()
			.filter(entry -> entry.indexName.equals(StringUtils.uncapitalize(indexType)))
			.flatMap(entry -> entry.categories.stream())
			.forEach(category -> sb.append(String.format("[[Kategoria:%s]]", category)).append("\n"));
		
		return sb.toString().trim();
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
