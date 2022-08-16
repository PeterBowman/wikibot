package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;

class ReferencesMigrationStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/ReferencesMigrationStats/");
    private static final Path STORED_STATS = LOCATION.resolve("stats.xml");
    private static final String TARGET = "Wikipedysta:PBbot/statystyki migracji przypisów";
    private static final Wiki wiki = Wiki.newSession("pl.wikipedia.org");
    private static final XStream xstream = new XStream();

    static {
        xstream.allowTypes(new Class[]{Stats.class});
    }

    public static void main(String[] args) throws Exception {
        var reader = getDumpReader(args);
        var stats = new Stats();

        try (var stream = reader.getStAXReaderStream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .forEach(rev -> analyze(rev.getText(), stats));
        }

        stats.printResults();
        Login.login(wiki);
        edit(stats, retrieveStats(), reader.getPathToDump().getFileName().toString());
        Files.writeString(STORED_STATS, xstream.toXML(stats));
    }

    private static XMLDumpReader getDumpReader(String[] args) throws IOException {
        if (args.length == 0) {
            return new XMLDumpReader("plwiki");
        } else {
            return new XMLDumpReader(Paths.get(args[0].trim()));
        }
    }

    private static void analyze(String text, Stats stats) {
        text = ParseUtils.removeCommentsAndNoWikiText(text);

        var referencesTemplate = ParseUtils.getTemplatesIgnoreCase("przypisy", text).stream().findAny();

        if (referencesTemplate.isPresent()) {
            referencesTemplate
                // don't query first unnamed param, the parser goes bananas on the first '=' at <ref name="bla">
                .map(Jsoup::parseBodyFragment)
                .filter(doc -> !doc.getElementsByTag("ref").isEmpty())
                .ifPresentOrElse(doc -> stats.haveGroupedReferencesTemplate++, () -> stats.haveUngroupedReferencesTemplate++);

            text = text.replace(referencesTemplate.get(), "");
        }

        var doc = Jsoup.parseBodyFragment(text);
        var referencesTag = doc.getElementsByTag("references").stream().findAny();

        if (referencesTag.isPresent()) {
            referencesTag
                .filter(el -> el.hasAttr("responsive"))
                .ifPresentOrElse(el -> stats.haveReferencesResponsiveTag++, () -> stats.haveReferencesTag++);

            referencesTag.get().remove();
            text = doc.html();
        }

        if (!doc.getElementsByTag("ref").isEmpty()) {
            stats.haveRefTag++;
        }

        if (!ParseUtils.getTemplatesIgnoreCase("r", text).isEmpty()) {
            stats.haveRefTemplate++;
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

    private static void edit(Stats newstats, Stats oldStats, String dumpFilename) throws IOException, LoginException {
        var text = wiki.getPageText(List.of(TARGET)).get(0);
        var marker = "<!-- BOTTOM -->";

        if (!text.contains(marker)) {
            throw new IllegalStateException("marker not found");
        }

        var date = Pattern.compile("^plwiki-(\\d+)-pages-articles\\.xml(?:\\.bz2)?$").matcher(dumpFilename).results()
            .map(mr -> mr.group(1))
            .findAny()
            .orElseThrow();

        var index = text.indexOf(marker);
        var newText = text.substring(0, index) + newstats.makeRow(date, oldStats) + text.substring(index);

        wiki.edit(TARGET, newText, "aktualizacja: " + dumpFilename);
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
