package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import com.github.wikibot.parsing.AbstractSection;
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

public final class ArchiveThreads {
    private static final String CONFIG_PAGE = "Wikisłownik:Archiwizacja.json";
    private static final String TARGET_TEMPLATE = "załatwione";
    private static final String CHANGE_TAG = "archive-threads";

    // from CommentParser::doWikiLinks()
    private static final Pattern P_HEADER_LINK = Pattern.compile("\\[{2}\\s*+:?([^\\[\\]\\|]+)(?:\\|((?:]?[^\\]])*+))?\\]{2}");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\((?:UTC|CES?T)\\)");

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

        configs.forEach(System.out::println);
        Login.login(wb);

        record Error(ArchiveConfig config, Throwable err) {}
        var errors = new ArrayList<Error>();

        for (var config : configs) {
            System.out.println(config);

            try {
                processEntry(config, daysOverride);
            } catch (Throwable t) {
                t.printStackTrace();
                errors.add(new Error(config, t));
            }
        }

        if (!errors.isEmpty()) {
            System.out.printf("Got %d errors:%n", errors.size());

            errors.stream()
                .map(e -> String.format("%s: %s", e.config().pagename(), e.err().getMessage()))
                .forEach(System.out::println);

            var pagenames = errors.stream().map(e -> e.config().pagename()).toList();
            throw new RuntimeException(pagenames.size() + " error(s): " + pagenames);
        }
    }

    private static void processEntry(ArchiveConfig config, Optional<Integer> daysOverride) throws Exception {
        var days = daysOverride.orElse(config.minDays());
        var refTimestamp = OffsetDateTime.now().minusDays(days);
        System.out.printf("Reference date-time: %s%n", refTimestamp);

        var rev = wb.getTopRevision(config.pagename());
        var page = Page.store(config.pagename(), rev.getText());

        var sectionInfos = page.getAllSections().stream()
            .filter(s -> s.getLevel() == config.sectionLevel())
            .map(SectionInfo::of)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ArrayList::new));

        sectionInfos.removeIf(si -> config.triggerOnTemplate() && si.type().contains(ArchiveType.NONE));
        sectionInfos.removeIf(si -> si.latest().isAfter(refTimestamp));

        if (!sectionInfos.isEmpty()) {
            var usingAdditionalTargets = false;

            for (var sectionInfo : sectionInfos) {
                usingAdditionalTargets |= processAdditionalTargets(config, rev.getID(), sectionInfo);
            }

            var sectionsPerYear = sectionInfos.stream()
                .filter(si -> !config.triggerOnTemplate() || !si.type().contains(ArchiveType.MOVE))
                .collect(Collectors.groupingBy(
                    si -> si.earliest().getYear(),
                    TreeMap::new,
                    Collectors.mapping(SectionInfo::section, Collectors.toList())
                ));

            // important: first detach (this alters the Section instances), then append to archive subpage
            sectionInfos.stream().map(SectionInfo::section).forEach(Section::detach);

            if (!sectionsPerYear.isEmpty()) {
                tryEditArchiveListPage(config, sectionsPerYear.keySet());

                for (var entry : sectionsPerYear.entrySet()) {
                    editArchiveSubpage(config, rev.getID(), entry.getKey(), entry.getValue());
                }
            }

            var summary = makeSummaryTo(sectionInfos.size(), config, sectionsPerYear.navigableKeySet(), usingAdditionalTargets);
            wb.edit(config.pagename(), page.toString(), summary, false, true, -2, List.of(CHANGE_TAG), rev.getTimestamp());
        } else {
            System.out.println("No eligible sections found for " + config.pagename());
        }
    }

    private static List<ArchiveConfig> parseArchiveConfig(JSONObject json) {
        var config = new ArrayList<ArchiveConfig>();

        var minDaysGlobal = json.getInt("minDays");
        var subpageGlobal = json.getString("subpage");
        var triggerGlobal = json.getBoolean("triggerOnTemplate");
        var sectionLevelGlobal = json.getInt("sectionLevel");

        for (var obj : json.getJSONArray("entries")) {
            var entry = (JSONObject)obj;
            var pagename = entry.getString("pagename");
            var minDays = entry.optInt("minDays", minDaysGlobal);
            var subpage = entry.optString("subpage", subpageGlobal);
            var trigger = entry.optBoolean("triggerOnTemplate", triggerGlobal);
            var sectionLevel = entry.optInt("sectionLevel", sectionLevelGlobal);
            var honorLinksInHeader = entry.optBoolean("honorLinksInHeader");

            if (minDays < 0) {
                throw new IllegalArgumentException("minDays must be positive or zero: " + minDays);
            }

            if (subpage.isEmpty()) {
                throw new IllegalArgumentException("subpage must be a non-empty string: " + subpage);
            }

            if (sectionLevel < 2 || sectionLevel > 6) {
                throw new IllegalArgumentException("sectionLevel must be between 2 and 6: " + sectionLevel);
            }

            config.add(new ArchiveConfig(pagename, minDays, subpage, trigger, sectionLevel, honorLinksInHeader));
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
            new HelpFormatter().printHelp(ArchiveThreads.class.getName(), options);
            throw e;
        }
    }

    private static boolean processAdditionalTargets(ArchiveConfig config, long revid, SectionInfo sectionInfo) throws LoginException, IOException {
        var targets = new ArrayList<String>();

        if (config.triggerOnTemplate() && !sectionInfo.targets().isEmpty()) {
            sectionInfo.targets().forEach(targets::add);
        } else if (config.honorLinksInHeader() && !sectionInfo.type().contains(ArchiveType.IGNORE_HEADER)) {
            P_HEADER_LINK.matcher(sectionInfo.section().getHeader()).results()
                .map(m -> m.group(1).strip())
                .filter(target -> wb.namespace(target) == Wiki.MAIN_NAMESPACE)
                .forEach(targets::add);
        }

        if (targets.isEmpty()) {
            return false;
        }

        var edited = false;
        var summary = String.format("Przeniesione z [[Specjalna:Niezmienny link/%d|%s]]", revid, config.pagename());
        var text = sectionInfo.section().getFlattenedContent();

        if (!config.honorLinksInHeader()) {
            text = String.format(": <small>Tytuł sekcji przed archiwizacją: %s.</small>\n", sectionInfo.section().getStrippedHeader()) + text;
        }

        for (var info : wb.getPageInfo(targets)) {
            if ((Boolean)info.get("exists")) {
                var pageName = (String)info.get("pagename");
                var talkPageName = wb.getTalkPage(pageName);
                var talkPageText = wb.getPageText(List.of(talkPageName)).get(0);

                if (talkPageText == null || !talkPageText.contains(text)) {
                    wb.edit(talkPageName, text, summary, false, true, -1, List.of(CHANGE_TAG), null);
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
        var targets = "";

        if (!subpages.isEmpty()) {
            targets = String.format(" w [[%s/%s/%d]]", config.pagename(), config.subpage(), subpages.first());

            if (subpages.size() > 1) {
                targets += ", " + subpages.tailSet(subpages.first() + 1).stream()
                    .map(subpage -> String.format("[[%1$s/%2$s/%3$d|/%3$d]]", config.pagename(), config.subpage(), subpage))
                    .collect(Collectors.joining(", "));
            }

            if (usingAdditionalTargets) {
                targets += " i in.";
            }
        }

        return String.format("zarchiwizowano %d %s", numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek")) + targets;
    }

    private static void editArchiveSubpage(ArchiveConfig config, long revid, int year, List<Section> sections) throws IOException, LoginException {
        var pagename = String.format("%s/%s/%d", config.pagename(), config.subpage(), year);
        var rev = wb.getTopRevision(pagename);
        var text = rev != null ? rev.getText() : "";

        sections = sections.stream()
            .filter(s -> !text.contains(s.toString()))
            .toList();

        if (!sections.isEmpty()) {
            var timestamp = rev != null ? rev.getTimestamp() : null;
            var archive = Page.store(pagename, text);

            archive.appendSections(AbstractSection.flattenSubSections(sections));

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
    }

    private static void tryEditArchiveListPage(ArchiveConfig config, Set<Integer> years) throws IOException, LoginException {
        var pagename = String.format("%s/%s", config.pagename(), config.subpage());
        var rev = wb.getTopRevision(pagename);
        var basetime = Optional.ofNullable(rev).map(Wiki.Revision::getTimestamp).orElse(null);

        final String text;

        if (rev == null) {
            text = """
                Archiwum strony [[%s]] z podziałem na lata wg daty zgłoszenia.

                <!-- START (nie zmieniaj tej linii ani poniższych aż do następnego znacznika) -->
                <!-- END (nie zmieniaj tej linii ani powyższych aż do poprzedniego znacznika) -->

                [[Kategoria:Archiwum Wikisłownika|%s]]
                """.formatted(config.pagename(), wb.removeNamespace(pagename));
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

    private record ArchiveConfig(String pagename, int minDays, String subpage, boolean triggerOnTemplate, int sectionLevel, boolean honorLinksInHeader) {}

    private enum ArchiveType {
        NONE,
        COPY,
        MOVE,
        IGNORE_HEADER
    }

    private record SectionInfo(Section section, OffsetDateTime earliest, OffsetDateTime latest, EnumSet<ArchiveType> type, List<String> targets) {
        private static List<String> parseTargets(Map<String, String> params, String regex) {
            return params.entrySet().stream()
                .filter(e -> e.getKey().matches(regex))
                .map(Map.Entry::getValue)
                .distinct()
                .filter(v -> !v.isEmpty() && wb.namespace(v) >= 0)
                .map(v -> wb.namespace(v) % 2 == 1 ? wb.getContentPage(v) : v)
                .toList();
        }

        private static EnumSet<ArchiveType> retrieveTargets(String text, List<String> targets) {
            var doc = Jsoup.parseBodyFragment(ParseUtils.removeCommentsAndNoWikiText(text));
            doc.getElementsByTag("nowiki").remove(); // already removed along with comments, but why not
            doc.getElementsByTag("s").remove();
            doc.getElementsByTag("pre").remove();
            doc.getElementsByTag("syntaxhighlight").remove();
            doc.getElementsByTag("source").remove();

            var eligibleParams = ParseUtils.getTemplates(TARGET_TEMPLATE, doc.body().text()).stream()
                .map(ParseUtils::getTemplateParametersWithValue)
                .filter(params -> !params.getOrDefault("ParamWithoutName1", "").equals("-"))
                .toList();

            var type = EnumSet.noneOf(ArchiveType.class);

            if (!eligibleParams.isEmpty()) {
                var params = eligibleParams.get(eligibleParams.size() - 1);

                if (targets.addAll(parseTargets(params, "^przenieś_do\\d*$"))) {
                    type.add(ArchiveType.MOVE);
                } else if (targets.addAll(parseTargets(params, "^kopiuj_do\\d*$"))) {
                    type.add(ArchiveType.COPY);
                }

                if (params.getOrDefault("ignoruj_nagłówek", "").equals("tak")) {
                    type.add(ArchiveType.IGNORE_HEADER);
                }
            } else {
                type.add(ArchiveType.NONE);
            }

            return type;
        }

        public static SectionInfo of(Section s) {
            var text = s.toString();
            var timestamps = retrieveTimestamps(text);
            var targets = new ArrayList<String>();
            var type = retrieveTargets(text, targets);

            if (!timestamps.isEmpty()) {
                return new SectionInfo(s, timestamps.first(), timestamps.last(), type, targets);
            } else {
                return null;
            }
        }
    }
}
