package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;

public final class BoldedSelflinks {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/BoldedSelflinks/");
    private static final String TARGET_PAGE = "Wikipedysta:PBbot/pogrubione selflinki";

    // https://pl.wiktionary.org/wiki/MediaWiki:Gadget-section-links.js
    private static final List<String> IGNORED_SELFLINKS = List.of(
        "znak chiński", "chiński standardowy", "minnan", "japoński", "gan", "ajnoski", "kantoński"
    );

    private static final List<String> IGNORED_LANGS = List.of("niemiecki");

    private static final Map<FieldTypes, List<String>> IGNORED_FIELDS;

    // from Linker::formatLinksInComment in Linker.php
    private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]([^\\[]*)");
    private static final Pattern P_BOLD = Pattern.compile("'{3}([^\\[\\]\\{\\}]+?)'{3}");
    private static final Pattern P_TRANSL = Pattern.compile("→[^•;]+");
    private static final Pattern P_TEMPLATE = Pattern.compile("\\{{2}[^\\}\n]+?\\}{2}");

    private static final Collator COLL_PL = Collator.getInstance(Locale.forLanguageTag("pl"));

    private static final String PAGE_INTRO;

    static {
        var ignoredFields = new LinkedHashMap<FieldTypes, List<String>>();
        ignoredFields.put(FieldTypes.EXAMPLES, Collections.emptyList());
        ignoredFields.put(FieldTypes.TRANSLATIONS, Collections.emptyList());
        ignoredFields.put(FieldTypes.ETYMOLOGY, Collections.emptyList());
        ignoredFields.put(FieldTypes.DERIVED_TERMS, Collections.emptyList());
        ignoredFields.put(FieldTypes.NOTES, Collections.emptyList());
        ignoredFields.put(FieldTypes.DEFINITIONS, List.of("polski"));

        IGNORED_FIELDS = Collections.unmodifiableMap(ignoredFields);

        var excludedSelflinks = IGNORED_SELFLINKS.stream().collect(Collectors.joining(", "));
        var excludedLangs = IGNORED_LANGS.stream().collect(Collectors.joining(", "));

        var excludedFields = IGNORED_FIELDS.entrySet().stream()
            .map(e -> !e.getValue().isEmpty()
                ? String.format(
                    "%s (wyjątki: %s)",
                    e.getKey().localised(),
                    e.getValue().stream().collect(Collectors.joining(", "))
                )
                : e.getKey().localised())
            .collect(Collectors.joining(", "));

        PAGE_INTRO = """
            Zestawienie wystąpień pogrubionych selflinków, czyli linków prowadzących do strony, w której
            się znajdują. Automatyczne pogrubianie takich linków, wymuszone przez oprogramowanie MediaWiki,
            jest zazwyczaj tłumione za sprawą lokalnego [[MediaWiki:Gadget-section-links.js|skryptu JS]],
            o ile dany link nie prowadzi do tej samej sekcji językowej. Wykluczenia:
            * języki z wyłączoną obsługą selflinków przez JS – %s
            * języki źle współpracujące z mechanizmem selflinków – %s
            * pola – %s
            Lista uwzględnia również zwykłe pogrubienia (tekst owinięty znakami <code><nowiki>'''</nowiki></code>,
            niezawierający <code><nowiki>[]{}</nowiki></code>) na powyższych zasadach.

            Dane na podstawie zrzutu z bazy danych z dnia $1. Aktualizacja: ~~~~~.
            ----
            """.formatted(excludedSelflinks, excludedLangs, excludedFields);
    }

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var list = analyzeDump(dump);

        if (!checkAndUpdateStoredData(list)) {
            System.out.println("No changes detected, aborting.");
            return;
        }

        var wb = Wikibot.newSession("pl.wiktionary.org");
        Login.login(wb);

        var timestamp = extractTimestamp(dump.getDirectoryName());
        var pageText = makePageText(list, timestamp);

        wb.setMarkBot(false);
        wb.edit(TARGET_PAGE, pageText, "aktualizacja");

        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws IOException, ParseException {
        var dumpConfig = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES);

        if (args.length != 0) {
            var options = new Options();
            options.addOption("l", "local", false, "use latest local dump");

            var parser = new DefaultParser();
            var line = parser.parse(options, args);

            if (line.hasOption("local")) {
                if (Files.exists(path)) {
                    dumpConfig.after(Files.readString(path).strip());
                }

                dumpConfig.local();
            } else {
                new HelpFormatter().printHelp(BoldedSelflinks.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static List<Item> analyzeDump(XMLDump dump) {
        final var pNewline = Pattern.compile("\n");

        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::nonRedirect)
                .filter(XMLRevision::isMainNamespace)
                .map(rev -> Page.store(rev.getTitle(), rev.getText()))
                .flatMap(p -> p.getAllSections().stream()
                    .filter(s -> !IGNORED_LANGS.contains(s.getLangShort()))
                    .flatMap(s -> s.getAllFields().stream()
                        .filter(f -> !f.isEmpty())
                        .filter(f -> IGNORED_FIELDS.entrySet().stream()
                            .allMatch(e -> f.getFieldType() != e.getKey() || e.getValue().contains(s.getLangShort()))
                        )
                        .flatMap(f -> pNewline.splitAsStream(f.getContent())
                            .filter(line -> filterLines(line, p.getTitle(), s.getLangShort()))
                            .map(line ->
                                new Item(p.getTitle(), s.getLangShort(), f.getFieldType().localised(), line)
                            )
                        )
                    )
                )
                .sorted((i1, i2) -> COLL_PL.compare(i1.title, i2.title))
                .toList();
        }
    }

    private static boolean filterLines(String line, String title, String lang) {
        if (line.contains("→")) {
            line = P_TRANSL.matcher(line).replaceAll("");
        }

        if (line.contains("{") || line.contains("}")) {
            line = P_TEMPLATE.matcher(line).replaceAll("");
        }

        if (!IGNORED_SELFLINKS.contains(lang)) {
            var m = P_LINK.matcher(line);

            while (m.find()) {
                var target = m.group(1).trim().replaceFirst("#.*", ""); // ignore URL fragments

                if (target.equals(title)) {
                    return true;
                }
            }
        }

        var m = P_BOLD.matcher(line);

        while (m.find()) {
            var target = m.group(1).trim();

            if (target.equals(title)) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkAndUpdateStoredData(List<Item> list) throws IOException {
        int newHashCode = list.hashCode();
        int storedHashCode;

        var fHash = LOCATION.resolve("hash.txt");
        var fList = LOCATION.resolve("list.xml");

        try {
            storedHashCode = Integer.parseInt(Files.readString(fHash));
        } catch (IOException | NumberFormatException e) {
            e.printStackTrace();
            storedHashCode = 0;
        }

        if (storedHashCode != newHashCode) {
            Files.writeString(fHash, Integer.toString(newHashCode));
            Files.writeString(fList, new XStream().toXML(list));
            return true;
        } else {
            return false;
        }
    }

    private static String extractTimestamp(String canonicalTimestamp) {
        try {
            var originalDateFormat = new SimpleDateFormat("yyyyMMdd");
            var date = originalDateFormat.parse(canonicalTimestamp);
            var desiredDateFormat = new SimpleDateFormat("dd/MM/yyyy");
            return desiredDateFormat.format(date);
        } catch (java.text.ParseException e) {
            return "(błąd odczytu sygnatury czasowej: ''" + canonicalTimestamp + "'')";
        }
    }

    private static String makePageText(List<Item> list, String timestamp) {
        var output = list.stream()
            .collect(Collectors.groupingBy(
                item -> item.title,
                () -> new TreeMap<>(COLL_PL),
                Collectors.mapping(
                    item -> String.format(
                        "#:(%s, %s) <nowiki>%s</nowiki>",
                        item.language, item.fieldType, item.line
                    ),
                    Collectors.toList()
                )
            ))
            .entrySet().stream()
            .map(entry -> String.format("#[[%s]]%n%s", entry.getKey(), String.join("\n", entry.getValue())))
            .collect(Collectors.joining("\n"));

        return PAGE_INTRO.replace("$1", timestamp) + output;
    }

    private record Item (String title, String language, String fieldType, String line) {}
}
