package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

import com.thoughtworks.xstream.XStream;

public final class WantedPolishEntries {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/WantedPolishEntries/");
    private static final Pattern PATT_LINK = Pattern.compile("\\[{2} *?:?(?!(?i:Plik|File):)([^\\[\\]\\|#]+)(#[^\\|\\]]*?)?(?:\\|((?:]?[^\\]])*+))?\\]{2}([a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)?");
    private static final int MAX_ENTRIES = 20000;
    private static final int GAP = 2000;

    private static final Set<FieldTypes> POLISH_FIELDS = Set.of(
        FieldTypes.DEFINITIONS,
        FieldTypes.EXAMPLES,
        FieldTypes.SYNTAX,
        FieldTypes.COLLOCATIONS,
        FieldTypes.SYNONYMS,
        FieldTypes.ANTONYMS,
        FieldTypes.HYPERNYMS,
        FieldTypes.HYPONYMS,
        FieldTypes.MERONYMS,
        FieldTypes.HOLONYMS,
        FieldTypes.RELATED_TERMS
    );

    private static final String CONTENT_TEMPLATE = """
        Lista frekwencyjna języka polskiego na podstawie odnośników na stronach Wikisłownika.

        {{język linków|polski}}
        %s
        [[Kategoria:Polski (słowniki tematyczne)]]
        [[Kategoria:Listy frekwencyjne|polski]]
        """;

    private static final String TARGET_TEMPLATE = "Indeks:Polski - Najpopularniejsze słowa %d-%d";

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var storage = analyzeDump(dump);
        var collator = Collator.getInstance(Locale.forLanguageTag("pl-PL"));

        var results = storage.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey, collator)
            )
            .limit(MAX_ENTRIES)
            .map(e -> "[[%s]]=%d".formatted(e.getKey(), e.getValue()))
            .toList();

        if (!checkAndUpdateStoredData(results)) {
            System.out.println("No changes detected, aborting.");
            return;
        }

        Login.login(wb);

        for (int i = 0; i < Math.ceil(results.size() / GAP); i++) {
            var start = i * GAP + 1;
            var end = (i + 1) * GAP;
            var title = TARGET_TEMPLATE.formatted(start, end);
            var content = CONTENT_TEMPLATE.formatted(String.join("\n", results.subList(start - 1, end)));

            wb.edit(title, content, "aktualizacja na podstawie zrzutu %s".formatted(dump.getDescriptiveFilename()));
        }

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

    private static Map<String, Integer> analyzeDump(XMLDump dump) {
        var map = new HashMap<String, Integer>();

        try (var stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .map(WantedPolishEntries::sanitizeText)
                .flatMap(text -> PATT_LINK.matcher(text).results())
                .map(mr -> mr.group(1).trim())
                .forEach(target -> map.merge(target, 1, Integer::sum));
        }

        return map;
    }

    private static String sanitizeText(Page p) {
        var lines = new ArrayList<String>();

        for (var section : p.getAllSections()) {
            if (section.isPolishSection()) {
                section.getAllFields().stream()
                    .filter(f -> POLISH_FIELDS.contains(f.getFieldType()))
                    .filter(f -> !f.isEmpty())
                    .map(Field::getContent)
                    .forEach(lines::add);
            } else {
                for (var f : section.getAllFields()) {
                    if (f.getFieldType() == FieldTypes.DEFINITIONS) {
                        for (var line : f.getContent().split("\n")) {
                            var index = Math.max(line.lastIndexOf("→"), line.lastIndexOf("="));
                            lines.add(line.substring(index + 1).strip());
                        }
                    } else if (f.getContent().contains("→") && (
                        f.getFieldType() == FieldTypes.EXAMPLES || f.getFieldType() == FieldTypes.COLLOCATIONS)
                    ) {
                        f.getContent().lines()
                            .filter(l -> l.contains("→"))
                            .map(l -> l.substring(l.lastIndexOf('→') + 1).strip())
                            .forEach(lines::add);
                    }
                }
            }
        }

        return String.join("\n", lines);
    }

    private static boolean checkAndUpdateStoredData(List<String> list) throws IOException {
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
}
