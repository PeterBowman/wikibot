package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

public final class ArchiveThread {
    private static final List<String> TARGET_PAGES = List.of(
            "Wikisłownik:Zgłoś błąd w haśle"
        );

    private static final String TARGET_TEMPLATE = "załatwione";

    // from CommentParser::doWikiLinks()
    private static final Pattern P_HEADER_LINK = Pattern.compile("\\[{2}\\s*+:?([^\\[\\]\\|]+)(?:\\|((?:]?[^\\]])*+))?\\]{2}");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\(CES?T\\)");

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm, d LLL yyyy (z)").withLocale(new Locale("pl"));

    private static final String TALK_HEADER_FORMAT = "Przeniesione z [[Specjalna:Niezmienny link/%d#%s|%s]]";

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Plural SUMMARY_FORMATTER;

    static {
        var threads = new WordForms[] {
            new WordForms(new String[] {"wątek", "wątki", "wątków"})
        };

        SUMMARY_FORMATTER = new Plural(PluralRules.POLISH, threads);
    }

    public static void main(String[] args) throws Exception {
        var line = readOptions(args);
        var days = Period.ofDays(Integer.parseInt(line.getOptionValue("days")));

        if (days.isNegative()) {
            throw new IllegalArgumentException("Days must be positive or zero");
        }

        var refTimestamp = OffsetDateTime.now().minus(days);

        System.out.printf("Archiving pages older than %d days%n", days.getDays());
        System.out.printf("Reference date-time: %s%n", refTimestamp);

        Login.login(wb);

        for (var title : TARGET_PAGES) {
            var rev = wb.getTopRevision(title);
            var page = Page.store(title, rev.getText());

            var eligibleSections = page.getAllSections().stream()
                .filter(s -> s.getLevel() == 2)
                .filter(s -> hasDoneTemplate(s.toString()))
                .map(TimedSection::of)
                .filter(Objects::nonNull)
                .filter(ts -> ts.latest().isBefore(refTimestamp))
                .toList();

            for (var section : eligibleSections.stream().map(TimedSection::section).toList()) {
                var targets = P_HEADER_LINK.matcher(section.getHeader()).results()
                    .map(m -> m.group(1).trim())
                    .filter(target -> wb.namespace(target) == Wiki.MAIN_NAMESPACE)
                    .distinct()
                    .toList();

                for (var info : wb.getPageInfo(targets)) {
                    if ((Boolean)info.get("exists")) {
                        var pageName = (String)info.get("pagename");
                        var talkPageName = wb.getTalkPage(pageName);
                        var summary = String.format(TALK_HEADER_FORMAT, rev.getID(), pageName, page.getTitle());

                        wb.newSection(talkPageName, summary, section.getFlattenedContent(), false, true);
                    }
                }
            }

            if (!eligibleSections.isEmpty()) {
                var sectionsPerYear = eligibleSections.stream()
                    .collect(Collectors.groupingBy(as -> as.earliest().getYear()));

                var archiveListPage = String.format("%s/Archiwum", title);
                tryEditArchiveListPage(archiveListPage, sectionsPerYear.keySet());

                for (var entry : sectionsPerYear.entrySet()) {
                    var archiveTitle = String.format("%s/Archiwum/%d", title, entry.getKey());
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
                wb.edit(title, page.toString(), makeSummary(eligibleSections.size()), rev.getTimestamp());
            }
        }
    }

    private static CommandLine readOptions(String[] args) throws ParseException {
        var options = new Options();
        options.addRequiredOption("d", "days", true, "days to consider for archiving (>= 0)");

        if (args.length == 0) {
            System.out.print("Option(s): ");
            args = Misc.readLine().split(" ");
        }

        try {
            return new DefaultParser().parse(options, args, true);
        } catch (ParseException e) {
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
