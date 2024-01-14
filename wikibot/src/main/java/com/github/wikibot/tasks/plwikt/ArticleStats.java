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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

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
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public class ArticleStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/ArticleStats/");
    private static final String MODULE_PAGE = "Moduł:statystyka/dane.json";
    private static final String STATS_PAGE = "Wikisłownik:Statystyka";
    private static final Pattern P_DEF = Pattern.compile("^: *\\(\\d+\\.\\d+(?:\\.\\d+)?\\)");
    private static final Pattern P_FILE = Pattern.compile("\\[{2} *(?i:Plik|File|Image):.+?\\]{2}");
    private static final Pattern P_LINK = Pattern.compile("\\[{2}:?([^\\]|]+)(?:\\|((?:.(?!\\[{2}.+?\\]{2}))+?))*\\]{2}");
    private static final Pattern P_TEMPLATE = Pattern.compile("\\{{2}([^<>\n\\{\\}\\[\\]\\|]+)(?:.(?!\\{{2}.+?\\}{2}))*?\\}{2}", Pattern.DOTALL);
    private static final Pattern P_LIST = Pattern.compile("^[:;#\\*]\\s*", Pattern.MULTILINE);
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final XStream xstream = new XStream();

    static {
        xstream.allowTypes(new Class[] {Stats.class});
        xstream.processAnnotations(Stats.class);
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

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

        var supportedLanguages = wb.getCategoryMembers("Indeks słów wg języków", Wiki.CATEGORY_NAMESPACE).stream()
            .map(wb::removeNamespace)
            .map(category -> category.replaceFirst(" \\(indeks\\)$", ""))
            .collect(Collectors.toSet());

        stats.keySet().retainAll(supportedLanguages);

        var json = makeJson(stats, prevStats);
        json.put("currentDate", dump.getDirectoryName());

        if (prevStats != null && Files.exists(datePath)) {
            json.put("previousDate", Files.readString(datePath).strip());
        }

        wb.edit(MODULE_PAGE, json.toString(), "aktualizacja: " + dump.getDescriptiveFilename());

        Files.writeString(statsPath, xstream.toXML(stats));
        Files.writeString(datePath, dump.getDirectoryName());
        Files.writeString(jsonPath, json.toString());

        try {
            // attempt a null edit; purging doesn't work
            var pc = wb.getContentOfPages(List.of(STATS_PAGE)).get(0);
            wb.edit(pc.title(), pc.text(), "", pc.timestamp());
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

                    stats.combinedLength += Stream.concat(
                            Stream.of(s.getIntro())
                                .filter(intro -> !intro.isBlank()),
                            s.getAllFields().stream()
                                .filter(f -> f.getFieldType() != FieldTypes.TRANSLATIONS)
                                .filter(f -> !f.isEmpty())
                                .map(Field::getContent)
                        )
                        .map(ArticleStats::stripFieldContents)
                        .mapToInt(String::length)
                        .sum();
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

    private static String stripFieldContents(String text) {
        text = ParseUtils.removeCommentsAndNoWikiText(text);
        text = text.replaceAll("\\{\\|.+?\\|\\}", ""); // nuke all tables in wiki markup

        while (true) {
            var temp = P_TEMPLATE.matcher(text).replaceAll(mr -> ParseUtils.getTemplates(mr.group(1).strip(), mr.group()).stream()
                .map(ParseUtils::getTemplateParametersWithValue)
                .map(params -> params.size() == 1
                    ? params.get("templateName") // no template params
                    : params.entrySet().stream()
                        .filter(e -> !e.getKey().equals("templateName"))
                        .map(Map.Entry::getValue)
                        .collect(Collectors.joining(" "))
                )
                .map(Matcher::quoteReplacement)
                .collect(Collectors.joining("")) // should have only one template at most, anyway
            );

            if (temp.equals(text)) {
                break;
            } else {
                text = temp;
            }
        }

        while (true) {
            // accounts for file links with nested links in captions
            var temp = P_LINK.matcher(text).replaceAll(mr -> Matcher.quoteReplacement(Optional.ofNullable(mr.group(2)).orElse(mr.group(1)).strip()));

            if (temp.equals(text)) {
                break;
            } else {
                text = temp;
            }
        }

        text = P_LIST.matcher(text).replaceAll("");
        text = Jsoup.parse(text).text(); // remove HTML entities

        return text
            .replace("'''", "") // bolds
            .replace("''", "") // italics
            .replaceAll("\\s{2,}", " "); // multiple consecutive whitespaces
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

                diffMap.values().removeIf(diff -> ((Number)diff).longValue() == 0L); // save some space
                map.putAll(diffMap);
                json = new JSONObject(map);
            }

            languages.put(e.getKey(), json);
        }

        out.put("languages", languages);
        return out;
    }

    private static Map<String, Long> accumulateStats(Collection<Stats> stats) {
        return Map.of(
            "entries", stats.stream().mapToLong(s -> s.entries).sum(),
            "canonical", stats.stream().mapToLong(s -> s.canonical).sum(),
            "nonCanonical", stats.stream().mapToLong(s -> s.nonCanonical).sum(),
            "definitions", stats.stream().mapToLong(s -> s.definitions).sum(),
            "withReferences", stats.stream().mapToLong(s -> s.withReferences).sum(),
            "withAudio", stats.stream().mapToLong(s -> s.withAudio).sum(),
            "withFiles", stats.stream().mapToLong(s -> s.withFiles).sum(),
            "combinedLength", stats.stream().mapToLong(s -> s.combinedLength).sum()
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
        long combinedLength; // combined normalized length of all entries

        JSONObject toJson() {
            return new JSONObject(Map.of(
                "entries", entries,
                "canonical", canonical,
                "nonCanonical", nonCanonical,
                "definitions", definitions,
                "withReferences", withReferences,
                "withAudio", withAudio,
                "withFiles", withFiles,
                "combinedLength", combinedLength
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
                "withFilesDiff", withFiles - other.withFiles,
                "combinedLengthDiff", combinedLength - other.combinedLength
            ));
        }
    }
}
