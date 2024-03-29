package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

public final class MissingPolishExamples {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishExamples/");
    private static final Pattern P_LINKER = Pattern.compile("\\[\\[\\s*?([^\\]\\|]+)\\s*?(?:\\|\\s*?((?:]?[^\\]\\|])*+))*\\s*?\\]\\]([^\\[]*)", Pattern.DOTALL);
    private static final Pattern P_REF = Pattern.compile("<\\s*ref\\b", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var timestamp = extractTimestamp(dump.getDirectoryName());

        final Set<String> titles;

        try (var stream = dump.stream()) {
            titles = stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getPolishSection().stream())
                .flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
                .filter(Field::isEmpty)
                .map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
                .collect(Collectors.toSet());
        }

        System.out.printf("%d titles retrieved\n", titles.size());
        var titlesToBacklinks = new HashMap<String, Set<Backlink>>();

        try (var stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getAllSections().stream())
                .flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
                .filter(f -> !f.isEmpty())
                .forEach(f -> Pattern.compile("\n").splitAsStream(f.getContent())
                    .filter(line -> f.getContainingSection().get().isPolishSection()
                        || (line.contains("→") && !P_REF.matcher(line).find()))
                    .map(line -> line.substring(line.indexOf('→') + 1))
                    .flatMap(line -> P_LINKER.matcher(line).results())
                    .map(m -> m.group(1))
                    .filter(titles::contains)
                    .forEach(target -> titlesToBacklinks.computeIfAbsent(target, k -> new TreeSet<>())
                        .add(Backlink.makeBacklink(
                            f.getContainingSection().get().getContainingPage().get().getTitle(),
                            f.getContainingSection().get()
                        ))
                    )
                );
        }

        System.out.printf("%d titles mapped to backlinks\n", titlesToBacklinks.size());

        var list = titlesToBacklinks.entrySet().stream()
            .map(e -> Entry.makeEntry(e.getKey(), new ArrayList<>(e.getValue())))
            .collect(Collectors.toList());

        storeData(list, timestamp);
        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws IOException, org.apache.commons.cli.ParseException {
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
                new HelpFormatter().printHelp(MissingPolishExamples.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static LocalDate extractTimestamp(String canonicalTimestamp) throws ParseException {
        try {
            var originalDateFormat = new SimpleDateFormat("yyyyMMdd");
            var date = originalDateFormat.parse(canonicalTimestamp);
            return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC);
        } catch (ParseException e) {
            throw e;
        }
    }

    private static void storeData(List<Entry> list, LocalDate timestamp) throws IOException {
        var fEntries = LOCATION.resolve("entries.xml");
        var fDumpTimestamp = LOCATION.resolve("dump-timestamp.xml");
        var fBotTimestamp = LOCATION.resolve("bot-timestamp.xml");
        var fCtrl = LOCATION.resolve("UPDATED");

        var xstream = new XStream(new StaxDriver());
        xstream.processAnnotations(Entry.class);

        try (var bos = new BufferedOutputStream(Files.newOutputStream(fEntries))) {
            xstream.toXML(list, bos);
        }

        Files.write(fDumpTimestamp, List.of(xstream.toXML(timestamp)));
        Files.write(fBotTimestamp, List.of(xstream.toXML(OffsetDateTime.now())));

        Files.deleteIfExists(fCtrl);
    }

    // keep in sync with com.github.wikibot.webapp.MissingPolishExamples
    @XStreamAlias("entry")
    static class Entry {
        @XStreamAlias("t")
        String title;

        @XStreamAlias("blt")
        List<String> backlinkTitles = new ArrayList<>();

        @XStreamAlias("bls")
        List<String> backlinkSections = new ArrayList<>();

        public static Entry makeEntry(String title, List<Backlink> backlinks) {
            var entry = new Entry();
            entry.title = title;

            backlinks.forEach(bl -> {
                entry.backlinkTitles.add(bl.title);
                entry.backlinkSections.add(bl.langLong);
            });

            return entry;
        }
    }

    // keep in sync with com.github.wikibot.webapp.MissingPolishExamples
    @XStreamAlias("bl")
    static class Backlink implements Comparable<Backlink> {
        @XStreamAlias("t")
        String title;

        @XStreamAlias("ls")
        String langShort;

        @XStreamAlias("ll")
        String langLong;

        public static Backlink makeBacklink(String title, Section section) {
            var bl = new Backlink();
            bl.title = title;
            bl.langShort = section.getLangShort();
            bl.langLong = section.getLang();
            return bl;
        }

        @Override
        public int compareTo(Backlink o) {
            if (!title.equals(o.title)) {
                return title.compareTo(o.title);
            }

            if (!langShort.equals(o.langShort)) {
                return langShort.compareTo(o.langShort);
            }

            return 0;
        }
    }
}
