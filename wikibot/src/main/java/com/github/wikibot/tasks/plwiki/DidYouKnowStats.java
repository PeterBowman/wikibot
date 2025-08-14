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
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
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

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.Section;
import com.github.wikibot.utils.CollectorUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PluralRules;
import com.thoughtworks.xstream.XStream;

final class DidYouKnowStats {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/DidYouKnowStats/");
    private static final String CATEGORY_TREE = "Ekspozycje Czywiesza %d";
    private static final String EXPO_TEMPLATE = "CW/weryfikacja";
    private static final Pattern CANDIDATE_PATT = Pattern.compile("^Czy wiesz/propozycje/\\d{4}-\\d{2}/.+$");
    private static final Pattern EXPO_ARCHIVE_PATT = Pattern.compile("^Wikiprojekt:Czy wiesz/archiwum/\\d{4}-\\d{2}$");
    private static final int PROJECT_NS = 102; // Wikiprojekt
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Europe/Warsaw");
    private static final String DYK_SUBPAGE_AUTHORS = "Wikiprojekt:Czy wiesz/Statystyki (%d)";
    private static final String DYK_SUBPAGE_POSTERS = "Wikiprojekt:Czy wiesz/Statystyki zgłaszających (%d)";
    private static final String DYK_SUBPAGE_REVIEWERS = "Wikiprojekt:Czy wiesz/Statystyki sprawdzających (%d)";
    private static final String DYK_SUBPAGE_PAGEVIEWS = "Wikiprojekt:Czy wiesz/Statystyki wyświetleń (%d)";
    private static final String DYK_SUBPAGE_PAGEVIEWS_TOP100 = "Wikiprojekt:Czy wiesz/Statystyki wyświetleń (%d)/TOP 100";
    private static final String DYK_SUBPAGE_TEMPLATE = "Wikiprojekt:Czy wiesz/statystyki-szablon";
    private static final String REST_URI_TEMPLATE = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/pl.wikipedia.org/all-access/user/%1$s/daily/%2$d/%2$d";
    private static final String NOTICEBOARD_PAGE = "Wikipedia:Tablica ogłoszeń/Ogłoszenia";
    private static final int NOTICEBOARD_MAX_DAYS_SPAN = 31;
    private static final int NOTICEBOARD_MIN_DAYS = 5;
    private static final int NOTICEBOARD_MAX_MENTIONS = 3;
    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    private static final Map<Long, String> MONTH_NAMES_GENITIVE = Map.ofEntries(
        Map.entry(1L, "stycznia"),
        Map.entry(2L, "lutego"),
        Map.entry(3L, "marca"),
        Map.entry(4L, "kwietnia"),
        Map.entry(5L, "maja"),
        Map.entry(6L, "czerwca"),
        Map.entry(7L, "lipca"),
        Map.entry(8L, "sierpnia"),
        Map.entry(9L, "września"),
        Map.entry(10L, "października"),
        Map.entry(11L, "listopada"),
        Map.entry(12L, "grudnia")
    );

    // https://stackoverflow.com/q/17188316
    private static final DateTimeFormatter NOTICEBOARD_HEADER_FORMATTER = new DateTimeFormatterBuilder()
        .appendValue(ChronoField.DAY_OF_MONTH)
        .appendLiteral(' ')
        .appendText(ChronoField.MONTH_OF_YEAR, MONTH_NAMES_GENITIVE)
        .toFormatter(Locale.forLanguageTag("pl-PL"));

    private static final DateTimeFormatter EXPO_DATE_FORMAT = new DateTimeFormatterBuilder()
        .appendLiteral("Czy wiesz/ekspozycje/")
        .appendValue(ChronoField.YEAR, 4)
        .appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .toFormatter();

    private static final Plural PLURAL_PL = new Plural(PluralRules.POLISH, new WordForms[] {
        new WordForms(new String[] {"artykuł", "artykuły", "artykułów"}),
        new WordForms(new String[] {"utworzona", "utworzone", "utworzonych"}),
        new WordForms(new String[] {"zgłoszona", "zgłoszone", "zgłoszonych"}),
        new WordForms(new String[] {"sprawdzona", "sprawdzone", "sprawdzonych"}),
    });

