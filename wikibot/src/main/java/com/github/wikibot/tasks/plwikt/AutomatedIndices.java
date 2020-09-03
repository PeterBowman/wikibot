package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Transliterator;
import com.ibm.icu.util.ULocale;

public final class AutomatedIndices {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/AutomatedIndices/");
	private static final String WORKLIST = "Wikisłownikarz:Beau.bot/indeksy/lista";
	private static final String TEMPLATE = "Wikisłownikarz:Beau.bot/indeksy/szablon";
	
	private static final List<String> MAINTAINERS = List.of("Peter Bowman");
	private static final String ERROR_REPORT_TEMPLATE_FMT = "{{re|%s}}";
	
	private static final ULocale POLISH_LOCALE = new ULocale("pl");
	
	// https://stackoverflow.com/a/13071166/10404307
	private static final Transliterator DIACRITIC_TRANSLITERATOR = Transliterator.getInstance("NFD; [:M:] Remove; NFC");
	
	private static final Map<String, String> languageToIcuCode = Map.of("łaciński", "la", "nowogrecki", "el");
	
	private static List<String> errors = new ArrayList<>();
	
	private static Wikibot wb;

	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");
		
		String pageText = wb.getPageText(List.of(WORKLIST)).get(0);
		List<String> templates = ParseUtils.getTemplatesIgnoreCase(TEMPLATE, pageText);
		
