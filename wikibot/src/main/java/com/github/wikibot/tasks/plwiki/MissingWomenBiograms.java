package com.github.wikibot.tasks.plwiki;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.security.auth.login.LoginException;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.scripts.wd.FetchEpwnBiograms;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.annotations.XStreamAlias;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MissingWomenBiograms {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/MissingWomenBiograms/");
    private static final Path HASHCODES = LOCATION.resolve("hashcodes.xml");
    private static final Path LATEST_DUMP = LOCATION.resolve("latest-dump.txt");
    private static final String BOT_SUBPAGE = "Wikipedysta:PBbot/brakujące biogramy o kobietach";
    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");
    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");
    private static final XStream xstream = new XStream();
    private static final int MAX_PRINTED_RESULTS = 1000;
    private static final String STATIC_URL_DUMP_FMT = "//tools-static.wmflabs.org/pbbot/plwiki/missing-women-biograms/%s.xml.bz2";

    private static final List<QueryItem> QUERY_CONFIG;
    private static final DateTimeFormatter DATE_FORMATTER;

    static {
        try {
            var json = wb.getPageText(List.of(BOT_SUBPAGE + "/konfiguracja.json")).get(0);
            QUERY_CONFIG = parseQueryConfig(new JSONArray(json));

            DATE_FORMATTER = new DateTimeFormatterBuilder()
                .appendValue(ChronoField.YEAR, 4, 4, SignStyle.ALWAYS)
                .appendLiteral('-')
                .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                .appendLiteral('-')
                .appendValue(ChronoField.DAY_OF_MONTH, 2)
                .appendLiteral("T00:00:00Z")
                .toFormatter();

            SPARQL_REPO.setAdditionalHttpHeaders(Collections.singletonMap("User-Agent", Login.getUserAgent()));

            xstream.processAnnotations(Entry.class);
            xstream.allowTypes(new Class[] {Entry.class});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Configuration:");
        QUERY_CONFIG.forEach(System.out::println);

        var line = readOptions(args);
        var entries = new TreeMap<QueryItem, List<Entry>>((i1, i2) -> Integer.compare(QUERY_CONFIG.indexOf(i1), QUERY_CONFIG.indexOf(i2)));
        var dumpPath = Paths.get("");

        if (line.hasOption("dump")) {
            var biogramEntities = new HashSet<>(queryBiogramEntities());
            var reader = new XMLDumpReader("wikidatawiki", true);

            try (var stream = reader.seekTitles(biogramEntities).getStAXReaderStream()) {
                stream
                    .filter(XMLRevision::isMainNamespace)
                    .filter(XMLRevision::nonRedirect)
                    .filter(rev -> biogramEntities.contains(rev.getTitle()))
                    .map(rev -> new JSONObject(rev.getText()))
                    .filter(json -> Optional.ofNullable(json.optJSONObject("claims")).filter(MissingWomenBiograms::isWoman).isPresent())
                    .filter(json -> Optional.ofNullable(json.optJSONObject("sitelinks")).filter(sl -> sl.has("plwiki")).isEmpty())
                    .forEach(json -> analyzeEntity(json, entries));
            }

            for (var entry : entries.entrySet()) {
                Collections.sort(entry.getValue(), EntryComparator.prioritizeLanglinks());
                var path = LOCATION.resolve(entry.getKey().filename() + ".xml.bz2");
                System.out.printf("Writing %d entries to %s%n", entry.getValue().size(), path.getFileName());

                try (var stream = new BZip2CompressorOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                    xstream.toXML(entry.getValue(), stream);
                }
            }

            dumpPath = reader.getPathToDump();
            Files.writeString(LATEST_DUMP, dumpPath.toString());
        } else if (line.hasOption("edit")) {
            for (var queryItem : QUERY_CONFIG) {
                var path = LOCATION.resolve(queryItem.filename() + ".xml.bz2");

                try (var stream = new BZip2CompressorInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    @SuppressWarnings("unchecked")
                    var data = (List<Entry>)xstream.fromXML(stream);
                    entries.put(queryItem, data);
                    System.out.printf("Retrieved %d entries from %s%n", data.size(), path.getFileName());
                }
            }

            dumpPath = Paths.get(Files.readString(LATEST_DUMP));
        }

        if (line.hasOption("edit")) {
            Login.login(wb);

            var hashcodes = retrieveHashcodes();
            var initialHash = hashcodes.hashCode();

            if (hashcodes.getOrDefault("config", 0) != QUERY_CONFIG.hashCode()) {
                writeMainSubpage(entries);
                hashcodes.put("config", QUERY_CONFIG.hashCode());
            }

            for (var entry : entries.entrySet()) {
                var portion = entry.getValue().stream()
                    .limit(MAX_PRINTED_RESULTS)
                    .sorted(EntryComparator.prioritizeNames())
                    .toList();

                if (hashcodes.getOrDefault(entry.getKey().subtype(), 0) != portion.hashCode()) {
                    writeEntrySubpage(entry.getKey(), portion, dumpPath, entry.getValue().size());
                    hashcodes.put(entry.getKey().subtype(), portion.hashCode());
                }
            }

            if (hashcodes.hashCode() != initialHash) {
                Files.writeString(HASHCODES, xstream.toXML(hashcodes));
            } else {
                System.out.println("No changes detected");
            }
        }
    }

    private static List<QueryItem> parseQueryConfig(JSONArray arr) {
        var items = new ArrayList<QueryItem>();

        for (var supertype : arr) {
            var supername = ((JSONObject)supertype).getString("name");
            var property = ((JSONObject)supertype).getString("property");

            for (var subtype : ((JSONObject)supertype).getJSONArray("items")) {
                var subname = ((JSONObject)subtype).getString("name");
                var entity = ((JSONObject)subtype).getString("entity");
                var filename = StringUtils.replaceChars(subname, "ĄĆĘŁŃÓŚŹŻąćęłńóśźż ", "ACELNOSZZacelnoszz-");
                items.add(new QueryItem(supername, subname, property, entity, filename));
            }
        }

        return Collections.unmodifiableList(items);
    }

    private static CommandLine readOptions(String[] args) throws ParseException {
        var options = new Options();
        options.addOption("d", "dump", false, "process dump file");
        options.addOption("e", "edit", false, "edit lists on-wiki");

        if (args.length == 0) {
            System.out.print("Option(s): ");
            var input = Misc.readLine();
            args = input.split(" ");
        }

        try {
            return new DefaultParser().parse(options, args, true);
        } catch (ParseException e) {
            new HelpFormatter().printHelp(FetchEpwnBiograms.class.getName(), options);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> retrieveHashcodes() {
        try {
            return (HashMap<String, Integer>)xstream.fromXML(Files.readString(HASHCODES));
        } catch (IOException | XStreamException | ClassCastException e) {
            return new HashMap<String, Integer>();
        }
    }

    private static boolean isWoman(JSONObject claims) {
        return Optional.ofNullable(claims.optJSONArray("P21"))
            .filter(snaks -> StreamSupport.stream(snaks.spliterator(), false)
                .map(obj -> (JSONObject)obj)
                .anyMatch(snak -> Optional.ofNullable(snak.optJSONObject("mainsnak"))
                    .map(mainsnak -> mainsnak.optJSONObject("datavalue"))
                    .map(datavalue -> datavalue.optJSONObject("value"))
                    .map(value -> value.optString("id"))
                    .filter(id -> id.equals("Q6581072"))
                    .isPresent()
            ))
            .isPresent();
    }

    private static List<String> queryBiogramEntities() {
        var biograms = queryEntities("all biograms", "?item wdt:P21 wd:Q6581072");
        var targeted = new HashSet<String>();

        for (var queryItem : QUERY_CONFIG) {
            var queryConds = String.format("?item wdt:%s wd:%s", queryItem.property(), queryItem.entity());
            var results = queryEntities(queryItem.subtype(), queryConds);
            targeted.addAll(results);
        }

        biograms.retainAll(targeted);
        System.out.println("Targeted biograms: " + biograms.size());

        var plwikiArticles = queryEntities("plwiki articles", "?wfr schema:about ?item ; schema:inLanguage \"pl\"");
        biograms.removeAll(new HashSet<>(plwikiArticles));
        System.out.println("Targeted biograms not linked to any plwiki article: " + biograms.size());

        return biograms;
    }

    private static List<String> queryEntities(String tag, String queryConds) {
        var entities = new ArrayList<String>(2000000);

        try (var connection = SPARQL_REPO.getConnection()) {
            var querySelect = String.format("SELECT ?item WHERE { %s }", queryConds);
            var query = connection.prepareTupleQuery(querySelect);

            try (var result = query.evaluate()) {
                result.forEach(bs -> entities.add(((IRI)bs.getValue("item")).getLocalName()));
            }
        }

        var entityPattern = Pattern.compile("^Q\\d+$");
        entities.removeIf(entity -> !entityPattern.matcher(entity).matches());

        System.out.printf("Got %d entities for query: %s%n", entities.size(), tag);
        return entities;
    }

    private static void analyzeEntity(JSONObject json, Map<QueryItem, List<Entry>> entries) {
        // https://doc.wikimedia.org/Wikibase/master/php/md_docs_topics_json.html
        var item = json.getString("id");
        var labels = Optional.ofNullable(json.optJSONObject("labels"));

        var name = labels
            .map(obj -> obj.optJSONObject("pl"))
            .or(() -> labels.map(obj -> obj.optJSONObject("en")))
            .or(() -> labels.map(obj -> obj.optJSONObject("de")))
            .or(() -> labels.map(obj -> obj.optJSONObject("fr")))
            .or(() -> labels.map(obj -> obj.optJSONObject("it")))
            .or(() -> labels.map(obj -> obj.optJSONObject("es")))
            .or(() -> labels.map(obj -> obj.optJSONObject(obj.names().optString(0))))
            .map(label -> label.optString("value"))
            .orElse(null);

        var descriptions = Optional.ofNullable(json.optJSONObject("descriptions"));

        var description = descriptions
            .map(obj -> obj.optJSONObject("pl"))
            .or(() -> descriptions.map(obj -> obj.optJSONObject("en")))
            .map(lang -> lang.optString("value"))
            .map(desc -> desc.replace("=", "{{=}}").replace("|", "{{!}}"))
            .orElse(null);

        var claims = Optional.ofNullable(json.optJSONObject("claims"));

        var birthDate = claims
            .map(obj -> obj.optJSONArray("P569"))
            .map(arr -> arr.optJSONObject(0))
            .map(obj -> obj.optJSONObject("mainsnak"))
            .map(mainsnak -> mainsnak.optJSONObject("datavalue"))
            .map(datavalue -> datavalue.optJSONObject("value"))
            .map(MissingWomenBiograms::parseDate)
            .orElse(null);

        var deathDate = claims
            .map(obj -> obj.optJSONArray("P570"))
            .map(arr -> arr.optJSONObject(0))
            .map(obj -> obj.optJSONObject("mainsnak"))
            .map(mainsnak -> mainsnak.optJSONObject("datavalue"))
            .map(datavalue -> datavalue.optJSONObject("value"))
            .map(MissingWomenBiograms::parseDate)
            .orElse(null);

        var picture = claims
            .map(obj -> obj.optJSONArray("P18"))
            .map(arr -> arr.optJSONObject(0))
            .map(obj -> obj.optJSONObject("mainsnak"))
            .map(mainsnak -> mainsnak.optJSONObject("datavalue"))
            .map(datavalue -> datavalue.optString("value"))
            .orElse(null);

        var langlinks = Optional.ofNullable(json.optJSONObject("sitelinks"))
            .map(obj -> obj.keySet().stream()
                .filter(project -> project.endsWith("wiki") && !project.equals("commonswiki") && !project.equals("specieswiki"))
                .sorted()
                .map(project -> String.format("%s:%s",
                    project.replace('_', '-').replace("be-x-old", "be-tarask").replaceFirst("wiki$", ""),
                    obj.getJSONObject(project).getString("title")
                ))
                .collect(Collectors.toCollection(ArrayList::new))
            )
            .filter(list -> !list.isEmpty())
            .orElse(null);

        var entry = new Entry(name, description, birthDate, deathDate, picture, langlinks, item);

        for (var queryItem : QUERY_CONFIG) {
            claims
                .map(obj -> obj.optJSONArray(queryItem.property()))
                .filter(snaks -> StreamSupport.stream(snaks.spliterator(), false)
                    .map(obj -> (JSONObject)obj)
                    .anyMatch(snak -> Optional.ofNullable(snak.optJSONObject("mainsnak"))
                        .map(mainsnak -> mainsnak.optJSONObject("datavalue"))
                        .map(datavalue -> datavalue.optJSONObject("value"))
                        .map(value -> value.optString("id"))
                        .filter(id -> id.equals(queryItem.entity()))
                        .isPresent()
                    )
                )
                .ifPresent(snaks -> entries.computeIfAbsent(queryItem, k -> new ArrayList<>()).add(entry));
        }
    }

    private static TemporalAccessor parseDate(JSONObject value) {
        var time = value.optString("time");
        var precision = value.optInt("precision");

        try {
            if (precision <= 9) {
                return DATE_FORMATTER.parse(time, Year::from);
            } else if (precision == 10) {
                return DATE_FORMATTER.parse(time, YearMonth::from);
            } else {
                return DATE_FORMATTER.parse(time, LocalDate::from);
            }
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private static void writeMainSubpage(Map<QueryItem, List<Entry>> entries) throws IOException, LoginException {
        var text = wb.getPageText(List.of(BOT_SUBPAGE)).get(0);
        text = text.substring(0, text.indexOf("\n----") + 5) + "\n";

        record Stats(String subname, int size) {}

        text += entries.entrySet().stream().collect(Collectors.groupingBy(
                e -> e.getKey().supertype(),
                LinkedHashMap::new,
                Collectors.mapping(
                    e -> new Stats(e.getKey().subtype(), e.getValue().size()),
                    Collectors.toList()
                )
            )).entrySet().stream()
                .map(e -> String.format("; %s%n%s", e.getKey(), e.getValue().stream()
                    .map(stats -> String.format("* [[%1$s/%2$s|%2$s]] ({{formatnum:%3$d}})", BOT_SUBPAGE, stats.subname(), stats.size()))
                    .collect(Collectors.joining("\n"))
                ))
                .collect(Collectors.joining("\n"));

        wb.edit(BOT_SUBPAGE, text, "aktualizacja");
    }

    private static void writeEntrySubpage(QueryItem queryItem, List<Entry> data, Path dumpPath, int originalSize) throws LoginException, IOException {
        var outFmt = """
            <templatestyles src="Wikipedysta:Peter Bowman/autonumber.css" />
            Właściwość: {{PID|%s}}. Wartość: {{QID|%s}}. Rozmiar [%s oryginalnej listy]: {{formatnum:%d}}. Ograniczono do {{formatnum:%d}} wyników.

            Aktualizacja: ~~~~~. Dane na podstawie zrzutu %s.
            ----
            {| class="wikitable sortable autonumber"
            ! artykuł !! opis !! data urodzenia !! data śmierci !! ilustracja !! IW !! inne języki !! item
            |-
            %s
            |}
            """;

        var text = String.format(outFmt,
                queryItem.property().substring(1),
                queryItem.entity().substring(1),
                String.format(STATIC_URL_DUMP_FMT, queryItem.filename()),
                originalSize,
                MAX_PRINTED_RESULTS,
                dumpPath.getFileName(),
                data.stream().map(Entry::toTemplate).collect(Collectors.joining("\n"))
            );

        wb.edit(BOT_SUBPAGE + "/" + queryItem.subtype(), text, "aktualizacja");
    }

    private record QueryItem(String supertype, String subtype, String property, String entity, String filename) {}

    @XStreamAlias("entry")
    private static class Entry {
        final String name;
        final String description;
        final TemporalAccessor birthDate;
        final TemporalAccessor deathDate;
        final String picture;
        final List<String> langlinks;
        final String item;

        @SuppressWarnings("unused")
        Entry() {
            // this exists only for the sake of xstream not complaining about a missing no-args ctor on deserialization
            name = description = picture = item = null;
            birthDate = deathDate = null;
            langlinks = null;
        }

        Entry(String name, String description, TemporalAccessor birthDate, TemporalAccessor deathDate, String picture, List<String> langlinks, String item) {
            this.name = name;
            this.description = description;
            this.birthDate = birthDate;
            this.deathDate = deathDate;
            this.picture = picture;
            this.langlinks = langlinks;
            this.item = item;
        }

        @Override
        public int hashCode() {
            return item.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Entry e) {
                return item.equals(e.item);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("Entry[item=%s, name=%s]", item, name);
        }

        public String toTemplate() {
            return String.format(
                "{{../s|%s|%s|%s|%s|%s|%s|%s|%s}}",
                Optional.ofNullable(name).orElse(""),
                Optional.ofNullable(description).orElse(""),
                Optional.ofNullable(birthDate).map(Object::toString).orElse(""),
                Optional.ofNullable(deathDate).map(Object::toString).orElse(""),
                Optional.ofNullable(picture).orElse(""),
                Optional.ofNullable(langlinks).map(l -> Integer.toString(l.size())).orElse(""),
                Optional.ofNullable(langlinks).map(l -> l.stream()
                        .map(s -> String.format("[[:%s|%s]]", s, s.substring(0, s.indexOf(':'))))
                        .collect(Collectors.joining(" "))
                    ).orElse(""),
                item
            );
        }
    }

    private static abstract class EntryComparator implements Comparator<Entry> {
        private static final Collator PL_COLLATOR = Collator.getInstance(new Locale("pl"));

        static EntryComparator prioritizeLanglinks() {
            return new LanglinksFirst();
        }

        static EntryComparator prioritizeNames() {
            return new NamesFirst();
        }

        protected static int compareLanglinks(Entry e1, Entry e2) {
            if (e1.langlinks != null && e2.langlinks != null) {
                return -Integer.compare(e1.langlinks.size(), e2.langlinks.size());
            } else if (e1.langlinks == null && e2.langlinks != null) {
                return 1;
            } else if (e1.langlinks != null && e2.langlinks == null) {
                return -1;
            } else {
                return 0;
            }
        }

        protected static int compareNames(Entry e1, Entry e2) {
            if (e1.name != null && e2.name != null) {
                return PL_COLLATOR.compare(e1.name, e2.name);
            } else if (e1.name == null && e2.name != null) {
                return 1;
            } else if (e1.name != null && e2.name == null) {
                return -1;
            } else {
                return 0;
            }
        }

        private static class LanglinksFirst extends EntryComparator {
            @Override
            public int compare(Entry e1, Entry e2) {
                var comp = compareLanglinks(e1, e2);

                if (comp == 0) {
                    comp = compareNames(e1, e2);

                    if (comp == 0) {
                        comp = e1.item.compareTo(e2.item);
                    }
                }

                return comp;
            }
        }

        private static class NamesFirst extends EntryComparator {
            @Override
            public int compare(Entry e1, Entry e2) {
                var comp = compareNames(e1, e2);

                if (comp == 0) {
                    comp = compareLanglinks(e1, e2);

                    if (comp == 0) {
                        comp = e1.item.compareTo(e2.item);
                    }
                }

                return comp;
            }
        }
    }
}