    private static final String NOTICEBOARD_SUMMARY_FMT =
        "* W ostatnim okresie (%s–%s) wyeksponowano %d %s na stronie głównej w ramach [[Wikiprojekt:Czy wiesz|projektu „Czy wiesz”]]. " +
        "Najaktywniejsze osoby w określonych obszarach:" +
        "<ul>" +
        "<li>tworzenie artykułów: %s;</li>" +
        "<li>zgłaszanie do ekspozycji: %s;</li>" +
        "<li>sprawdzanie zgłoszeń: %s.</li>" +
        "</ul>" +
        "Artykułem z największą liczbą wyświetleń (%d w dniu %s) był „[[%s]]”. " +
        "Więcej informacji można uzyskać na [[Wikiprojekt:Czy wiesz/statystyki|stronie statystyk projektu]]. " +
        "Dziękujemy wszystkim zaangażowanym! ~~~~";

    public static void main(String[] args) throws Exception {
        var lastDatePath = LOCATION.resolve("last_date.txt");
        var optLastDate = retrieveLastDate(lastDatePath);
        var now = OffsetDateTime.now().atZoneSameInstant(PROJECT_ZONE);
        var line = readOptions(args);
        var years = new ArrayList<Integer>();

        if (line.hasOption("year")) {
            if (line.hasOption("noticeboard")) {
                throw new IllegalArgumentException("Noticeboard option cannot be used with year option.");
            }

            var yearStr = line.getOptionValue("year");
            years.add(Integer.valueOf(yearStr));
        } else if (line.hasOption("update")) {
            if (optLastDate.isPresent()) {
                for (int year = optLastDate.get().getYear(); year <= now.getYear(); year++) {
                    years.add(year);
                }
            } else {
                years.add(now.getYear());
            }
        } else {
            throw new IllegalArgumentException("No option specified.");
        }

        Login.login(wb);

        var storage = new ArrayList<Entry>();

        for (var year : years) {
            System.out.println("Processing year " + year);
            var entries = processYear(year);
            System.out.printf("Got %d entries.%n", entries.size());

            var untilMonth = entries.stream().mapToInt(e -> e.date().getMonthValue()).max().getAsInt();

            updateAuthors(year, untilMonth, entries);
            updatePosters(year, untilMonth, entries);
            updateReviewers(year, untilMonth, entries);
            updatePageViews(year, untilMonth, entries);
            updateNavbox(year);

            if (line.hasOption("noticeboard")) {
                storage.addAll(entries);
            }
        }

        Files.writeString(lastDatePath, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        if (line.hasOption("noticeboard") && !storage.isEmpty()) {
            if (optLastDate.isPresent()) {
                var lastDate = optLastDate.get();

                if (
                    ChronoUnit.DAYS.between(lastDate, now) <= NOTICEBOARD_MAX_DAYS_SPAN &&
                    ChronoUnit.DAYS.between(lastDate, now) >= NOTICEBOARD_MIN_DAYS
                ) {
                    postOnNoticeboard(lastDate, now.minusDays(1), storage);
                } else {
                    System.out.println("Last update was too soon or too long ago, skipping noticeboard update.");
                }
            } else {
                System.out.println("No last date found, skipping noticeboard update.");
            }
        }
    }

    private static CommandLine readOptions(String[] args) {
        var options = new Options();
        options.addOption("y", "year", true, "year of archived threads to be queried");
        options.addOption("u", "update", false, "process new changes since last run");
        options.addOption("n", "noticeboard", false, "report to noticeboard");

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

    private static Optional<ZonedDateTime> retrieveLastDate(Path lastDatePath) {
        try {
            var contents = Files.readString(lastDatePath).trim();

            var parsed = OffsetDateTime.parse(contents, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(PROJECT_ZONE);

            return Optional.of(parsed);
        } catch (IOException | DateTimeParseException e) {
            System.out.println("Failed to retrieve last date: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static List<Entry> processYear(int year) throws IOException {
        var category = CATEGORY_TREE.formatted(year);
        var members = wb.getCategoryMembers(category, PROJECT_NS);

        var talkPages = members.stream()
            .map(wb::getTalkPage)
            .filter(title -> isValidExpoPage(title, year))
            .toList();

        var candidatePagesMap = getCandidatePagesMap(talkPages);
        var linkedArticles = getLinkedArticles(members);
        var redirectsToNewTitles = getRedirectMap(linkedArticles.stream().toList());

        var entries = new ArrayList<Entry>();

        for (var page : wb.getContentOfPages(candidatePagesMap.keySet())) {
            var talkPage = candidatePagesMap.get(page.title());
            var date = LocalDate.parse(wb.removeNamespace(talkPage), EXPO_DATE_FORMAT);

            for (var template : ParseUtils.getTemplatesIgnoreCase(EXPO_TEMPLATE, page.text())) {
                var params = ParseUtils.getTemplateParametersWithValue(template);

                var title = wb.normalize(params.getOrDefault("artykuł", ""));
                var poster = wb.normalize(params.getOrDefault("nominacja", ""));

                title = redirectsToNewTitles.getOrDefault(title, title);

                var authors = new ArrayList<String>();

                for (int i = 1; i <= 5; i++) {
                    var value = params.getOrDefault("%d. autorstwo".formatted(i), "").trim();

                    if (!value.isEmpty()) {
                        authors.add(wb.normalize(value));
                    }
                }

                if (StringUtils.isAnyBlank(title, poster) || authors.isEmpty() || !linkedArticles.contains(title)) {
                    continue;
                }

                var reviewers = new ArrayList<String>();

                for (int i = 1; i <= 9; i++) {
                    var value = params.getOrDefault("%d. sprawdzenie".formatted(i), "?").trim();

                    // ugly hack for extra trailing braces, e.g.: "...|Jamnik z Tarnowa}}}"
                    value = StringUtils.stripEnd(value, "}");

                    if (!value.isEmpty() && !value.equals("?")) {
                        reviewers.add(wb.normalize(value));
                    }
                }

                var entry = new Entry(title, authors, poster, Collections.unmodifiableList(reviewers), date);
                entries.add(entry);
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

    private static Map<String, String> getCandidatePagesMap(List<String> titles) throws IOException {
        var map = new HashMap<String, String>();
        var candidatePages = wb.getTemplates(titles, PROJECT_NS);

        for (var i = 0; i < titles.size(); i++) {
            var title = titles.get(i);

            candidatePages.get(i).stream()
                .filter(t -> CANDIDATE_PATT.matcher(wb.removeNamespace(t)).matches())
                .forEach(t -> map.put(t, title));
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

        var groupedUsers = entries.stream().collect(CollectorUtils.groupingByFlattened(
            e -> e.authors().stream(),
            Collectors.groupingBy(e -> e.date().getMonthValue(), Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            e -> e.date().getMonthValue(),
            CollectorUtils.groupingByFlattened(e -> e.authors().stream(), Collectors.counting())
        ));

        var contents = makeTableContents(groupedUsers, groupedMonths, untilMonth);
        var text = template.formatted(contents);

        wb.edit(DYK_SUBPAGE_AUTHORS.formatted(year), text, "aktualizacja");
    }

    private static void updatePosters(int year, int untilMonth, List<Entry> entries) throws IOException, LoginException {
        var template = makeIntro("Najwięcej zgłoszeń", "zgłoszeń", "jedno zgłoszenie", year);

        var groupedUsers = entries.stream().collect(Collectors.groupingBy(
            Entry::poster,
            Collectors.groupingBy(e -> e.date().getMonthValue(), Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            e -> e.date().getMonthValue(),
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
            Collectors.groupingBy(e -> e.date().getMonthValue(), Collectors.counting())
        ));

        var groupedMonths = entries.stream().collect(Collectors.groupingBy(
            e -> e.date().getMonthValue(),
            CollectorUtils.groupingByFlattened(e -> e.reviewers().stream(), Collectors.counting())
        ));

        var contents = makeTableContents(groupedUsers, groupedMonths, untilMonth);
        var text = template.formatted(contents);

        wb.edit(DYK_SUBPAGE_REVIEWERS.formatted(year), text, "aktualizacja");
    }

    private static void updatePageViews(int year, int untilMonth, List<Entry> entries) throws IOException, InterruptedException, LoginException {
        var pageViews = updatePageViewsStorage(year, entries);

        if (pageViews.isEmpty()) {
            return;
        }

        var text = makePageViewsText(year, untilMonth, pageViews);
        wb.edit(DYK_SUBPAGE_PAGEVIEWS.formatted(year), text, "aktualizacja");

        var top100Text = makePageViewsTop100Text(year, untilMonth, pageViews);
        wb.edit(DYK_SUBPAGE_PAGEVIEWS_TOP100.formatted(year), top100Text, "aktualizacja");
    }

    private static List<PageViews> updatePageViewsStorage(int year, List<Entry> entries) throws IOException, InterruptedException {
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

        if (!filtered.isEmpty()) {
            var client = HttpClient.newHttpClient();
            var jsonPointer = JSONPointer.builder().append("items").append(0).append("views").build();

            var requestBuilder = HttpRequest.newBuilder()
                .header("User-Agent", Login.getUserAgent())
                .header("Accept", "application/json")
                .GET();

            for (var entry : filtered) {
                var urlStr = URLEncoder.encode(entry.title(), StandardCharsets.UTF_8);
                var uri = URI.create(REST_URI_TEMPLATE.formatted(urlStr, entry.getTimestamp()));
                var request = requestBuilder.uri(uri).build();
                var response = getResponse(client, request);
                var json = new JSONObject(response.body());
                var optViews = Optional.ofNullable((Integer)json.optQuery(jsonPointer));

                if (optViews.isPresent()) {
                    storage.computeIfAbsent(entry.getTimestamp(), k -> new HashMap<>()).put(entry.title(), optViews.get());
                }

                System.out.printf("Queried pageviews for %s (%d)%n", entry.title(), entry.getTimestamp());

                // REST API rules state that no more than 100 requests per *second* are allowed, so 10 ms is the hard limit
                Thread.sleep(100);
            }

            var currentTitles = entries.stream().map(Entry::title).collect(Collectors.toSet());

            // some titles might have been renamed
            storage.values().forEach(map -> map.keySet().removeIf(title -> !currentTitles.contains(title)));

            // shouldn't happen, maybe the pages were deleted instead
            storage.values().removeIf(Map::isEmpty);

            Files.writeString(storagePath, xstream.toXML(storage));
        }

        var pageViews = new ArrayList<PageViews>();

        storage.entrySet().stream()
            .forEach(e -> e.getValue().entrySet().stream()
                .map(ee -> new PageViews(
                    ee.getKey(),
                    LocalDate.of(year, extractMonth(e.getKey()), extractDay(e.getKey())),
                    ee.getValue())
                )
                .forEach(pageViews::add)
            );

        return pageViews;
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
                pv -> pv.date().getMonthValue(),
                Collectors.toCollection(() -> new TreeSet<>(Comparator.comparingInt(PageViews::views)
                    .reversed()
                    .thenComparing(pv -> pv.date().getDayOfMonth())
                ))
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
            .sorted(Comparator.comparingInt(PageViews::views).reversed().thenComparing(pv -> pv.date().getDayOfMonth()))
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
            sb.append("|[[Wikiprojekt:Czy wiesz/ekspozycje/%1$s|%1$s]]\n".formatted(
                DateTimeFormatter.ISO_LOCAL_DATE.format(entry.date())
            ));
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

    private static void postOnNoticeboard(ZonedDateTime startDate, ZonedDateTime endDate, List<Entry> entries) throws IOException, InterruptedException, LoginException {
        var filtered = entries.stream()
            .filter(e -> !e.date().isBefore(startDate.toLocalDate()) && !e.date().isAfter(endDate.toLocalDate()))
            .toList();

        var authors = filtered.stream()
            .collect(CollectorUtils.groupingByFlattened(e -> e.authors().stream(), Collectors.counting()));

        var posters = filtered.stream()
            .collect(Collectors.groupingBy(Entry::poster, Collectors.counting()));

        var reviewers = filtered.stream()
            .collect(CollectorUtils.groupingByFlattened(e -> e.reviewers().stream(), Collectors.counting()));

        var topAuthors = TopUsers.fromMap(authors);
        var topPosters = TopUsers.fromMap(posters);
        var topReviewers = TopUsers.fromMap(reviewers);

        var pageViews = new ArrayList<PageViews>(); // *all* of them, not limited to `filtered`

        for (var year : filtered.stream().map(e -> e.date().getYear()).distinct().toList()) {
            var pageViewsStorage = updatePageViewsStorage(year, filtered);
            pageViews.addAll(pageViewsStorage);
        }

        var topPageViews = pageViews.stream()
            .filter(pv -> !pv.date().isBefore(startDate.toLocalDate()) && !pv.date().isAfter(endDate.toLocalDate()))
            .sorted(Comparator.comparingInt(PageViews::views).reversed())
            .findFirst()
            .get();

        var dateFormatter = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('.')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .toFormatter();

        var text = NOTICEBOARD_SUMMARY_FMT.formatted(dateFormatter.format(startDate),
                                                     dateFormatter.format(endDate),
                                                     filtered.size(),
                                                     PLURAL_PL.pl(filtered.size(), "artykuł"),
                                                     topAuthors.stringifyRanking(),
                                                     topPosters.stringifyRanking(),
                                                     topReviewers.stringifyRanking(),
                                                     topPageViews.views(),
                                                     dateFormatter.format(topPageViews.date()),
                                                     topPageViews.title());

        var today = OffsetDateTime.now().atZoneSameInstant(PROJECT_ZONE);
        var pc = wb.getContentOfPages(List.of(NOTICEBOARD_PAGE)).get(0);
        var page = Page.wrap(pc);
        var firstSection = page.getAllSections().get(0);
        var header = NOTICEBOARD_HEADER_FORMATTER.format(today);

        if (firstSection.getHeader().equals(header)) {
            firstSection.setIntro(firstSection.getIntro().stripTrailing() + "\n" + text);
        } else {
            var newSection = Section.create(header, 2);
            newSection.setIntro(text);
            page.prependSections(List.of(newSection));
        }

        wb.edit(NOTICEBOARD_PAGE, page.toString(), "raport CzyWiesza", pc.timestamp());
    }

    private record Entry(String title, List<String> authors, String poster, List<String> reviewers, LocalDate date) {
        int getTimestamp() {
            return Integer.parseInt("%04d%02d%02d".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
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

    private record TopUsers(List<String> gold, List<String> silver, List<String> bronze, long goldValue, long silverValue, long bronzeValue) {
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

            return new TopUsers(goldUsers, silverUsers, bronzeUsers,
                                highestScores.size() >= 1 ? highestScores.get(0) : 0L,
                                highestScores.size() >= 2 ? highestScores.get(1) : 0L,
                                highestScores.size() >= 3 ? highestScores.get(2) : 0L);
        }

        public String stringifyRanking() {
            var out = new ArrayList<String>();

            appendTopUsers(gold, goldValue, out);
            appendTopUsers(silver, silverValue, out);
            appendTopUsers(bronze, bronzeValue, out);

            return joinItemsAsNaturalLanguage(out, "oraz");
        }
    }

    private static void appendTopUsers(List<String> users, long value, List<String> out) {
        if (!users.isEmpty()) {
            var linked = users.stream()
                .map(user -> "[[User:%1$s|%1$s]]".formatted(user))
                .limit(NOTICEBOARD_MAX_MENTIONS)
                .collect(Collectors.toCollection(ArrayList::new));

            if (users.size() > NOTICEBOARD_MAX_MENTIONS) {
                linked.add("in.");
            }

            var temp = joinItemsAsNaturalLanguage(linked, "i");
            out.add("%s (%d)".formatted(temp, value));
        }
    }

    // https://www.baeldung.com/java-string-concatenation-natural-language
    private static String joinItemsAsNaturalLanguage(List<String> elements, String conjunction) {
        if (elements.size() < 3) {
            return String.join(" " + conjunction + " ", elements);
        }

        // list has at least three elements
        int lastIdx = elements.size() - 1;

        var sb = new StringBuilder();

        return sb.append(String.join(", ", elements.subList(0, lastIdx)))
          .append(" " + conjunction + " ")
          .append(elements.get(lastIdx))
          .toString();
    }

    private record PageViews(String title, LocalDate date, int views) {}
}
