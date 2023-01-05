package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public final class MissingPolishAudio {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishAudio/");
    private static final List<String> REG_CATEGORIES = List.of("Regionalizmy polskie", "Dialektyzmy polskie");
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        Login.login(wb);

        var dump = optDump.get();
        var regMap = categorizeRegWords();
        var targetMap = new HashMap<String, Set<String>>();

        try (var stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getPolishSection().stream())
                .flatMap(s -> s.getField(FieldTypes.PRONUNCIATION).stream())
                .filter(f -> f.isEmpty() || ParseUtils.getTemplates("audio", f.getContent()).isEmpty())
                .map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
                .forEach(title -> categorizeTargets(title, regMap, targetMap));
        }

        writeLists(targetMap, extractTimestamp(dump.getDirectoryName()));
        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws ParseException, IOException {
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
                new HelpFormatter().printHelp(MissingPolishAudio.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static String normalize(String category) {
        return wb.removeNamespace(category).toLowerCase().replaceAll("[ -]+", "-");
    }

    private static Map<String, List<String>> categorizeRegWords() throws IOException {
        var map = new HashMap<String, List<String>>();

        for (var mainCat : REG_CATEGORIES) {
            for (var subCat : wb.getCategoryMembers(mainCat, Wiki.CATEGORY_NAMESPACE)) {
                for (var title : wb.getCategoryMembers(subCat, Wiki.MAIN_NAMESPACE)) {
                    map.computeIfAbsent(title, k -> new ArrayList<>()).add(normalize(subCat));
                }
            }

            for (var title : wb.getCategoryMembers(mainCat, Wiki.MAIN_NAMESPACE)) {
                map.computeIfAbsent(title, k -> new ArrayList<>()).add(normalize(mainCat));
            }
        }

        return map;
    }

    private static void categorizeTargets(String title, Map<String, List<String>> regMap, Map<String, Set<String>> targetMap) {
        if (!regMap.containsKey(title)) {
            targetMap.computeIfAbsent("ogólnopolskie", k -> new TreeSet<>()).add(title);
        } else {
            for (var category : regMap.get(title)) {
                targetMap.computeIfAbsent(category, k -> new TreeSet<>()).add(title);
            }
        }
    }

    private static String extractTimestamp(String canonicalTimestamp) {
        try {
            var originalDateFormat = new SimpleDateFormat("yyyyMMdd");
            var date = originalDateFormat.parse(canonicalTimestamp);
            var desiredDateFormat = new SimpleDateFormat("dd-MM-yyyy");
            return desiredDateFormat.format(date);
        } catch (java.text.ParseException e) {
            return "brak-daty";
        }
    }

    private static void writeLists(Map<String, Set<String>> map, String timestamp) throws IOException {
        try (var files = Files.newDirectoryStream(LOCATION)) {
            for (var file : files) {
                Files.delete(file);
            }
        }

        for (var entry : map.entrySet()) {
            var filename = String.format("%s-%s.txt", entry.getKey(), timestamp);
            var output = entry.getValue().stream().map(s -> String.format("#%s", s)).collect(Collectors.joining(" "));
            Files.writeString(LOCATION.resolve(filename), output);
        }
    }
}
