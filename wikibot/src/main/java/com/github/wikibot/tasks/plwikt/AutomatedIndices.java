package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Transliterator;
import com.ibm.icu.util.ULocale;
import com.thoughtworks.xstream.XStream;

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

    private static final List<String> errors = new ArrayList<>();

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var pageText = wb.getPageText(List.of(WORKLIST)).get(0);
        var templates = ParseUtils.getTemplatesIgnoreCase(TEMPLATE, pageText);

        var entries = templates.stream()
            .map(Entry::parseTemplate)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));

        entries.forEach(System.out::println);

        if (entries.size() != templates.size()) {
            errors.add(String.format("Entry list has duplicates: %d entries, %d templates", entries.size(), templates.size()));
        }

        validateEntries(entries);

        var langToEntries = entries.stream()
            .flatMap(entry -> entry.languageTemplates.stream())
            .distinct()
            .collect(Collectors.toMap(
                UnaryOperator.identity(),
                lang -> entries.stream().filter(e -> e.languageTemplates().contains(lang)).toList()
            ));

        var langToLocale = langToEntries.keySet().stream()
            .map(AutomatedIndices::stripLanguagePrefix)
            .collect(Collectors.toMap(
                UnaryOperator.identity(),
                AutomatedIndices::getLocale
            ));

        System.out.println(langToLocale);

        var dump = getDump(args);
        var indexToTitles = new HashMap<String, List<String>>();
        var indexToLang = new HashMap<String, String>();

        try (var stream = dump.stream()) {
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

        indexToTitles.entrySet().stream()
            .forEach(e -> Collections.sort(
                e.getValue(),
                Collator.getInstance(langToLocale.get(indexToLang.get(e.getKey())))
            ));

        var hash = LOCATION.resolve("hash.xml");

        @SuppressWarnings("unchecked")
        var indexToHash = Files.exists(hash) ? (Map<String, Integer>) new XStream().fromXML(hash.toFile()) : new HashMap<String, Integer>();

        final var summary = String.format("aktualizacja na podstawie zrzutu z bazy danych: %s", dump.getDescriptiveFilename());

        for (var e : indexToTitles.entrySet()) {
            var index = e.getKey();
            var titles = e.getValue();
            var newHash = titles.hashCode();

            if (!indexToHash.containsKey(index) || indexToHash.get(index) != newHash) {
                indexToHash.put(index, newHash);
                var lang = indexToLang.get(index);
                var text = makeIndexText(index, titles, langToLocale.get(lang), entries);
                wb.edit("Indeks:" + index, text, summary);
            }
        }

        Files.writeString(hash, new XStream().toXML(indexToHash));

        if (!errors.isEmpty()) {
            var talkPage = wb.getTalkPage(WORKLIST);
            var text = errors.stream().map(err -> String.format("# %s", err)).collect(Collectors.joining("\n"));
            text = String.format(ERROR_REPORT_TEMPLATE_FMT, String.join("|", MAINTAINERS)) + ":\n" + text + "\n~~~~";
            wb.newSection(talkPage, dump.getDescriptiveFilename(), text, false, false);
        }
    }

    private static void validateEntries(Set<Entry> entries) throws IOException {
        var languageTemplates = entries.stream()
            .flatMap(e -> e.languageTemplates().stream())
            .distinct()
            .map(t -> String.format("Szablon:%s", t))
            .toList();

        var existLanguageTemplates = wb.exists(languageTemplates);
        var missingLanguageTemplates = new HashSet<String>();

        for (var i = 0; i < languageTemplates.size(); i++) {
            if (!existLanguageTemplates[i]) {
                errors.add(languageTemplates.get(i) + " does not exist");
                missingLanguageTemplates.add(languageTemplates.get(i));
            }
        }

        entries.stream().map(Entry::languageTemplates).forEach(list -> list.removeIf(missingLanguageTemplates::contains));

        var defTemplates = entries.stream()
            .flatMap(e -> e.templates().stream())
            .distinct()
            .map(t -> String.format("Szablon:%s", t))
            .toList();

        var existDefTemplates = wb.exists(defTemplates);
        var missingDefTemplates = new HashSet<String>();

        for (var i = 0; i < defTemplates.size(); i++) {
            if (!existDefTemplates[i]) {
                errors.add(defTemplates.get(i) + " does not exist");
                missingDefTemplates.add(defTemplates.get(i));
            }
        }

        entries.stream().map(Entry::templates).forEach(list -> list.removeIf(missingDefTemplates::contains));

        var categories = entries.stream()
            .flatMap(e -> e.categories().stream())
            .distinct()
            .map(c -> String.format("Kategoria:%s", c))
            .toList();

        if (!categories.isEmpty()) {
            var existCategories = wb.exists(categories);
            var missingCategories = new HashSet<String>();

            for (var i = 0; i < categories.size(); i++) {
                if (!existCategories[i]) {
                    errors.add(categories.get(i) + " does not exist");
                    missingCategories.add(categories.get(i));
                }
            }

            entries.stream().map(Entry::categories).forEach(list -> list.removeIf(missingCategories::contains));
        }

        entries.removeIf(e -> e.languageTemplates().isEmpty() || e.templates().isEmpty());
    }

    private static XMLDump getDump(String[] args) {
        var dumpConfig = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES);

        if (args.length == 0) {
            return dumpConfig.remote().fetch().get();
        } else {
            return dumpConfig.local().fetch().get();
        }
    }

    private static String stripLanguagePrefix(String lang) {
        return lang.replace("język ", "");
    }

    private static ULocale getLocale(String lang) {
        var code = Stream.of(ULocale.getAvailableLocales())
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

    private static void processDefinitionsField(Field f, Map<String, List<Entry>> langToEntries, Map<String, List<String>> indexToTitles,
            Map<String, String> indexToLang) {
        var lang = f.getContainingSection().get().getLang();
        var title = f.getContainingSection().get().getContainingPage().get().getTitle();

        for (var entry : langToEntries.get(lang)) {
            if (entry.templates().stream().anyMatch(template -> !ParseUtils.getTemplates(template, f.getContent()).isEmpty())) {
                var shortLang = stripLanguagePrefix(lang);
                var index = String.format("%s - %s", StringUtils.capitalize(shortLang), StringUtils.capitalize(entry.indexName()));

                indexToTitles.computeIfAbsent(index, k -> new ArrayList<>()).add(title);
                indexToLang.putIfAbsent(index, shortLang);
            }
        }
    }

    private static String makeIndexText(String index, List<String> titles, ULocale locale, Set<Entry> entries) {
        final var separator = " - ";
        var langUpper = index.substring(0, index.indexOf(separator));
        var langLower = StringUtils.uncapitalize(langUpper);
        var indexType = index.substring(index.indexOf(separator) + separator.length());

        var sb = new StringBuilder();
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
            .filter(entry -> entry.indexName().equals(StringUtils.uncapitalize(indexType)))
            .flatMap(entry -> entry.categories().stream())
            .forEach(category -> sb.append(String.format("[[Kategoria:%s]]", category)).append("\n"));

        return sb.toString().trim();
    }

    private static Comparator<Object> makeComparator(ULocale locale) {
        final var collPrimary = Collator.getInstance(locale);
        collPrimary.setStrength(Collator.PRIMARY);

        final var collSecondary = Collator.getInstance(locale);
        collSecondary.setStrength(Collator.SECONDARY);

        final var collTertiary = Collator.getInstance(locale);
        collTertiary.setStrength(Collator.TERTIARY);

        return collPrimary.thenComparing((o1, o2) -> {
            if (o1.equals(o2)) {
                return 0;
            }

            var s1 = (String)o1;
            var s2 = (String)o2;

            var secondaryCompare = collSecondary.compare(s1, s2);

            if (secondaryCompare == 0) {
                // same diacritic mark (or lack of), but different case
                return -collTertiary.compare(s1, s2);
            } else {
                var isLower1 = UCharacter.isLowerCase(s1.codePointAt(0));
                var isLower2 = UCharacter.isLowerCase(s2.codePointAt(0));

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
        var coll = Collator.getInstance(locale);
        coll.setStrength(Collator.PRIMARY);
        var stripped = DIACRITIC_TRANSLITERATOR.transliterate(s);
        return coll.compare(s, stripped) == 0 ? stripped : s;
    }

    private record Entry (String indexName, List<String> templates, List<String> languageTemplates, List<String> categories) {
        private static final Pattern SEP = Pattern.compile(",");

        public static Entry parseTemplate(String template) {
            var params = ParseUtils.getTemplateParametersWithValue(template);

            var indexName = params.getOrDefault("nazwa indeksu", "");
            var templates = makeList(SEP.splitAsStream(params.getOrDefault("szablony tematyczne", "")));
            var languageTemplates = makeList(SEP.splitAsStream(params.getOrDefault("szablony języków", "")));
            var categories = makeList(SEP.splitAsStream(params.getOrDefault("kategorie", "")));

            if (indexName.isEmpty() || templates.isEmpty() || languageTemplates.isEmpty()) {
                errors.add("Illegal parameters to template " + template.replace("\n", ""));
                return null;
            }

            return new Entry(indexName, templates, languageTemplates, categories);
        }

        private static List<String> makeList(Stream<String> stream) {
            return stream.map(String::trim).filter(s -> !s.isEmpty()).distinct().collect(Collectors.toCollection(ArrayList::new));
        }

        @Override
        public int hashCode() {
            return indexName.hashCode();
        }
    }
}
