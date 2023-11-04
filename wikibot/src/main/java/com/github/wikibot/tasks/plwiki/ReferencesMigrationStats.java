package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;

class ReferencesMigrationStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/ReferencesMigrationStats/");
    private static final Path STORED_STATS = LOCATION.resolve("stats.xml");
    private static final String TARGET_STATS = "Wikipedysta:PBbot/statystyki migracji przypisów";
    private static final String TARGET_EMPTY_REFS = "Wikipedysta:PBbot/niewykorzystane grupowanie przypisów";
    private static final Wiki wiki = Wiki.newSession("pl.wikipedia.org");
    private static final XStream xstream = new XStream();

    private static final String EMPTY_REFS_TEMPLATE = """
        Artykuły, w których użyto &lt;references&gt; lub {{s|przypisy}}, lecz w treści nie znaleziono odsyłaczy &lt;ref&gt;, {{s|r}}, {{s|odn}}, {{s|refn}}
        ani szablonów, które automatycznie dodają przypisy ({{s|zwierzę infobox}}).

        Dane na podstawie zrzutu %s. Aktualizacja: ~~~~~.
        ----
        """;

    static {
        xstream.allowTypes(new Class[]{Stats.class});
    }

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var stats = new Stats();
        var emptyRefs = new ArrayList<String>(1000);

        try (var stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .forEach(rev -> analyze(rev, stats, emptyRefs));
        }

        stats.printResults();

        Login.login(wiki);
        edit(stats, retrieveStats(), emptyRefs, dump);

        Files.writeString(STORED_STATS, xstream.toXML(stats));
        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws ParseException, IOException {
        var dumpConfig = new XMLDumpConfig("plwiki").type(XMLDumpTypes.PAGES_ARTICLES_RECOMBINE);

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
                new HelpFormatter().printHelp(ReferencesMigrationStats.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static void analyze(XMLRevision rev, Stats stats, List<String> emptyRefs) {
        var text = ParseUtils.removeCommentsAndNoWikiText(rev.getText());
        var title = rev.getTitle();
        var hasGroupingElement = false;
        var hasReferenceInBody = false;

        var referencesTemplate = ParseUtils.getTemplatesIgnoreCase("przypisy", text).stream().findAny();

        if (referencesTemplate.isPresent()) {
            referencesTemplate
                // don't query first unnamed param, the parser goes bananas on the first '=' at <ref name="bla">
                .map(Jsoup::parseBodyFragment)
                .filter(doc -> !doc.getElementsByTag("ref").isEmpty())
                .ifPresentOrElse(doc -> stats.haveGroupedReferencesTemplate++, () -> stats.haveUngroupedReferencesTemplate++);

            text = text.replace(referencesTemplate.get(), "");
            hasGroupingElement = true;
        }

        var doc = Jsoup.parseBodyFragment(text);
        var referencesTag = doc.getElementsByTag("references").stream().findAny();

        if (referencesTag.isPresent()) {
            referencesTag
                .filter(el -> el.hasAttr("responsive"))
                .ifPresentOrElse(el -> stats.haveReferencesResponsiveTag++, () -> stats.haveReferencesTag++);

            referencesTag.get().remove();
            text = doc.html();
            hasGroupingElement = true;
        }

        if (!doc.getElementsByTag("ref").isEmpty()) {
            stats.haveRefTag++;
            hasReferenceInBody = true;
        }

        if (!ParseUtils.getTemplatesIgnoreCase("r", text).isEmpty()) {
            stats.haveRefTemplate++;
            hasReferenceInBody = true;
        }

        for (var template : List.of("odn", "refn", "zwierzę infobox")) {
            if (!ParseUtils.getTemplatesIgnoreCase(template, text).isEmpty()) {
                hasReferenceInBody = true;
            }
        }

        if (hasGroupingElement && !hasReferenceInBody) {
            emptyRefs.add(title);
        }

        stats.overall++;
    }

    private static Stats retrieveStats() {
        try {
            return (Stats)xstream.fromXML(Files.readString(STORED_STATS));
        } catch (IOException | XStreamException | ClassCastException e) {
            System.out.println(e.getMessage());
            return null;
        }
    }

    private static void edit(Stats newstats, Stats oldStats, List<String> emptyRefs, XMLDump dump) throws IOException, LoginException {
        var text = wiki.getPageText(List.of(TARGET_STATS)).get(0);
        var marker = "<!-- BOTTOM -->";

        if (!text.contains(marker)) {
            throw new IllegalStateException("marker not found");
        }

        var index = text.indexOf(marker);
        var newText = text.substring(0, index) + newstats.makeRow(dump.getDirectoryName(), oldStats) + text.substring(index);

        wiki.edit(TARGET_STATS, newText, "aktualizacja: " + dump.getDescriptiveFilename());

        var emptyRefsText = String.format(EMPTY_REFS_TEMPLATE, dump.getDescriptiveFilename()) + emptyRefs.stream()
            .sorted(Collator.getInstance(Locale.forLanguageTag("pl")))
            .map(title -> String.format("#[[%s#Przypisy]]", title))
            .collect(Collectors.joining("\n"));

        wiki.edit(TARGET_EMPTY_REFS, emptyRefsText, "aktualizacja: " + dump.getDescriptiveFilename());
    }

    private static class Stats {
        int overall;
        int haveRefTag;
        int haveRefTemplate;
        int haveUngroupedReferencesTemplate;
        int haveGroupedReferencesTemplate;
        int haveReferencesTag;
        int haveReferencesResponsiveTag;

        void printResults() {
            System.out.printf("""
                Results:
                overall: %d
                have <ref>: %d
                have {{r}}: %d
                have ungrouped {{przypisy}}: %d
                have grouped {{przypisy}}: %d
                have <references>: %d
                have <references responsive>: %d
                """,
                overall, haveRefTag, haveRefTemplate, haveUngroupedReferencesTemplate,
                haveGroupedReferencesTemplate, haveReferencesTag, haveReferencesResponsiveTag);
        }

        String makeRow(String date, Stats oldStats) {
            var opt = Optional.ofNullable(oldStats);

            return String.format(
                "|-%n| %s || %s || %s || %s || %s || %s || %s || %s%n",
                date,
                makeCell(overall, opt.map(o -> o.overall)),
                makeCell(haveRefTag, opt.map(o -> o.haveRefTag)),
                makeCell(haveRefTemplate, opt.map(o -> o.haveRefTemplate)),
                makeCell(haveUngroupedReferencesTemplate, opt.map(o -> o.haveUngroupedReferencesTemplate)),
                makeCell(haveGroupedReferencesTemplate, opt.map(o -> o.haveGroupedReferencesTemplate)),
                makeCell(haveReferencesTag, opt.map(o -> o.haveReferencesTag)),
                makeCell(haveReferencesResponsiveTag, opt.map(o -> o.haveReferencesResponsiveTag))
            );
        }

        private static String makeCell(int newValue, Optional<Integer> oldValue) {
            var out = String.format("{{formatnum:%d}}", newValue);

            if (oldValue.isPresent()) {
                var diff = newValue - oldValue.get();
                out += "<br>";

                if (diff >= 0) {
                    out += String.format("(+%d)", diff);
                } else {
                    out += String.format("(%d)", diff);
                }
            }

            return out;
        }
    }
}
