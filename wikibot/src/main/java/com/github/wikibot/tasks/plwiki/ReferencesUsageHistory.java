package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.EnumStats;
import com.github.wikibot.dumps.TimelineCollectors;
import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.dumps.Timeline.Entry;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.utils.Login;

public final class ReferencesUsageHistory {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/ReferencesUsageHistory/");
    private static final String TARGET = "Wikipedysta:PBbot/statystyki artykułów z przypisami";
    private static final DateTimeFormatter YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Wiki wiki = Wiki.newSession("pl.wikipedia.org");

    private static final List<String> DISAMBIG_TEMPLATES = List.of(
        "ujednoznacznienie", "disambig", "strona ujednoznaczniająca"
    );

    private enum REFS {
        OVERALL, HAVE_REFS, HAVE_BIBLIOGRAPHY, HAVE_EXTERNAL_LINKS, HAVE_NOTHING,
        HAVE_REF_TAG, HAVE_REF_TEMPLATE, HAVE_FOOTNOTE_TEMPLATE,
        HAVE_REFERENCES_TAG, HAVE_REFERENCES_TEMPLATE
    }

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var sb = new StringBuilder();

        try (var stream = dump.stream()) {
            System.out.println("start: " + OffsetDateTime.now());

            var timeline = stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .filter(rev -> !rev.isRevisionDeleted())
                .collect(TimelineCollectors.consuming(
                    LocalDate.of(2001, Month.OCTOBER, 1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
                    OffsetDateTime.now(),
                    Period.ofMonths(1),
                    REFS.class,
                    ReferencesUsageHistory::acceptRevision
                ));

            System.out.println("end: " + OffsetDateTime.now());
            System.out.println(timeline);

            sb.append("{| class=\"wikitable\"\n");

            timeline.stream()
                .collect(Collectors.groupingBy(
                    stats -> stats.getTime().getYear(),
                    LinkedHashMap::new,
                    Collectors.toList()
                ))
                .values().stream()
                .forEach(year -> {
                    sb.append("|-\n");
                    sb.append("! miesiąc !! artykuły !! przypisy !! % !! B !! % !! LZ !! % !! nic !! % ");
                    sb.append("!! &lt;ref&gt; !! <nowiki>{{r}}</nowiki> !! <nowiki>{{odn}}</nowiki> !! &lt;references&gt; !! <nowiki>{{przypisy}}</nowiki>\n");

                    year.stream().map(ReferencesUsageHistory::makeYear).forEach(sb::append);
                });

            sb.append("|}");
        }

        var text = makeText(sb.toString(), dump.getDirectoryName());

        Login.login(wiki);

        wiki.edit(TARGET, text, "aktualizacja: " + dump.getDirectoryName());

        Files.writeString(LOCATION.resolve("out.txt"), sb.toString());
        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws ParseException, IOException {
        var dumpConfig = new XMLDumpConfig("plwiki").type(XMLDumpTypes.PAGES_META_HISTORY);

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
                new HelpFormatter().printHelp(ReferencesUsageHistory.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static void acceptRevision(XMLRevision rev, EnumStats<REFS> stats) {
        var text = ParseUtils.removeCommentsAndNoWikiText(rev.getText());

        if (text.contains("__DISAMBIG__")) {
            return;
        }

        for (var template : DISAMBIG_TEMPLATES) {
            if (!ParseUtils.getTemplatesIgnoreCase(template, text).isEmpty()) {
                return;
            }
        }

        var referencesTemplates = ParseUtils.getTemplatesIgnoreCase("przypisy", text);

        if (!referencesTemplates.isEmpty()) {
            stats.increment(REFS.HAVE_REFERENCES_TEMPLATE);

            for (var template : referencesTemplates) {
                text = text.replace(template, "");
            }
        }

        var doc = Jsoup.parseBodyFragment(text);
        var referencesTags = doc.getElementsByTag("references");

        if (!referencesTags.isEmpty()) {
            stats.increment(REFS.HAVE_REFERENCES_TAG);
            referencesTags.forEach(Element::remove);

            text = doc.html();
        }

        var refTemplates = ParseUtils.getTemplatesIgnoreCase("r", text);

        if (!refTemplates.isEmpty()) {
            stats.increment(REFS.HAVE_REF_TEMPLATE);
        }

        var footnoteTemplates = ParseUtils.getTemplatesIgnoreCase("odn", text);

        if (!footnoteTemplates.isEmpty()) {
            stats.increment(REFS.HAVE_FOOTNOTE_TEMPLATE);
        }

        var refTags = doc.getElementsByTag("ref").stream()
            .filter(el -> !el.attr("group").equals("uwaga"))
            .toList();

        if (!refTags.isEmpty()) {
            stats.increment(REFS.HAVE_REF_TAG);
        }

        if (!refTags.isEmpty() || !refTemplates.isEmpty() || !footnoteTemplates.isEmpty()) {
            stats.increment(REFS.HAVE_REFS);
        } else {
            try {
                var page = Page.store(rev.getTitle(), text);

                if (page.hasSectionWithHeader("^Bibliografia$")) {
                    stats.increment(REFS.HAVE_BIBLIOGRAPHY);
                } else if (page.hasSectionWithHeader("^Linki zewnętrzne$")) {
                    stats.increment(REFS.HAVE_EXTERNAL_LINKS);
                } else {
                    stats.increment(REFS.HAVE_NOTHING);
                }
            } catch (ParsingException e) {
                stats.increment(REFS.HAVE_NOTHING);
            }
        }

        stats.increment(REFS.OVERALL);
    }

    private static String makeYear(Entry<EnumStats<REFS>> stats) {
        return String.format(
            "|-\n| %s || {{subst:formatnum:%d}} || " +
            "{{subst:formatnum:%d}} || %.1f%% || {{subst:formatnum:%d}} || %.1f%% || {{subst:formatnum:%d}} || %.1f%% || {{subst:formatnum:%d}} || %.1f%% || " +
            "{{subst:formatnum:%d}} || {{subst:formatnum:%d}} || {{subst:formatnum:%d}} || {{subst:formatnum:%d}} || {{subst:formatnum:%d}}\n",
            stats.getTime().format(YEAR_FORMATTER),
            stats.getValue().get(REFS.OVERALL),
            stats.getValue().get(REFS.HAVE_REFS),
            stats.getValue().get(REFS.HAVE_REFS) * 100.0 / stats.getValue().get(REFS.OVERALL),
            stats.getValue().get(REFS.HAVE_BIBLIOGRAPHY),
            stats.getValue().get(REFS.HAVE_BIBLIOGRAPHY) * 100.0 / stats.getValue().get(REFS.OVERALL),
            stats.getValue().get(REFS.HAVE_EXTERNAL_LINKS),
            stats.getValue().get(REFS.HAVE_EXTERNAL_LINKS) * 100.0 / stats.getValue().get(REFS.OVERALL),
            stats.getValue().get(REFS.HAVE_NOTHING),
            stats.getValue().get(REFS.HAVE_NOTHING) * 100.0 / stats.getValue().get(REFS.OVERALL),
            stats.getValue().get(REFS.HAVE_REF_TAG),
            stats.getValue().get(REFS.HAVE_REF_TEMPLATE),
            stats.getValue().get(REFS.HAVE_FOOTNOTE_TEMPLATE),
            stats.getValue().get(REFS.HAVE_REFERENCES_TAG),
            stats.getValue().get(REFS.HAVE_REFERENCES_TEMPLATE)
        );
    }

    private static String makeText(String table, String date) throws IOException {
        var text = wiki.getPageText(List.of(TARGET)).get(0);
        var TAG = "<!-- START -->";
        var start = text.indexOf(TAG);

        if (start == -1) {
            throw new IllegalStateException("marker not found");
        }

        return text.substring(0, start + TAG.length()) + "\n" +
            String.format("Dane na podstawie %s. Ostatnia aktualizacja: ~~~~~.", date) + "\n\n" +
            table;
    }
}
