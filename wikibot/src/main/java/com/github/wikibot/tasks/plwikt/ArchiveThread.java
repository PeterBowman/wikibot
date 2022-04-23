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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
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

    // from CommentParser::doWikiLinks()
    private static final Pattern P_HEADER_LINK = Pattern.compile("\\[{2}\\s*+:?([^\\[\\]\\|]+)(?:\\|((?:]?[^\\]])*+))?\\]{2}");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\(CES?T\\)");

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm, d LLL yyyy (z)").withLocale(new Locale("pl"));

    private static final String TALK_HEADER_FORMAT = "Przeniesione z [[Specjalna:Niezmienny link/%d#%s|%s]]";

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    private static final Plural SUMMARY_FORMATTER = new Plural(PluralRules.POLISH, new WordForms[] {
        new WordForms(new String[] {"wątek", "wątki", "wątków"})
    });

    public static void main(String[] args) throws Exception {
        var line = readOptions(args);
        var daysOverride = Optional.ofNullable(line.getOptionValue("days")).map(Integer::parseInt);
        var json = wb.getPageText(List.of(CONFIG_PAGE)).get(0);
        var configs = parseArchiveConfig(new JSONObject(json));

        configs.forEach(System.out::println);
        Login.login(wb);

        for (var config : configs) {
            System.out.println(config);

            var days = daysOverride.orElse(config.minDays());
            var refTimestamp = OffsetDateTime.now().minusDays(days);
            System.out.printf("Reference date-time: %s%n", refTimestamp);

            var rev = wb.getTopRevision(config.pagename());
            var page = Page.store(config.pagename(), rev.getText());

            var eligibleSections = page.getAllSections().stream()
                .filter(s -> s.getLevel() == 2)
                .filter(s -> hasDoneTemplate(s.toString()))
                .map(TimedSection::of)
                .filter(Objects::nonNull)
                .filter(ts -> ts.latest().isBefore(refTimestamp))
                .toList();

            if (!eligibleSections.isEmpty()) {
                if (config.honorLinksInHeader()) {
                    processLinksInHeader(config, rev, eligibleSections);
                }

                var sectionsPerYear = eligibleSections.stream()
                    .collect(Collectors.groupingBy(as -> as.earliest().getYear()));

                var archiveListPage = String.format("%s%s", config.pagename(), config.subpage());
                tryEditArchiveListPage(archiveListPage, sectionsPerYear.keySet());

                for (var entry : sectionsPerYear.entrySet()) {
                    var archiveTitle = String.format("%s%s/%d", config.pagename(), config.subpage(), entry.getKey());
                    var archiveText = Optional.ofNullable(wb.getPageText(List.of(archiveTitle)).get(0)).orElse("");
                    var archive = Page.store(archiveTitle, archiveText);
                    var sectionsToAppend = entry.getValue().stream().map(TimedSection::section).toList();

                    archive.appendSections(sectionsToAppend);

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

                    wb.edit(archiveTitle, archive.toString(), makeSummary(sectionsToAppend.size()));
                }

                eligibleSections.stream().map(TimedSection::section).forEach(Section::detach);
                wb.edit(config.pagename(), page.toString(), makeSummary(eligibleSections.size()), rev.getTimestamp());
            } else {
                System.out.println("No eligible sections found for page: " + config.pagename());
            }
        }
    }

    private static List<ArchiveConfig> parseArchiveConfig(JSONObject json) {
        var config = new ArrayList<ArchiveConfig>();

        var minDaysGlobal = json.getInt("minDays");
        var subpageGlobal = json.getString("subpage");

        for (var obj : json.getJSONArray("entries")) {
            var entry = (JSONObject)obj;
            var pagename = entry.getString("pagename");
            var minDays = entry.optInt("minDays", minDaysGlobal);
            var subpage = entry.optString("subpage", subpageGlobal);
            var honorLinksInHeader = entry.optBoolean("honorLinksInHeader");

            if (minDays < 0) {
                throw new IllegalArgumentException("minDays must be positive or zero: " + minDays);
            }

            if (!subpage.startsWith("/") || subpage.length() == 1) {
                throw new IllegalArgumentException("subpage must be a string prefixed with '/'': " + subpage);
            }

            config.add(new ArchiveConfig(pagename, minDays, subpage, honorLinksInHeader));
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

    private static void processLinksInHeader(ArchiveConfig config, Wiki.Revision rev, List<TimedSection> sections) throws LoginException, IOException {
        for (var section : sections.stream().map(TimedSection::section).toList()) {
            var targets = P_HEADER_LINK.matcher(section.getHeader()).results()
                .map(m -> m.group(1).trim())
                .filter(target -> wb.namespace(target) == Wiki.MAIN_NAMESPACE)
                .distinct()
                .toList();

            for (var info : wb.getPageInfo(targets)) {
                if ((Boolean)info.get("exists")) {
                    var pageName = (String)info.get("pagename");
                    var talkPageName = wb.getTalkPage(pageName);
                    var summary = String.format(TALK_HEADER_FORMAT, rev.getID(), pageName, rev.getTitle());

                    wb.newSection(talkPageName, summary, section.getFlattenedContent(), false, true);
                }
            }
        }
    }

    private static String makeSummary(int numThreads) {
        return String.format("zarchiwizowano %d %s", numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek"));
    }

    private static void tryEditArchiveListPage(String pagename, Set<Integer> years) throws IOException, LoginException {
        var text = wb.getPageText(List.of(pagename)).get(0);

        if (text == null) {
            text = String.format("""
                Archiwum strony [[%s]] z podziałem na lata wg daty zgłoszenia.

                <!-- START (nie zmieniaj tej linii ani poniższych aż do następnego znacznika) -->
                <!-- END (nie zmieniaj tej linii ani powyższych aż do poprzedniego znacznika) -->

                [[Kategoria:Archiwum Wikisłownika|%s]]
                """, pagename, wb.removeNamespace(pagename));
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

        var yearsListed = Pattern.compile("^\\* \\[{2}/(\\d+)\\]{2}$", Pattern.MULTILINE).matcher(substring).results()
            .map(mr -> mr.group(1))
            .map(Integer::parseInt)
            .collect(Collectors.toCollection(TreeSet::new));

        if (yearsListed.addAll(years)) {
            var newList = yearsListed.stream()
                .map(year -> String.format("* [[/%d]]", year))
                .collect(Collectors.joining("\n"));

            var newText = String.format("%s\n%s\n%s", text.substring(0, start), newList, text.substring(end));
            wb.edit(pagename, newText, "uzupełnienie listy podstron archiwum");
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

    private record ArchiveConfig(String pagename, int minDays, String subpage, boolean honorLinksInHeader) {}

    private record TimedSection(Section section, OffsetDateTime earliest, OffsetDateTime latest) {
        public static TimedSection of(Section section) {
            var timestamps = retrieveTimestamps(section.toString());

            if (!timestamps.isEmpty()) {
                return new TimedSection(section, timestamps.first(), timestamps.last());
            } else {
                return null;
            }
        }
    }
}
