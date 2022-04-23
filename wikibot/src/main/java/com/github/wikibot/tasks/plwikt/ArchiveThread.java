package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PluralRules;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

public final class ArchiveThread {
    private static final String CONFIG_PAGE = "Wikisłownik:Archiwizacja.json";
    private static final String TARGET_TEMPLATE = "załatwione";
    private static final String CHANGE_TAG = "archive-threads";

    // from CommentParser::doWikiLinks()
    private static final Pattern P_HEADER_LINK = Pattern.compile("\\[{2}\\s*+:?([^\\[\\]\\|]+)(?:\\|((?:]?[^\\]])*+))?\\]{2}");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\(CES?T\\)");

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm, d LLL yyyy (z)").withLocale(new Locale("pl"));

    private static final Plural SUMMARY_FORMATTER = new Plural(PluralRules.POLISH, new WordForms[] {
        new WordForms(new String[] {"wątek", "wątki", "wątków"})
    });

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        var line = readOptions(args);
        var daysOverride = Optional.ofNullable(line.getOptionValue("days")).map(Integer::parseInt);
        var json = wb.getPageText(List.of(CONFIG_PAGE)).get(0);
        var configs = parseArchiveConfig(new JSONObject(json));
        var now = OffsetDateTime.now();

        configs.forEach(System.out::println);
        Login.login(wb);

