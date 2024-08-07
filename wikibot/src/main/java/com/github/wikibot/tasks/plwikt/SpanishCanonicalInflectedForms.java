package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikipedia.Wiki;

import com.github.plural4j.Plural;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public final class SpanishCanonicalInflectedForms {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/SpanishCanonicalInflectedForms/");
    private static final String TARGET_PAGE = "Indeks:Hiszpańskie formy czasownikowe";
    private static final String CATEGORY_NAME = "Formy czasowników hiszpańskich";

    private static final Map<Character, Character> STRIPPED_ACCENTS_MAP = Map.of('á', 'a', 'é', 'e', 'í', 'i', 'ó', 'o', 'ú', 'u');

    private static final Plural PLURAL_PL = new Plural(PluralRules.POLISH, "hasło,hasła,haseł");

    private static final LocalizedNumberFormatter NUMBER_FORMAT_PL = NumberFormatter.withLocale(Locale.forLanguageTag("pl-PL"))
            .grouping(GroupingStrategy.MIN2);

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        CommandLine line = readOptions(args);
        List<String> list = retrieveList();

        if (!line.hasOption("edit") || !checkAndUpdateStoredData(list)) {
            System.out.println("No changes detected/read-only mode, aborting.");
            return;
        }

        String pageText = makePage(list);

        wb.setMarkBot(false);
        wb.edit(TARGET_PAGE, pageText, "aktualizacja");
    }

    private static CommandLine readOptions(String[] args) {
        Options options = new Options();
        options.addOption("e", "edit", false, "edit pages");

        if (args.length == 0) {
            System.out.print("Options (if any): ");
            String input = Misc.readLine();
            args = input.split(" ");
        }

        CommandLineParser parser = new DefaultParser();

        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            new HelpFormatter().printHelp(InconsistentHeaderTitles.class.getName(), options);
            throw new IllegalArgumentException();
        }
    }

    private static List<String> retrieveList() throws IOException {
        List<PageContainer> pages = wb.getContentOfCategorymembers(CATEGORY_NAME, Wiki.MAIN_NAMESPACE);

        Collator collator = Collator.getInstance(Locale.forLanguageTag("es-ES"));
        collator.setStrength(Collator.SECONDARY);

        List<String> titles = pages.stream()
            .map(Page::wrap)
            .flatMap(p -> p.getSection("hiszpański", true).stream())
            .flatMap(s -> s.getField(FieldTypes.DEFINITIONS).stream())
            .filter(SpanishCanonicalInflectedForms::matchNonInflectedDefinitions)
            .map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
            .sorted(collator)
            .toList();

        System.out.printf("%d titles extracted%n", titles.size());

        return titles;
    }

    private static boolean checkAndUpdateStoredData(List<String> list) throws IOException {
        int newHashCode = list.hashCode();
        int storedHashCode;

        Path fHash = LOCATION.resolve("hash.txt");

        try {
            storedHashCode = Integer.parseInt(Files.readString(fHash));
        } catch (IOException | NumberFormatException e) {
            storedHashCode = 0;
        }

        if (storedHashCode != newHashCode) {
            Files.writeString(fHash, Integer.toString(newHashCode));
            Files.write(LOCATION.resolve("list.txt"), list);
            return true;
        } else {
            return false;
        }
    }

    private static String makePage(List<String> list) throws IOException {
        com.github.wikibot.parsing.Page page =
            com.github.wikibot.parsing.Page.create(TARGET_PAGE);

        page.setIntro(String.format(
            "Lista zawiera %s %s. Aktualizacja: ~~~~~.",
            NUMBER_FORMAT_PL.format(list.size()), PLURAL_PL.pl(list.size(), "hasło")
        ));

        list.stream()
            .collect(Collectors.groupingBy(
                SpanishCanonicalInflectedForms::getFirstChar,
                LinkedHashMap::new,
                Collectors.mapping(
                    title -> String.format("[[%s]]", title),
                    Collectors.joining(" • ")
                )
            ))
            .forEach((letter, content) -> {
                com.github.wikibot.parsing.Section section =
                    com.github.wikibot.parsing.Section.create(letter.toString(), 2);
                section.setIntro(content);
                page.appendSections(List.of(section));
            });

        String pageText = wb.getPageText(List.of(TARGET_PAGE)).get(0);
        pageText = pageText.substring(0, pageText.indexOf("-->") + 3);
        page.setIntro(pageText + "\n" + page.getIntro());

        return page.toString();
    }

    private static Character getFirstChar(String str) {
        char letter = str.charAt(0);
        return STRIPPED_ACCENTS_MAP.getOrDefault(letter, letter);
    }

    private static boolean matchNonInflectedDefinitions(Field definitions) {
        return definitions.getContent().lines()
            .anyMatch(SpanishCanonicalInflectedForms::matchNonInflectedDefinitionLine);
    }

    private static boolean matchNonInflectedDefinitionLine(String line) {
        return !line.startsWith(":") && !line.contains("{{forma ") && !line.contains("{{zbitka");
    }
}
