package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public class ArticleStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/ArticleStats/");
    private static final String MODULE_PAGE = "Moduł:statystyka/dane.json";
    private static final String STATS_PAGE = "Wikisłownik:Statystyka";
    private static final Pattern P_DEF = Pattern.compile("^: *\\(\\d+\\.\\d+(?:\\.\\d+)?\\)");
    private static final Pattern P_FILE = Pattern.compile("\\[{2} *(?i:Plik|File|Image):.+?\\]{2}");
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final XStream xstream = new XStream();

    static {
        xstream.allowTypes(new Class[] {Stats.class});
        xstream.processAnnotations(Stats.class);
    }

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var statsPath = LOCATION.resolve("stats.xml");
        var jsonPath = LOCATION.resolve("stats.json");

        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var stats = analyzeDump(dump);
        var prevStats = retrieveStats(statsPath);

        var json = makeJson(stats, prevStats);
        json.put("currentDate", dump.getDirectoryName());

        if (prevStats != null && Files.exists(datePath)) {
            json.put("previousDate", Files.readString(datePath).strip());
        }

        Login.login(wb);
        wb.edit(MODULE_PAGE, json.toString(), "aktualizacja: " + dump.getDescriptiveFilename());

        Files.writeString(statsPath, xstream.toXML(stats));
        Files.writeString(datePath, dump.getDirectoryName());
        Files.writeString(jsonPath, json.toString());

        try {
            // attempt a null edit; purging doesn't work
            var pc = wb.getContentOfPages(List.of(STATS_PAGE)).get(0);
            wb.edit(pc.getTitle(), pc.getText(), "", pc.getTimestamp());
        } catch (Throwable t) {}
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
                new HelpFormatter().printHelp(ArticleStats.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static Map<String, Stats> analyzeDump(XMLDump dump) {
        var map = new HashMap<String, Stats>();

        try (var stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getAllSections().stream())
                .forEach(s -> {
                    var stats = map.computeIfAbsent(s.getLangShort(), k -> new Stats());
                    var definitions = s.getField(FieldTypes.DEFINITIONS);

                    if (definitions.isEmpty()) {
                        return;
                    }

                    var headerToDefs = processDefinitions(definitions.get().toString());

                    var canonicalDefs = headerToDefs.entrySet().stream()
                        .filter(e -> !isFlexiveForm(e.getKey()))
                        .mapToInt(e -> e.getValue().size())
                        .sum();

                    var flexiveDefs = headerToDefs.entrySet().stream()
                        .filter(e -> isFlexiveForm(e.getKey()))
                        .mapToInt(e -> e.getValue().size())
                        .sum();

                    stats.entries++;

                    if (canonicalDefs != 0 || canonicalDefs + flexiveDefs == 0) {
                        // includes a range of entries with an empty definitions field, i.e. at time of writing all chinese signs
                        // ('znak chiński' sections) and a bunch (25) of Japanese kanjis
                        stats.canonical++;
                    }

                    if (flexiveDefs != 0) {
                        stats.nonCanonical++;
                    }

                    stats.definitions += canonicalDefs;

                    if (
                        !Jsoup.parse(s.toString()).getElementsByTag("ref").isEmpty() &&
                        s.getField(FieldTypes.SOURCES)
                            .filter(f -> !f.isEmpty())
                            .filter(f -> !Jsoup.parse(f.getContent()).getElementsByTag("references").isEmpty())
                            .isPresent()
                    ) {
                        stats.withReferences++;
                    }

                    if (s.getField(FieldTypes.PRONUNCIATION).filter(f -> f.getContent().contains("{{audio")).isPresent()) {
                        stats.withAudio++;
                    }

                    if (P_FILE.matcher(s.getIntro()).find()) {
                        stats.withFiles++;
                    }
                });
        }

        return map;
    }

    private static final Map<String, List<String>> processDefinitions(String text) {
        var header = "";
        var defs = new ArrayList<String>();
        var map = new HashMap<String, List<String>>();

        for (var line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }

            if (P_DEF.matcher(line).find()) {
                defs.add(line);
            } else {
                if (!defs.isEmpty()) {
                    map.put(header, new ArrayList<>(defs));
                }

                defs.clear();
                header = line;
            }
        }

        if (!defs.isEmpty()) {
            map.put(header, new ArrayList<>(defs));
        }

        return map;
    }

    private static boolean isFlexiveForm(String header) {
        return StringUtils.containsAny(header, "forma fleksyjna", "forma odmieniona", "{{forma ");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Stats> retrieveStats(Path path) {
        try {
            return (Map<String, Stats>) xstream.fromXML(Files.readString(path));
        } catch (IOException | XStreamException e) {
            return null;
        }
    }

    private static JSONObject makeJson(Map<String, Stats> stats, Map<String, Stats> prevStats) {
        var out = new JSONObject();

        var overallMap = accumulateStats(stats.values());
        out.put("overall", new JSONObject(overallMap));

        if (prevStats != null) {
            var overallDiffMap = accumulateStats(prevStats.values());
            var overallDiff = new JSONObject();

            for (var e : overallMap.entrySet()) {
                overallDiff.put(e.getKey(), e.getValue() - overallDiffMap.get(e.getKey()));
            }

            out.put("overallDiff", overallDiff);
        }

        var languages = new JSONObject();

        for (var e : stats.entrySet()) {
            var json = e.getValue().toJson();

            if (prevStats != null && prevStats.containsKey(e.getKey())) {
                var map = json.toMap();
                var diffMap = e.getValue().toJsonDifference(prevStats.get(e.getKey())).toMap();

                diffMap.values().removeIf(sum -> (int)sum == 0); // save some space
                map.putAll(diffMap);
                json = new JSONObject(map);
            }

            languages.put(e.getKey(), json);
        }

        out.put("languages", languages);
        return out;
    }

    private static Map<String, Integer> accumulateStats(Collection<Stats> stats) {
        return Map.of(
            "entries", stats.stream().mapToInt(s -> s.entries).sum(),
            "canonical", stats.stream().mapToInt(s -> s.canonical).sum(),
            "nonCanonical", stats.stream().mapToInt(s -> s.nonCanonical).sum(),
            "definitions", stats.stream().mapToInt(s -> s.definitions).sum(),
            "withReferences", stats.stream().mapToInt(s -> s.withReferences).sum(),
            "withAudio", stats.stream().mapToInt(s -> s.withAudio).sum(),
            "withFiles", stats.stream().mapToInt(s -> s.withFiles).sum()
        );
    }

    @XStreamAlias("entry")
    private static class Stats {
        int entries; // overall number of entries
        int canonical; // number of entries with canonical forms
        int nonCanonical; // number of entries with non-canonical forms
        int definitions; // number of canonical definitions
        int withReferences; // has <references>
        int withAudio; // has {{audio}} or similar
        int withFiles; // has file transclusions

        JSONObject toJson() {
            return new JSONObject(Map.of(
                "entries", entries,
                "canonical", canonical,
                "nonCanonical", nonCanonical,
                "definitions", definitions,
                "withReferences", withReferences,
                "withAudio", withAudio,
                "withFiles", withFiles
            ));
        }

        JSONObject toJsonDifference(Stats other) {
            return new JSONObject(Map.of(
                "entriesDiff", entries - other.entries,
                "canonicalDiff", canonical - other.canonical,
                "nonCanonicalDiff", nonCanonical - other.nonCanonical,
                "definitionsDiff", definitions - other.definitions,
                "withReferencesDiff", withReferences - other.withReferences,
                "withAudioDiff", withAudio - other.withAudio,
                "withFilesDiff", withFiles - other.withFiles
            ));
        }
    }
}
