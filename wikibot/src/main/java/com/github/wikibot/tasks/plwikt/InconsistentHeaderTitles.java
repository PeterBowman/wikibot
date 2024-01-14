package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public final class InconsistentHeaderTitles {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/InconsistentHeaderTitles/");
    private static final String TARGET_PAGE = "Wikipedysta:PBbot/nagłówki";
    private static final String PAGE_INTRO;

    private static final int COLUMN_ELEMENT_THRESHOLD = 50;
    private static final int NUMBER_OF_COLUMNS = 3;

    // from Linker::formatLinksInComment in Linker.php
    private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]");

    // https://en.wikipedia.org/wiki/Whitespace_character#Unicode
    // + SOFT HYPHEN (00AD), LEFT-TO-RIGHT MARK (200E), RIGHT-TO-LEFT MARK (200F)
    private static final Pattern P_WHITESPACE = Pattern.compile("[\u0009\u00a0\u00ad\u1680\u180e\u2000-\u200f\u2028-\u2029\u202f\u205f-\u2060\u3000\ufeff]");

    // http://www.freeformatter.com/html-entities.html
    private static final Pattern P_ENTITIES = Pattern.compile("&(nbsp|ensp|emsp|thinsp|zwnj|zwj|lrm|rlm);");

    private static final List<String> HEADER_TEMPLATES = List.of("zh", "ko", "ja");
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Map<String, Set<Item>> map = new TreeMap<>(Collator.getInstance(new Locale("pl", "PL")));

    private static final Plural PLURAL_PL;
    private static final LocalizedNumberFormatter NUMBER_FORMAT_PL;

    static {
        PAGE_INTRO = """
            Spis zawiera listę haseł z rozbieżnością pomiędzy nazwą strony a tytułem sekcji językowej.
            Odświeżany jest automatycznie przy użyciu wewnętrzej listy ostatnio przenalizowanych stron.
            Zmiany wykonane ręcznie na tej stronie nie będą uwzględniane przez bota.
            Spacje niełamliwe i inne znaki niewidoczne w podglądzie strony oznaczono symbolem
            <code>&#9251;</code> ([[w:en:Whitespace character#Unicode]]).
            __NOEDITSECTION__
            {{TOChorizontal}}
            """;

        var polishWords = new WordForms[] {
            new WordForms(new String[] {"hasło", "hasła", "haseł"}),
            new WordForms(new String[] {"strona", "strony", "stron"}),
            new WordForms(new String[] {"języku", "językach", "językach"})
        };

        PLURAL_PL = new Plural(PluralRules.POLISH, polishWords);
        NUMBER_FORMAT_PL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var line = readOptions(args);
        var candidateTitles = new ArrayList<String>(5000);

        if (!readDumpFile(line.hasOption("local"), candidateTitles)) {
            System.out.println("No dump file found, reading from stored titles.");
            candidateTitles.addAll(extractStoredTitles());
        }

        analyzeWithRecentChanges(candidateTitles);

        if (map.isEmpty()) {
            System.out.println("No entries found/extracted.");
            return;
        }

        var hash = LOCATION.resolve("hash.txt");

        if (Files.exists(hash) && Integer.parseInt(Files.readString(hash)) == map.hashCode()) {
            System.out.println("No changes detected, aborting.");
            return;
        } else {
            Files.writeString(hash, Integer.toString(map.hashCode()));

            var titles = map.values().stream()
                .flatMap(Collection::stream)
                .map(item -> item.pageTitle)
                .distinct()
                .toList();

            Files.write(LOCATION.resolve("stored_titles.txt"), titles);
            System.out.printf("%d titles stored.%n", titles.size());
        }

        var page = makePage();
        Files.writeString(LOCATION.resolve("page.txt"), page.toString());

        if (line.hasOption("edit")) {
            wb.setMarkBot(false);
            wb.edit(page.getTitle(), page.toString(), "aktualizacja");
        }
    }

    private static CommandLine readOptions(String[] args) {
        var options = new Options();
        options.addOption("l", "local", false, "use local dump");
        options.addOption("e", "edit", false, "perform edit");

        if (args.length == 0) {
            System.out.print("Option: ");
            var input = Misc.readLine();
            args = input.split(" ");
        }

        var parser = new DefaultParser();

        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            new HelpFormatter().printHelp(InconsistentHeaderTitles.class.getName(), options);
            return null;
        }
    }

    private static void analyzeWithRecentChanges(List<String> bufferedTitles) throws IOException {
        var newTitles = extractRecentChanges();

        var distinctTitles = Stream.concat(newTitles.stream(), bufferedTitles.stream())
            .distinct()
            .filter(title -> !title.startsWith("Słownik "))
            .toList();

        if (!distinctTitles.isEmpty()) {
            wb.getContentOfPages(distinctTitles).stream().forEach(InconsistentHeaderTitles::findErrors);
        }
    }

    private static boolean readDumpFile(boolean useLocalDump, List<String> titles) throws IOException {
        var datePath = LOCATION.resolve("last_date.txt");
        var dumpConfig = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES);

        if (useLocalDump) {
            dumpConfig.local();

            if (Files.exists(datePath)) {
                dumpConfig.after(Files.readString(datePath).strip());
            }
        } else {
            dumpConfig.remote();
        }

        var optDump = dumpConfig.fetch();

        if (!optDump.isPresent()) {
            return false;
        }

        try (var stream = optDump.get().stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .map(Page::getAllSections)
                .flatMap(Collection::stream)
                .filter(InconsistentHeaderTitles::filterSections)
                .map(section -> section.getContainingPage().get().getTitle())
                .forEach(titles::add);
        }

        Files.writeString(datePath, optDump.get().getDirectoryName());
        return true;
    }

    private static List<String> extractRecentChanges() throws IOException {
        OffsetDateTime earliest;

        try {
            var timestamp = Files.readAllLines(LOCATION.resolve("timestamp.txt")).get(0);
            earliest = OffsetDateTime.parse(timestamp);
        } catch (Exception e) {
            System.out.println("Setting new timestamp reference (-24h).");
            earliest = OffsetDateTime.now(wb.timezone()).minusDays(1);
        }

        var latest = OffsetDateTime.now(wb.timezone());

        if (!latest.isAfter(earliest)) {
            System.out.println("Extracted timestamp is greater than the current time, setting to -24h.");
            earliest = OffsetDateTime.now(wb.timezone()).minusDays(1);
        }

        var rcTypes = List.of("new", "edit");
        var revs = wb.recentChanges(earliest, latest, null, rcTypes, false, null, Wiki.MAIN_NAMESPACE);

        var helper = wb.new RequestHelper().withinDateRange(earliest, latest);
        var logs = wb.getLogEntries(Wiki.MOVE_LOG, "move", helper);

        // store current timestamp for the next iteration
        Files.write(LOCATION.resolve("timestamp.txt"), List.of(latest.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));

        return Stream.concat(
            revs.stream().map(Wiki.Revision::getTitle),
            logs.stream().map(Wiki.LogEntry::getDetails)
                .map(details -> details.get("target_title"))
                .filter(title -> wb.namespace(title) == Wiki.MAIN_NAMESPACE)
        ).distinct().toList();
    }

    private static List<String> extractStoredTitles() {
        try {
            return Files.readAllLines(LOCATION.resolve("stored_titles.txt"));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void findErrors(PageContainer pc) {
        Page page;

        try {
            page = Page.wrap(pc);
        } catch (Exception e) {
            return;
        }

        page.getAllSections().stream()
            .filter(InconsistentHeaderTitles::filterSections)
            .forEach(section -> {
                var lang = section.getLangShort();
                var headerTitle = section.getHeaderTitle();
                var item = new Item(pc.title(), headerTitle);

                // http://stackoverflow.com/a/10743710
                var coll = map.get(lang);

                if (coll == null) {
                    map.putIfAbsent(lang, new TreeSet<>());
                    coll = map.get(lang);
                }

                coll.add(item);
            });
    }

    private static boolean filterSections(Section section) {
        var headerTitle = section.getHeaderTitle();
        headerTitle = ParseUtils.removeCommentsAndNoWikiText(headerTitle);

        if (StringUtils.containsAny(headerTitle, '{', '}')) {
            headerTitle = stripHeaderTemplates(headerTitle);
        }

        if (StringUtils.containsAny(headerTitle, '[', ']')) {
            headerTitle = stripWikiLinks(headerTitle);
        }

        var pageTitle = section.getContainingPage().get().getTitle();
        pageTitle = pageTitle.replace("ʼ", "'").replace("…", "...");
        headerTitle = headerTitle.replace("ʼ", "'").replace("…", "...");

        return !pageTitle.equals(headerTitle);
    }

    private static String stripHeaderTemplates(String text) {
        for (var headerTemplate : HEADER_TEMPLATES) {
            text = Utils.replaceTemplates(text, headerTemplate, template ->
                Optional.of(ParseUtils.getTemplateParametersWithValue(template))
                    .map(params -> params.get("ParamWithoutName1"))
                    .filter(Objects::nonNull)
                    .orElse(template)
            );
        }

        return text;
    }

    private static String stripWikiLinks(String text) {
        return P_LINK.matcher(text).replaceAll(m -> Optional.ofNullable(m.group(2)).orElse(m.group(1)));
    }

    private static com.github.wikibot.parsing.Page makePage() {
        var values = map.values().stream()
            .flatMap(coll -> coll.stream().map(item -> item.pageTitle))
            .toList();

        var total = values.size();
        var unique = (int)values.stream().distinct().count();

        System.out.printf("Found: %d (%d unique)%n", total, unique);

        var page = com.github.wikibot.parsing.Page.create(TARGET_PAGE);

        page.setIntro(PAGE_INTRO + String.format(
            "Znaleziono %s %s (%s %s) w %s %s. Aktualizacja: ~~~~~.",
            NUMBER_FORMAT_PL.format(total), PLURAL_PL.pl(total, "hasło"),
            NUMBER_FORMAT_PL.format(unique), PLURAL_PL.pl(unique, "strona"),
            NUMBER_FORMAT_PL.format(map.keySet().size()), PLURAL_PL.pl(map.keySet().size(), "języku")
        ));

        var sections = map.entrySet().stream()
            .map(entry -> {
                var useColumns = entry.getValue().size() > COLUMN_ELEMENT_THRESHOLD;
                var section = com.github.wikibot.parsing.Section.create(entry.getKey(), 2);

                var sb = new StringBuilder(entry.getValue().size() * 35);
                sb.append(String.format("{{język linków|%s}}", entry.getKey()));

                if (useColumns) {
                    sb.append(String.format("{{columns|liczba=%d|", NUMBER_OF_COLUMNS));
                }

                sb.append("\n");

                entry.getValue().stream()
                    .map(item -> item.buildEntry("#[[%s]]: %s", "#[[%s|%s]]: %s"))
                    .forEach(formatted -> sb.append(formatted).append("\n"));

                if (useColumns) {
                    sb.append("}}");
                }

                section.setIntro(sb.toString());
                return section;
            })
            .toList();

        page.appendSections(sections);
        return page;
    }

    private static class Item implements Comparable<Item> {
        final String pageTitle;
        final String headerTitle;

        Item(String pageTitle, String headerTitle) {
            this.pageTitle = pageTitle;
            this.headerTitle = headerTitle;
        }

        String buildEntry(String simpleTargetFmt, String pipedTargetFmt) {
            var normalizedPageTitle = normalizeTitle(pageTitle);
            var normalizedHeaderTitle = normalizeTitle(headerTitle);

            if (normalizedPageTitle.equals(pageTitle)) {
                return String.format(simpleTargetFmt, normalizedPageTitle, normalizedHeaderTitle);
            } else {
                return String.format(pipedTargetFmt, pageTitle, normalizedPageTitle, normalizedHeaderTitle);
            }
        }

        private static String normalizeTitle(String title) {
            final var blankCharEntity = "&#9251;";
            title = title.replace("&#", "&amp;#");
            title = title.replace("<", "&lt;").replace(">", "&gt;");
            title = P_WHITESPACE.matcher(title).replaceAll(blankCharEntity);
            title = P_ENTITIES.matcher(title).replaceAll(blankCharEntity);
            return title;
        }

        @Override
        public int compareTo(Item item) {
            return pageTitle.compareTo(item.pageTitle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pageTitle, headerTitle);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof Item i) {
                return pageTitle.equals(i.pageTitle) && headerTitle.equals(i.headerTitle);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("[%s, %s]", pageTitle, headerTitle);
        }
    }
}