        for (var config : configs) {
            System.out.println(config);

            var days = daysOverride.orElse(config.minDays());
            var refTimestamp = now.minusDays(days);
            System.out.printf("Reference date-time: %s%n", refTimestamp);

            var rev = wb.getTopRevision(config.pagename());
            var page = Page.store(config.pagename(), rev.getText());

            var sectionToTimestamps = page.getAllSections().stream()
                .filter(s -> s.getLevel() == 2)
                .filter(s -> !config.triggerOnTemplate() || hasDoneTemplate(s.toString()))
                .collect(Collectors.toMap(
                    UnaryOperator.identity(),
                    s -> retrieveTimestamps(s.toString())
                ));

            sectionToTimestamps.values().removeIf(Set::isEmpty);
            sectionToTimestamps.values().removeIf(ts -> ts.last().isAfter(refTimestamp));

            if (!sectionToTimestamps.isEmpty()) {
                var usingAdditionalTargets = false;

                if (config.honorLinksInHeader()) {
                    usingAdditionalTargets = processLinksInHeader(config, rev.getID(), sectionToTimestamps.keySet());
                }

                var sectionsPerYear = sectionToTimestamps.entrySet().stream().collect(Collectors.groupingBy(
                    e -> e.getValue().first().getYear(),
                    TreeMap::new,
                    Collectors.mapping(e -> e.getKey(), Collectors.toList())
                ));

                tryEditArchiveListPage(config, sectionsPerYear.keySet());

                for (var entry : sectionsPerYear.entrySet()) {
                    editArchiveSubpage(config, rev.getID(), entry.getKey(), entry.getValue());
                }

                sectionToTimestamps.keySet().forEach(Section::detach);

                var summary = makeSummaryTo(sectionToTimestamps.size(), config, sectionsPerYear.navigableKeySet(), usingAdditionalTargets);
                wb.edit(config.pagename(), page.toString(), summary, false, true, -2, List.of(CHANGE_TAG), rev.getTimestamp());
            } else {
                System.out.println("No eligible sections found for " + config.pagename());
            }
        }
    }

    private static List<ArchiveConfig> parseArchiveConfig(JSONObject json) {
        var config = new ArrayList<ArchiveConfig>();

        var minDaysGlobal = json.getInt("minDays");
        var subpageGlobal = json.getString("subpage");
        var triggerGlobal = json.getBoolean("triggerOnTemplate");

        for (var obj : json.getJSONArray("entries")) {
            var entry = (JSONObject)obj;
            var pagename = entry.getString("pagename");
            var minDays = entry.optInt("minDays", minDaysGlobal);
            var subpage = entry.optString("subpage", subpageGlobal);
            var trigger = entry.optBoolean("triggerOnTemplate", triggerGlobal);
            var honorLinksInHeader = entry.optBoolean("honorLinksInHeader");

            if (minDays < 0) {
                throw new IllegalArgumentException("minDays must be positive or zero: " + minDays);
            }

            if (subpage.isEmpty()) {
                throw new IllegalArgumentException("subpage must be a non-empty string: " + subpage);
            }

            config.add(new ArchiveConfig(pagename, minDays, subpage, trigger, honorLinksInHeader));
        }

        return Collections.unmodifiableList(config);
    }

    private static CommandLine readOptions(String[] args) throws ParseException {
        var options = new Options();
        options.addOption("d", "days", true, "days to consider for archiving (>= 0)");

        if (args.length == 0) {
            System.out.print("Option(s): ");
            args = Misc.readLine().split(" ");
        }

        try {
            var cli = new DefaultParser().parse(options, args, true);

            if (Integer.parseInt(cli.getOptionValue("days", "0")) < 0) {
                throw new ParseException("days must be positive or zero");
            }

            return cli;
        } catch (ParseException | NumberFormatException e) {
            new HelpFormatter().printHelp(ArchiveThread.class.getName(), options);
            throw e;
        }
    }

    private static boolean hasDoneTemplate(String text) {
        var doc = Jsoup.parseBodyFragment(ParseUtils.removeCommentsAndNoWikiText(text));
        doc.getElementsByTag("nowiki").remove(); // already removed along with comments, but why not
        doc.getElementsByTag("s").remove();
        doc.getElementsByTag("pre").remove();
        doc.getElementsByTag("syntaxhighlight").remove();
        doc.getElementsByTag("source").remove();

        return ParseUtils.getTemplates(TARGET_TEMPLATE, doc.body().text()).stream()
            .map(ParseUtils::getTemplateParametersWithValue)
            .map(params -> params.getOrDefault("ParamWithoutName1", ""))
            .anyMatch(param -> !param.equals("-"));
    }

    private static boolean processLinksInHeader(ArchiveConfig config, long revid, Set<Section> sections) throws LoginException, IOException {
        var edited = false;

        for (var section : sections) {
            var targets = P_HEADER_LINK.matcher(section.getHeader()).results()
                .map(m -> m.group(1).strip())
                .filter(target -> wb.namespace(target) == Wiki.MAIN_NAMESPACE)
                .distinct()
                .toList();

            for (var info : wb.getPageInfo(targets)) {
                if ((Boolean)info.get("exists")) {
                    var pageName = (String)info.get("pagename");
                    var talkPageName = wb.getTalkPage(pageName);
                    var summary = String.format("Przeniesione z [[Specjalna:Niezmienny link/%d|%s]]", revid, config.pagename());

                    wb.edit(talkPageName, summary, section.getFlattenedContent(), false, true, -1, List.of(CHANGE_TAG), null);
                    edited = true;
                }
            }
        }

        return edited;
    }

    private static String makeSummaryFrom(int numThreads, String pagename, long id) {
        return String.format(
            "zarchiwizowano %d %s z [[Specjalna:Niezmienny link/%d|%s]]",
            numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek"), id, pagename
        );
    }

    private static String makeSummaryTo(int numThreads, ArchiveConfig config, SortedSet<Integer> subpages, boolean usingAdditionalTargets) {
        var targets = String.format("[[%s/%s/%d]]", config.pagename(), config.subpage(), subpages.first());

        if (subpages.size() > 1) {
            targets += ", " + subpages.tailSet(subpages.first() + 1).stream()
                .map(subpage -> String.format("[[%1$s/%2$s/%3$d|/%3$d]]", config.pagename(), config.subpage(), subpage))
                .collect(Collectors.joining(", "));
        }

        if (usingAdditionalTargets) {
            targets += " i in.";
        }

        return String.format("zarchiwizowano %d %s w %s", numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek"), targets);
    }

    private static void editArchiveSubpage(ArchiveConfig config, long revid, int year, List<Section> sections) throws IOException, LoginException {
        var pagename = String.format("%s/%s/%d", config.pagename(), config.subpage(), year);
        var rev = wb.getTopRevision(pagename);
        var text = rev != null ? rev.getText() : "";
        var timestamp = rev != null ? rev.getTimestamp() : null;
        var archive = Page.store(pagename, text);

        archive.appendSections(sections);

        var earliestTimestampPerSection = archive.getAllSections().stream()
            .collect(Collectors.toMap(
                UnaryOperator.identity(),
                s -> Optional.of(retrieveTimestamps(s.toString()))
                    .filter(timestamps -> !timestamps.isEmpty())
                    .map(SortedSet::first)
                    .map(OffsetDateTime::toEpochSecond)
                    .orElse(0L)
            ));

        archive.sortSections((s1, s2) -> Long.compare(
            earliestTimestampPerSection.getOrDefault(s1, 0L),
            earliestTimestampPerSection.getOrDefault(s2, 0L)
        ));

        var summary = makeSummaryFrom(sections.size(), config.pagename(), revid);
        wb.edit(pagename, archive.toString(), summary, false, true, -2, List.of(CHANGE_TAG), timestamp);
    }

    private static void tryEditArchiveListPage(ArchiveConfig config, Set<Integer> years) throws IOException, LoginException {
        var pagename = String.format("%s/%s", config.pagename(), config.subpage());
        var rev = wb.getTopRevision(pagename);
        var basetime = Optional.ofNullable(rev).map(Wiki.Revision::getTimestamp).orElse(null);

        final String text;

        if (rev == null) {
            text = String.format("""
                Archiwum strony [[%s]] z podziałem na lata wg daty zgłoszenia.

                <!-- START (nie zmieniaj tej linii ani poniższych aż do następnego znacznika) -->
                <!-- END (nie zmieniaj tej linii ani powyższych aż do poprzedniego znacznika) -->

                [[Kategoria:Archiwum Wikisłownika|%s]]
                """, pagename, wb.removeNamespace(pagename));
        } else {
            text = rev.getText();
        }

        var start = text.indexOf("<!-- START ");
        var end = text.indexOf("<!-- END ");

        if (start == -1 || end == -1 || start > end) {
            System.out.println("Unable to process archive list page: " + pagename);
            return;
        } else {
            start = text.indexOf("\n", start);
        }

        var substring = text.substring(start, end).strip();
        var pattern = Pattern.compile(String.format("^\\* \\[{2}%s/(\\d+)\\|\\1\\]{2}$", Pattern.quote(pagename)), Pattern.MULTILINE);

        var yearsListed = pattern.matcher(substring).results()
            .map(mr -> mr.group(1))
            .map(Integer::parseInt)
            .collect(Collectors.toCollection(TreeSet::new));

        if (yearsListed.addAll(years)) {
            var newList = yearsListed.stream()
                .map(year -> String.format("* [[%1$s/%2$d|%2$d]]", pagename, year))
                .collect(Collectors.joining("\n"));

            var newText = String.format("%s\n%s\n%s", text.substring(0, start), newList, text.substring(end));
            wb.edit(pagename, newText, "uzupełnienie listy podstron archiwum", false, true, -2, List.of(CHANGE_TAG), basetime);
        }
    }

    private static SortedSet<OffsetDateTime> retrieveTimestamps(String text) {
        return P_TIMESTAMP.matcher(ParseUtils.removeCommentsAndNoWikiText(text)).results()
            .map(MatchResult::group)
            .<ZonedDateTime>mapMulti((timestamp, consumer) -> {
                try {
                    consumer.accept(ZonedDateTime.parse(timestamp, DT_FORMATTER));
                } catch (DateTimeParseException e) {}
            })
            .map(ZonedDateTime::toOffsetDateTime)
            .filter(dt -> dt.getYear() >= 2004 && !dt.isAfter(OffsetDateTime.now())) // sanity check
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private record ArchiveConfig(String pagename, int minDays, String subpage, boolean triggerOnTemplate, boolean honorLinksInHeader) {}
}