		Set<Entry> entries = templates.stream()
			.map(Entry::parseTemplate)
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(HashSet::new));
		
		entries.forEach(System.out::println);
		
		if (entries.size() != templates.size()) {
			errors.add(String.format("Entry list has duplicates: %d entries, %d templates", entries.size(), templates.size()));
		}
		
		validateEntries(entries);
		
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
		
		System.out.println(langToLocale);
		
		XMLDumpReader reader = getDumpReader(args);
		int stats = wb.getSiteStatistics().get("pages");
		ConcurrentMap<String, List<String>> indexToTitles = new ConcurrentHashMap<>();
		ConcurrentMap<String, String> indexToLang = new ConcurrentHashMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.peek(AutomatedIndices::normalizeLangName)
				.filter(s -> langToEntries.containsKey(s.getLang()))
				.flatMap(s -> s.getField(FieldTypes.DEFINITIONS).stream())
				.forEach(f -> processDefinitionsField(f, langToEntries, indexToTitles, indexToLang));
		}
		
		indexToTitles.entrySet().parallelStream()
			.forEach(e -> Collections.sort(
				e.getValue(),
				Collator.getInstance(langToLocale.get(indexToLang.get(e.getKey())))
			));
		
		Path hash = LOCATION.resolve("hash.ser");
		Map<String, Integer> indexToHash;
		
		try {
			indexToHash = Misc.deserialize(hash);
		} catch (Exception e1) {
			indexToHash = new HashMap<>();
		}
		
		final String summary = String.format("aktualizacja na podstawie zrzutu z bazy danych: %s", reader.getFile().getName());
		
		wb.setMarkBot(true);
		wb.setMarkMinor(false);
		wb.setThrottle(1000);
		
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
		
		Misc.serialize(indexToHash, hash);
		
		if (!errors.isEmpty()) {
			String talkPage = wb.getTalkPage(WORKLIST);
			String text = errors.stream().map(err -> String.format("# %s", err)).collect(Collectors.joining("\n"));
			text = String.format(ERROR_REPORT_TEMPLATE_FMT, String.join("|", MAINTAINERS)) + ":\n" + text + "\n~~~~";
			wb.newSection(talkPage, reader.getFile().getName(), text, false, false);
		}
	}
	
	private static void validateEntries(Set<Entry> entries) throws IOException {
		List<String> languageTemplates = entries.stream()
			.flatMap(e -> e.languageTemplates.stream())
			.distinct()
			.map(t -> String.format("Szablon:%s", t))
			.collect(Collectors.toList());
		
		boolean[] existLanguageTemplates = wb.exists(languageTemplates);
		Set<String> missingLanguageTemplates = new HashSet<>();
		
		for (int i = 0; i < languageTemplates.size(); i++) {
			if (!existLanguageTemplates[i]) {
				errors.add(languageTemplates.get(i) + " does not exist");
				missingLanguageTemplates.add(languageTemplates.get(i));
			}
		}
		
		entries.stream().map(e -> e.languageTemplates).forEach(list -> list.removeIf(missingLanguageTemplates::contains));
		
		List<String> defTemplates = entries.stream()
			.flatMap(e -> e.templates.stream())
			.distinct()
			.map(t -> String.format("Szablon:%s", t))
			.collect(Collectors.toList());
		
		boolean[] existDefTemplates = wb.exists(defTemplates);
		Set<String> missingDefTemplates = new HashSet<>();
		
		for (int i = 0; i < defTemplates.size(); i++) {
			if (!existDefTemplates[i]) {
				errors.add(defTemplates.get(i) + " does not exist");
				missingDefTemplates.add(defTemplates.get(i));
			}
		}
		
		entries.stream().map(e -> e.templates).forEach(list -> list.removeIf(missingDefTemplates::contains));
		
		List<String> categories = entries.stream()
			.flatMap(e -> e.categories.stream())
			.distinct()
			.map(c -> String.format("Kategoria:%s", c))
			.collect(Collectors.toList());
		
		if (!categories.isEmpty()) {
			boolean[] existCategories = wb.exists(categories);
			Set<String> missingCategories = new HashSet<>();
			
			for (int i = 0; i < categories.size(); i++) {
				if (!existCategories[i]) {
					errors.add(categories.get(i) + " does not exist");
					missingCategories.add(categories.get(i));
				}
			}
			
			entries.stream().map(e -> e.categories).forEach(list -> list.removeIf(missingCategories::contains));
		}
		
		entries.removeIf(e -> e.languageTemplates.isEmpty() || e.templates.isEmpty() || e.categories.isEmpty());
	}
	
	private static XMLDumpReader getDumpReader(String[] args) throws FileNotFoundException {
		if (args.length == 0) {
			return new XMLDumpReader("plwiktionary");
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
	
	private static void normalizeLangName(Section s) {
		if (s.getLang().equals("termin obcy w języku polskim")) {
			s.setLang("język polski");
		}
	}
	
	private static void processDefinitionsField(Field f, Map<String, List<Entry>> langToEntries, ConcurrentMap<String, List<String>> indexToTitles,
			ConcurrentMap<String, String> indexToLang) {
		String lang = f.getContainingSection().get().getLang();
		String title = f.getContainingSection().get().getContainingPage().get().getTitle();
		
		for (Entry entry : langToEntries.get(lang)) {
			if (entry.templates.stream().anyMatch(template -> !ParseUtils.getTemplates(template, f.getContent()).isEmpty())) {
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
	
	private static String makeIndexText(String index, List<String> titles, ULocale locale, Set<Entry> entries) {
		final String separator = " - ";
		String langUpper = index.substring(0, index.indexOf(separator));
		String langLower = StringUtils.uncapitalize(langUpper);
		String indexType = index.substring(index.indexOf(separator) + separator.length());
		
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("{{język linków|%s}}", langLower)).append("{{TOCright}}").append("\n\n");
		
		titles.stream()
			.collect(Collectors.groupingBy(
				title -> String.valueOf(title.charAt(0)),
				// sort first letter ignoring case and diacritics, then reverse natural order ('A' before 'a')
				() -> new TreeMap<>(makeComparator(locale)),
				Collectors.mapping(
					title -> String.format("[[%s]]", title),
					Collectors.joining(" • ")
				)
			))
			.entrySet().stream()
			.map(e -> String.format("=== %s ===\n%s", removeDiacriticMarks(e.getKey(), locale), e.getValue()))
			.forEach(section -> sb.append(section).append("\n\n"));
		
		sb.append("[[Kategoria:Słowniki tworzone automatycznie]]").append("\n");
		sb.append(String.format("[[Kategoria:%s (słowniki tematyczne)|%s]]", langUpper, indexType)).append("\n");
		
		entries.stream()
			.filter(entry -> entry.indexName.equals(StringUtils.uncapitalize(indexType)))
			.flatMap(entry -> entry.categories.stream())
			.forEach(category -> sb.append(String.format("[[Kategoria:%s]]", category)).append("\n"));
		
		return sb.toString().trim();
	}
	
	private static Comparator<Object> makeComparator(ULocale locale) {
		final Collator collPrimary = Collator.getInstance(locale);
		collPrimary.setStrength(Collator.PRIMARY);
		
		final Collator collSecondary = Collator.getInstance(locale);
		collSecondary.setStrength(Collator.SECONDARY);
		
		final Collator collTertiary = Collator.getInstance(locale);
		collTertiary.setStrength(Collator.TERTIARY);
		
		return collPrimary.thenComparing((o1, o2) -> {
			if (o1.equals(o2)) {
				return 0;
			}
			
			String s1 = (String) o1;
			String s2 = (String) o2;
			
			int secondaryCompare = collSecondary.compare(s1, s2);
			
			if (secondaryCompare == 0) {
				// same diacritic mark (or lack of), but different case
				return -collTertiary.compare(s1, s2);
			} else {
				boolean isLower1 = UCharacter.isLowerCase(s1.codePointAt(0));
				boolean isLower2 = UCharacter.isLowerCase(s2.codePointAt(0));
				
				if (!(isLower1 ^ isLower2)) {
					// both are lower or upper case (XNOR)
					return 0;
				} else {
					return -collTertiary.compare(s1, s2);
				}
			}
		});
	}
	
	private static String removeDiacriticMarks(String s, ULocale locale) {
		Collator coll = Collator.getInstance(locale);
		coll.setStrength(Collator.PRIMARY);
		String stripped = DIACRITIC_TRANSLITERATOR.transliterate(s);
		return coll.compare(s, stripped) == 0 ? stripped : s;
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
			entry.templates = makeList(SEP.splitAsStream(params.getOrDefault("szablony tematyczne", "")));
			entry.languageTemplates = makeList(SEP.splitAsStream(params.getOrDefault("szablony języków", "")));
			entry.categories = makeList(SEP.splitAsStream(params.getOrDefault("kategorie", "")));
			
			if (entry.indexName.isEmpty() || entry.templates.isEmpty() || entry.languageTemplates.isEmpty()) {
				errors.add("Illegal parameters to template " + template.replace("\n", ""));
				return null;
			}
			
			return entry;
		}
		
		private static List<String> makeList(Stream<String> stream) {
			return stream.map(String::trim).filter(s -> !s.isEmpty()).distinct().collect(Collectors.toList());
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
		
		@Override
		public int hashCode() {
			return indexName.hashCode();
		}
		
		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			
			if (!(obj instanceof Entry)) {
				return false;
			}
			
			return indexName.equals(((Entry) obj).indexName);
		}
	}
}
