package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.json.JSONObject;
import org.json.JSONPointer;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.utils.CollectorUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.thoughtworks.xstream.XStream;

final class DidYouKnowStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/DidYouKnowStats/");
    private static final String CATEGORY_TREE = "Ekspozycje Czywiesza %d";
    private static final String EXPO_TEMPLATE = "Wikiprojekt:Czy wiesz/weryfikacja";
    private static final Pattern EXPO_ARCHIVE_PATT = Pattern.compile("^Wikiprojekt:Czy wiesz/archiwum/\\d{4}-\\d{2}$");
    private static final int PROJECT_NS = 102;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Europe/Warsaw");
    private static final String DYK_SUBPAGE_AUTHORS = "Wikiprojekt:Czy wiesz/Statystyki (%d)";
    private static final String DYK_SUBPAGE_POSTERS = "Wikiprojekt:Czy wiesz/Statystyki zgłaszających (%d)";
    private static final String DYK_SUBPAGE_REVIEWERS = "Wikiprojekt:Czy wiesz/Statystyki sprawdzających (%d)";
    private static final String DYK_SUBPAGE_PAGEVIEWS = "Wikiprojekt:Czy wiesz/Statystyki wyświetleń (%d)";
    private static final String DYK_SUBPAGE_PAGEVIEWS_TOP100 = "Wikiprojekt:Czy wiesz/Statystyki wyświetleń (%d)/TOP 100";
    private static final String DYK_SUBPAGE_TEMPLATE = "Wikiprojekt:Czy wiesz/statystyki-szablon";
    private static final String REST_URI_TEMPLATE = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/pl.wikipedia.org/all-access/user/%1$s/daily/%2$d/%2$d";
    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    private static final DateTimeFormatter EXPO_DATE_FORMAT = new DateTimeFormatterBuilder()
        .appendLiteral("Czy wiesz/ekspozycje/")
        .appendValue(ChronoField.YEAR, 4)
        .appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .toFormatter();

    public static void main(String[] args) throws Exception {
        var lastDatePath = LOCATION.resolve("last_date.txt");
        var now = OffsetDateTime.now().atZoneSameInstant(PROJECT_ZONE);
        var line = readOptions(args);
        var years = new ArrayList<Integer>();

        if (line.hasOption("year")) {
            var yearStr = line.getOptionValue("year");
            years.add(Integer.parseInt(yearStr));
        } else if (line.hasOption("update")) {
            if (Files.exists(lastDatePath)) {
                var contents = Files.readString(lastDatePath).trim();
                var lastDate = OffsetDateTime.parse(contents, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(PROJECT_ZONE);

                for (int year = lastDate.getYear(); year <= now.getYear(); year++) {
                    years.add(year);
                }
            } else {
                years.add(now.getYear());
            }
        } else {
            throw new IllegalArgumentException("No option specified.");
        }

        Login.login(wb);

        for (var year : years) {
            System.out.println("Processing year " + year);
            var entries = processYear(year);
            System.out.printf("Got %d entries.%n", entries.size());

            var untilMonth = entries.stream().mapToInt(Entry::monthOfYear).max().getAsInt();

            updateAuthors(year, untilMonth, entries);
            updatePosters(year, untilMonth, entries);
            updateReviewers(year, untilMonth, entries);
            updatePageViews(year, untilMonth, entries);
            updateNavbox(year);
        }

        Files.writeString(lastDatePath, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private static CommandLine readOptions(String[] args) {
        var options = new Options();
        options.addOption("y", "year", true, "year of archived threads to be queried");
        options.addOption("u", "update", false, "process new changes since last run");

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
            new HelpFormatter().printHelp(DidYouKnowStats.class.getName(), options);
            return null;
        }
    }

    private static List<Entry> processYear(int year) throws IOException {
        var category = CATEGORY_TREE.formatted(year);
        var members = wb.getCategoryMembers(category, PROJECT_NS);

        var talkPages = members.stream()
            .map(wb::getTalkPage)
            .filter(title -> isValidExpoPage(title, year))
            .toList();

        var linkedArticles = getLinkedArticles(members);
        var redirectsToNewTitles = getRedirectMap(linkedArticles.stream().toList());

        var entries = new ArrayList<Entry>();

        for (var talkPage : wb.getContentOfPages(talkPages)) {
            var temporal = EXPO_DATE_FORMAT.parse(wb.removeNamespace(talkPage.getTitle()));
            var p = Page.wrap(talkPage);

            for (var section : p.getAllSections()) {
                for (var template : ParseUtils.getTemplatesIgnoreCase(EXPO_TEMPLATE, section.toString())) {
                    var params = ParseUtils.getTemplateParametersWithValue(template);

                    var title = wb.normalize(params.getOrDefault("ParamWithoutName1", ""));
                    var author = wb.normalize(params.getOrDefault("ParamWithoutName4", ""));
                    var poster = wb.normalize(params.getOrDefault("ParamWithoutName5", ""));

                    title = redirectsToNewTitles.getOrDefault(title, title);

                    if (StringUtils.isAnyBlank(title, author, poster) || !linkedArticles.contains(title)) {
                        continue;
                    }

                    var reviewers = new ArrayList<String>();

                    for (int i = 6; i <= 12; i++) {
                        var value = params.getOrDefault("ParamWithoutName" + i, "?").trim();

                        // ugly hack for extra trailing braces, e.g.: "...|Jamnik z Tarnowa}}}"
                        value = StringUtils.stripEnd(value, "}");

                        if (!value.isBlank() && !value.equals("?")) {
                            reviewers.add(wb.normalize(value));
                        }
                    }

                    var entry = new Entry(title, author, poster,
                                          Collections.unmodifiableList(reviewers),
                                          temporal.get(ChronoField.YEAR),
                                          temporal.get(ChronoField.MONTH_OF_YEAR),
                                          temporal.get(ChronoField.DAY_OF_MONTH));

                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    private static boolean isValidExpoPage(String title, int year) {
        try {
            var sanitized = wb.removeNamespace(title);
            var parsed = EXPO_DATE_FORMAT.parse(sanitized);
            return parsed.get(ChronoField.YEAR) == year;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Set<String> getLinkedArticles(List<String> members) throws IOException {
        var linkedTitles = new HashSet<String>();

        var candidateMembers = members.stream()
            .filter(title -> EXPO_ARCHIVE_PATT.matcher(title).matches())
            .toList();

        for (var expo : candidateMembers) {
            var links = wb.getLinksOnPage(expo);
            linkedTitles.addAll(links);
        }

        return linkedTitles;
    }

    private static Map<String, String> getRedirectMap(List<String> titles) throws IOException {
        var map = new HashMap<String, String>();
        var redirects = wb.whatLinksHere(titles, true, false, Wiki.MAIN_NAMESPACE); // only list redirects

        for (int i = 0; i < redirects.size(); i++) {
            var title = titles.get(i);
            var maybeRedirects = redirects.get(i);

            if (!maybeRedirects.isEmpty()) {
                maybeRedirects.stream().forEach(t -> map.put(t, title));
            }
        }

        return map;
    }

    private static String makeIntro(String header, String legend, String threshold, int year) {
        return """
            <templatestyles src="Wikiprojekt:Czy wiesz/styles.css" />
            {{Wikiprojekt:Czy wiesz/statystyki-szablon}}
            __NOTOC__ __NOTALK__
            Ostatnia aktualizacja: ~~~~~.

            == %1$s artykułów wyeksponowanych w CzyWieszu (%4$d) ==

            Legenda:

            {{legenda|gold|najwięcej %2$s w miesiącu (wg daty ekspozycji) – złoty medal}}
            {{legenda|silver|najwięcej %2$s w miesiącu (wg daty ekspozycji) – srebrny medal}}
            {{legenda|brown|najwięcej %2$s w miesiącu (wg daty ekspozycji) – brązowy medal}}
            {{legenda|lightgreen|przynajmniej %3$s w każdym miesiącu (wg daty ekspozycji)}}

            {|class="wikitable sortable dyk-table-stats"
            !Lp.
            !Użytkownik
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-01|01]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-02|02]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-03|03]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-04|04]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-05|05]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-06|06]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-07|07]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-08|08]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-09|09]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-10|10]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-11|11]]
            ![[Wikiprojekt:Czy wiesz/archiwum/%4$d-12|12]]
            !Suma
            %%s
            |}

            [[Kategoria:Wikiprojekt Czy wiesz]]
            """.formatted(header, legend, threshold, year);
    }

    private static String makeTableContents(Map<String, Map<Integer, Long>> groupedUsers, Map<Integer, Map<String, Long>> groupedMonths, int untilMonth) {
        var sb = new StringBuilder();

        var topUsersMonthly = groupedMonths.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> TopUsers.fromMap(e.getValue())
            ));

        var rows = groupedUsers.entrySet().stream()
            .map(e -> Row.fromMap(e.getKey(), e.getValue()))
            .sorted(Comparator.<Row>comparingInt(Row::overall).reversed().thenComparing(Row::user))
            .toList();

        var ordinal = 1;

        for (var i = 0; i < rows.size(); i++) {
            var row = rows.get(i);

            sb.append("|-\n");

            if (i != 0 && row.overall() != rows.get(i - 1).overall()) {
                ordinal = i + 1;
            }

            sb.append("|%d\n".formatted(ordinal));

            if (untilMonth > 1 && row.stats().allMonths(untilMonth)) {
                sb.append("|class=\"hl4\"");
            }

            if (InetAddressValidator.getInstance().isValid(row.user())) {
                sb.append("|[[User talk:%1$s|%1$s]]\n".formatted(row.user()));
            } else {
                sb.append("|[[User:%1$s|%1$s]]\n".formatted(row.user()));
            }

            var month = 1;

            for (var score : row.stats()) {
                if (topUsersMonthly.containsKey(month)) {
                    var topUsers = topUsersMonthly.get(month);

                    if (topUsers.gold().contains(row.user())) {
                        sb.append("|class=\"hl1\"");
                    } else if (topUsers.silver().contains(row.user())) {
                        sb.append("|class=\"hl2\"");
                    } else if (topUsers.bronze().contains(row.user())) {
                        sb.append("|class=\"hl3\"");
                    }
                }

                sb.append(score != 0 ? "|%d\n".formatted(score) : "|–\n");
                month++;
            }

            sb.append("|%d\n".formatted(row.overall()));
        }

        return sb.toString().stripTrailing();
    }

    private static void updateAuthors(int year, int untilMonth, List<Entry> entries) throws IOException, LoginException {
        var template = makeIntro("Najbardziej aktywni autorzy", "artykułów", "jeden artykuł", year);

        var groupedUsers = entries.stream().collect(Collectors.groupingBy(
            Entry::author,
            Collectors.groupingBy(Entry::monthOfYear, Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            Entry::monthOfYear,
            Collectors.groupingBy(Entry::author, Collectors.counting())
        ));

        var contents = makeTableContents(groupedUsers, groupedMonths, untilMonth);
        var text = template.formatted(contents);

        wb.edit(DYK_SUBPAGE_AUTHORS.formatted(year), text, "aktualizacja");
    }

    private static void updatePosters(int year, int untilMonth, List<Entry> entries) throws IOException, LoginException {
        var template = makeIntro("Najwięcej zgłoszeń", "zgłoszeń", "jedno zgłoszenie", year);

        var groupedUsers = entries.stream().collect(Collectors.groupingBy(
            Entry::poster,
            Collectors.groupingBy(Entry::monthOfYear, Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            Entry::monthOfYear,
            Collectors.groupingBy(Entry::poster, Collectors.counting())
        ));

        var contents = makeTableContents(groupedUsers, groupedMonths, untilMonth);
        var text = template.formatted(contents);

        wb.edit(DYK_SUBPAGE_POSTERS.formatted(year), text, "aktualizacja");
    }

    private static void updateReviewers(int year, int untilMonth, List<Entry> entries) throws IOException, LoginException {
        var template = makeIntro("Najwięcej „sprawdzeń”", "„sprawdzeń”", "jedno „sprawdzenie”", year);

        var groupedUsers = entries.stream().collect(CollectorUtils.groupingByFlattened(
            e -> e.reviewers().stream(),
            Collectors.groupingBy(Entry::monthOfYear, Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            Entry::monthOfYear,
            CollectorUtils.groupingByFlattened(e -> e.reviewers().stream(), Collectors.counting())
        ));

        var contents = makeTableContents(groupedUsers, groupedMonths, untilMonth);
        var text = template.formatted(contents);

        wb.edit(DYK_SUBPAGE_REVIEWERS.formatted(year), text, "aktualizacja");
    }

    private static void updatePageViews(int year, int untilMonth, List<Entry> entries) throws IOException, InterruptedException, LoginException {
        var xstream = new XStream();
        var storagePath = LOCATION.resolve("pageviews-%d.xml".formatted(year));

        @SuppressWarnings("unchecked")
        var storage = Files.exists(storagePath)
            ? (SortedMap<Integer, Map<String, Integer>>)xstream.fromXML(storagePath.toFile())
            : new TreeMap<Integer, Map<String, Integer>>();

        var filtered = entries.stream()
            .filter(e -> !storage.getOrDefault(e.getTimestamp(), Collections.emptyMap()).containsKey(e.title()))
            .toList();

        System.out.println("Filtered entries for pageviews analysis: " + filtered.size());

        if (filtered.isEmpty()) {
            return;
        }

        var client = HttpClient.newHttpClient();
        var jsonPointer = JSONPointer.builder().append("items").append(0).append("views").build();

        var requestBuilder = HttpRequest.newBuilder()
            .header("User-Agent", Login.getUserAgent())
            .header("Accept", "application/json")
            .GET();

        for (var entry : filtered) {
            var uri = URI.create(REST_URI_TEMPLATE.formatted(URLEncoder.encode(entry.title(), StandardCharsets.UTF_8), entry.getTimestamp()));
            var request = requestBuilder.uri(uri).build();
            var response = getResponse(client, request);
            var json = new JSONObject(response.body());
            var optViews = Optional.ofNullable((Integer)json.optQuery(jsonPointer));

            if (optViews.isPresent()) {
                storage.computeIfAbsent(entry.getTimestamp(), k -> new HashMap<>()).put(entry.title(), optViews.get());
            }

            System.out.printf("Queried pageviews for %s (%d)%n", entry.title(), entry.getTimestamp());
            Thread.sleep(100); // REST API rules state that no more than 100 requests per *second* are allowed, so 10 ms is the hard limit
        }

        var currentTitles = entries.stream().map(Entry::title).collect(Collectors.toSet());

        storage.values().forEach(map -> map.keySet().removeIf(title -> !currentTitles.contains(title))); // some titles might have been renamed
        storage.values().removeIf(Map::isEmpty); // shouldn't happen, maybe the pages were deleted instead

        Files.writeString(storagePath, xstream.toXML(storage));

        var results = new ArrayList<PageViews>();

        storage.entrySet().stream()
            .forEach(e -> e.getValue().entrySet().stream()
                .map(ee -> new PageViews(ee.getKey(), year, extractMonth(e.getKey()), extractDay(e.getKey()), ee.getValue()))
                .forEach(results::add)
            );

        var text = makePageViewsText(year, untilMonth, results);
        wb.edit(DYK_SUBPAGE_PAGEVIEWS.formatted(year), text, "aktualizacja");

        var top100Text = makePageViewsTop100Text(year, untilMonth, results);
        wb.edit(DYK_SUBPAGE_PAGEVIEWS_TOP100.formatted(year), top100Text, "aktualizacja");
    }

    private static HttpResponse<String> getResponse(HttpClient client, HttpRequest request) throws InterruptedException {
        final var MAX_ATTEMPTS = 5;

        for (var attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                System.out.printf("Failed to get response, attempt %d: %s%n", attempt + 1, e.getMessage());
                Thread.sleep(5000); // throttle
            }
        }

        throw new RuntimeException("Failed to get response after %d attempts: %s".formatted(MAX_ATTEMPTS, request.uri()));
    }

    private static int extractMonth(int timestamp) {
        return timestamp / 100 % 100;
    }

    private static int extractDay(int timestamp) {
        return timestamp % 100;
    }

    private static final String makePageViewsText(int year, int untilMonth, List<PageViews> pageViews) {
        var template = """
            <templatestyles src="Wikiprojekt:Czy wiesz/styles.css" />
            {{Wikiprojekt:Czy wiesz/statystyki-szablon}}
            __FORCETOC__ __NOTALK__
            Ostatnia aktualizacja: ~~~~~.

            == Statystyki wyświetleń w CzyWieszu (%1$d) ==

            W tabeli podano liczbę odsłon strony w dniu ekspozycji w Czywieszu. Do sporządzenia statystyk użyto narzędzia [[toolforge:pageviews|Pageviews Analysis]].

            %%s

            [[Kategoria:Wikiprojekt Czy wiesz]]
            """.formatted(year);

        var sb = new StringBuilder();
        var locale = Locale.forLanguageTag("pl-PL");

        var rankingPerMonth = pageViews.stream()
            .collect(Collectors.groupingBy(
                PageViews::month,
                Collectors.toCollection(() -> new TreeSet<>(Comparator.comparingInt(PageViews::views).reversed().thenComparing(PageViews::day)))
            ));

        for (int month = 1; month <= untilMonth; month++) {
            var localized = Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale);

            sb.append("=== %s %d ===\n".formatted(StringUtils.capitalize(localized), year));
            sb.append("{|class=\"wikitable sortable dyk-table-stats\"\n");
            sb.append("!Lp.\n");
            sb.append("!Artykuł\n");
            sb.append("!Data ekspozycji\n");
            sb.append("!Liczba wyświetleń\n");

            if (rankingPerMonth.containsKey(month)) {
                fillPageViewsTable(sb, rankingPerMonth.get(month));
            }

            sb.append("|}\n\n");
        }

        return template.formatted(sb.toString().stripTrailing());
    }

    private static final String makePageViewsTop100Text(int year, int untilMonth, List<PageViews> pageViews) {
        var template = """
            <templatestyles src="Wikiprojekt:Czy wiesz/styles.css" />
            {{Wikiprojekt:Czy wiesz/statystyki-szablon}}
            __NOTOC__ __NOTALK__
            Ostatnia aktualizacja: ~~~~~.

            == Statystyki wyświetleń w CzyWieszu (%1$d) ==

            W tabeli podano 100 artykułów z największą liczbą odsłon w dniu ekspozycji w Czywieszu w %1$d roku. Do sporządzenia statystyk użyto narzędzia [[toolforge:pageviews|Pageviews Analysis]].

            {|class="wikitable sortable dyk-table-stats"
            !Lp.
            !Artykuł
            !Data ekspozycji
            !Liczba wyświetleń
            %%s
            |}

            [[Kategoria:Wikiprojekt Czy wiesz]]
            """.formatted(year);

        var sb = new StringBuilder();

        var top100 = pageViews.stream()
            .sorted(Comparator.comparingInt(PageViews::views).reversed().thenComparing(PageViews::day))
            .limit(100)
            .toList();

        fillPageViewsTable(sb, top100);

        return template.formatted(sb.toString().stripTrailing());
    }

    private static void fillPageViewsTable(StringBuilder sb, Collection<PageViews> pageViews) {
        var ordinal = 1;

        for (var entry : pageViews) {
            sb.append("|-\n");
            sb.append("|%d\n".formatted(ordinal++));
            sb.append("|[[%s]]\n".formatted(entry.title()));
            sb.append("|[[Wikiprojekt:Czy wiesz/ekspozycje/%1$04d-%2$02d-%3$02d|%1$04d-%2$02d-%3$02d]]\n".formatted(entry.year(), entry.month(), entry.day()));
            sb.append("|%d\n".formatted(entry.views()));
        }
    }

    private static void updateNavbox(int year) throws IOException, LoginException {
        var text = wb.getPageText(List.of(DYK_SUBPAGE_TEMPLATE.formatted(year))).get(0);
        var lines = text.lines().collect(Collectors.toCollection(ArrayList::new));
        var count = lines.size();

        addLineToNavbox(lines, year, "<!-- #authors# ", "* [[%s|%d]]".formatted(DYK_SUBPAGE_AUTHORS.formatted(year), year));
        addLineToNavbox(lines, year, "<!-- #reviewers# ", "* [[%s|%d]]".formatted(DYK_SUBPAGE_REVIEWERS.formatted(year), year));
        addLineToNavbox(lines, year, "<!-- #posters# ", "* [[%s|%d]]".formatted(DYK_SUBPAGE_POSTERS.formatted(year), year));

        addLineToNavbox(lines, year, "<!-- #pageviews# ", "* [[%s|%d]] {{small|([[%s|TOP 100]])}}".formatted(
            DYK_SUBPAGE_PAGEVIEWS.formatted(year),
            year,
            DYK_SUBPAGE_PAGEVIEWS_TOP100.formatted(year)
        ));

        if (lines.size() != count) {
            wb.edit(DYK_SUBPAGE_TEMPLATE.formatted(year), String.join("\n", lines), "aktualizacja");
        }
    }

    private static void addLineToNavbox(List<String> lines, int year, String marker, String newLine) {
        for (var i = 0; i < lines.size(); i++) {
            if (i != 0 && lines.get(i).startsWith(marker)) {
                if (!lines.get(i - 1).contains(Integer.toString(year))) {
                    lines.add(i, newLine);
                }

                break;
            }
        }
    }

    private record Entry(String title, String author, String poster, List<String> reviewers, int year, int monthOfYear, int dayOfMonth) {
        int getTimestamp() {
            return Integer.parseInt("%04d%02d%02d".formatted(year, monthOfYear, dayOfMonth));
        }
    }

    private static class MonthlyStats implements Iterable<Integer> {
        private static final int NUM_MONTHS = 12;

        final List<Integer> months = Arrays.asList(new Integer[NUM_MONTHS]); // fixed-size list

        static MonthlyStats fromMap(Map<Integer, Long> monthly) {
            var stats = new MonthlyStats();

            for (var month = 1; month <= NUM_MONTHS; month++) {
                stats.months.set(month - 1, monthly.getOrDefault(month, 0L).intValue());
            }

            return stats;
        }

        public boolean allMonths(int untilMonth) {
            for (var month = 1; month <= Math.min(untilMonth, NUM_MONTHS); month++) {
                if (months.get(month - 1) == 0) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public Iterator<Integer> iterator() {
            return months.iterator();
        }
    }

    private record Row(String user, int overall, MonthlyStats stats) {
        static Row fromMap(String user, Map<Integer, Long> monthly) {
            return new Row(
                user,
                monthly.values().stream().mapToInt(Long::intValue).sum(),
                MonthlyStats.fromMap(monthly)
            );
        }
    }

    private record TopUsers(List<String> gold, List<String> silver, List<String> bronze) {
        private static List<String> getRankedUsers(Map<String, Long> topUsers, long score) {
            return topUsers.entrySet().stream()
                .filter(e -> e.getValue().equals(score))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableList());
        }

        static TopUsers fromMap(Map<String, Long> topUsers) {
            var highestScores = topUsers.values().stream()
                .filter(v -> v != 0)
                .sorted(Comparator.reverseOrder())
                .distinct()
                .limit(3)
                .toList();

            var goldUsers = highestScores.size() >= 1 ? getRankedUsers(topUsers, highestScores.get(0)) : Collections.<String>emptyList();
            var silverUsers = highestScores.size() >= 2 ? getRankedUsers(topUsers, highestScores.get(1)) : Collections.<String>emptyList();
            var bronzeUsers = highestScores.size() >= 3 ? getRankedUsers(topUsers, highestScores.get(2)) : Collections.<String>emptyList();

            return new TopUsers(goldUsers, silverUsers, bronzeUsers);
        }
    }

    private record PageViews(String title, int year, int month, int day, int views) {}
}
