package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.Section;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public final class ReportReviewerActivity {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/ReportReviewerActivity/");

    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";
    private static final String TARGET_PAGE = "Wikipedia:Tablica ogłoszeń/Ogłoszenia";

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
    private static final DateTimeFormatter HEADER_FORMATTER = new DateTimeFormatterBuilder()
        .appendValue(ChronoField.DAY_OF_MONTH)
        .appendLiteral(' ')
        .appendText(ChronoField.MONTH_OF_YEAR, MONTH_NAMES_GENITIVE)
        .toFormatter(Locale.forLanguageTag("pl-PL"));

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final int REVIEW_COUNT_THRESHOLD = 120;
    private static final int REVIEWER_SUMMARY_LIMIT = 10;

    private static final Locale LOCALE_PL = Locale.forLanguageTag("pl-PL");

    private static final Plural PLURAL_PL = new Plural(PluralRules.POLISH, new WordForms[] {
        new WordForms(new String[] {"zaakceptował", "zaakceptowali", "zaakceptowało"}),
        new WordForms(new String[] {"redaktor", "redaktorzy", "redaktorów"}),
        new WordForms(new String[] {"Przejrzana", "Przejrzane", "Przejrzanych"}),
        new WordForms(new String[] {"została", "zostały", "zostało"}),
        new WordForms(new String[] {"edycja", "edycje", "edycji"}),
        new WordForms(new String[] {"oczekuje", "oczekują", "oczekuje"}),
        new WordForms(new String[] {"zmieniony", "zmienione", "zmienionych"}),
        new WordForms(new String[] {"nowy", "nowe", "nowych"}),
        new WordForms(new String[] {"artykuł", "artykuły", "artykułów"})
    });

    private static final LocalizedNumberFormatter NUMBER_FORMAT_PL = NumberFormatter.withLocale(LOCALE_PL).grouping(GroupingStrategy.MIN2);

    private static final String SUMMARY_FMT =
        "* Informacja nt. [[Wikipedia:Wersje przejrzane|wersji przejrzanych]] artykułów: przynajmniej jedną edycję w minionym tygodniu (%s–%s) %s %s %s. " +
        "%s %s %s %s. " +
        "Na sprawdzenie [[Specjalna:Statystyki oznaczania|%s]] %s %s i %s %s %s. " +
        "Wersje przejrzane mamy w %s%% artykułów. [https://tools.wikimedia.pl/~masti/review.html#reviewers168h Ponad %s edycji] udostępnili%s: %s. " +
        "Dziękujemy przeglądającym! ~~~~";

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    public static void main(String[] args) throws Exception {
        var ref = ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Europe/Warsaw")).minusWeeks(1);

        var startDate = ref.with(DayOfWeek.MONDAY).with(LocalTime.MIN);
        var endDate = ref.with(DayOfWeek.SUNDAY).with(LocalTime.MAX);

        var startTimestamp = Long.parseLong(TIMESTAMP_FORMATTER.format(startDate));
        var endTimestamp = Long.parseLong(TIMESTAMP_FORMATTER.format(endDate));

        System.out.printf("Start: %s, end: %s%n", startTimestamp, endTimestamp);

        var path = LOCATION.resolve("last_timestamp.txt");

        if (Files.exists(path)) {
            var stored = Long.parseLong(Files.readString(path).trim());

            if (stored >= startTimestamp) {
                System.out.printf("Already reported on %s; reference date: %s%n", stored, ref);
                return;
            }
        }

        System.out.println("Reporting: " + ref);

        var rows = queryReviewers(startTimestamp, endTimestamp);
        rows.forEach(System.out::println);

        var stats = queryStats();
        System.out.println(stats);

        Login.login(wb);

        var pc = wb.getContentOfPages(List.of(TARGET_PAGE)).get(0);
        var page = Page.wrap(pc);
        var firstSection = page.getAllSections().get(0);
        var header = HEADER_FORMATTER.format(LocalDate.now());
        var summary = makeSummary(startDate.toLocalDate(), endDate.toLocalDate(), rows, stats);

        if (firstSection.getHeader().equals(header)) {
            firstSection.setIntro(summary + "\n" + firstSection.getIntro());
        } else {
            var newSection = Section.create(header, 2);
            newSection.setIntro(summary);
            page.prependSections(List.of(newSection));
        }

        wb.edit(TARGET_PAGE, page.toString(), "raport oznaczania artykułów", pc.getTimestamp());

        Files.writeString(path, TIMESTAMP_FORMATTER.format(ref));
    }

    private static Connection getConnection() throws ClassNotFoundException, IOException, SQLException {
        Class.forName("com.mysql.cj.jdbc.Driver");

        var props = DBUtils.prepareSQLProperties();

        try {
            return DriverManager.getConnection(SQL_PLWIKI_URI_SERVER, props);
        } catch (SQLException e) {
            return DriverManager.getConnection(SQL_PLWIKI_URI_LOCAL, props);
        }
    }

    private static List<Row> queryReviewers(long start, long end) throws SQLException, IOException, ClassNotFoundException {
        var out = new ArrayList<Row>();

        try (var connection = getConnection()) {
            // masti: https://github.com/masti01/pywikipedia/blob/main/app/review.py
            // MW: https://github.com/wikimedia/mediawiki-extensions-FlaggedRevs/blob/5c2d3a26/frontend/FlaggedRevsReviewLogFormatter.php#L11-L23
            // supported actions: https://www.mediawiki.org/wiki/Manual:Log_actions#Actions
            // full list as of 2023-10-08: approve, approve-a, approve-i, approve-ia, approve2, approve2-a, approve2-i, approve2-ia, unapprove, unapprove2
            // supported on plwiki as of 2023-10-08: approve, approve-i, approve-ia (obsolete since 2021-07), approve2 (obsolete), approve2-i (obsolete), unapprove

            final var query = """
                SELECT
                    actor_name,
                    COUNT(log_action) AS total,
                    SUM(log_action = "approve-i") AS new,
                    SUM(log_action = "approve") AS other,
                    SUM(log_action = "unapprove") AS unapproved
                FROM logging
                    INNER JOIN actor ON actor_id = log_actor
                WHERE
                    log_type = "review" AND
                    log_namespace = 0 AND
                    log_action NOT LIKE "%%a" AND
                    log_timestamp BETWEEN %d AND %d
                GROUP BY
                    log_actor
                ORDER BY
                    total DESC,
                    other DESC,
                    new DESC
                """.formatted(start, end);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                out.add(new Row(rs.getString("actor_name"), rs.getInt("total"), rs.getInt("new"), rs.getInt("other"), rs.getInt("unapproved")));
            }
        }

        return out;
    }

    private static Stats queryStats() throws ClassNotFoundException, SQLException, IOException {
        var optTotal = Optional.<Integer>empty();
        var optReviewed = Optional.<Integer>empty();
        var optSynced = Optional.<Integer>empty();

        try (var connection = getConnection()) {
            final var query = """
                SELECT
                    frs_stat_key,
                    frs_stat_val
                FROM flaggedrevs_statistics
                WHERE
                    frs_timestamp = (
                        SELECT frs_timestamp
                        FROM flaggedrevs_statistics
                        ORDER BY frs_timestamp desc
                        LIMIT 1
                    ) AND
                    frs_stat_key IN ("totalPages-NS:0", "reviewedPages-NS:0", "syncedPages-NS:0")
                """;

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                switch (rs.getString("frs_stat_key")) {
                    case "totalPages-NS:0" -> optTotal = Optional.of(rs.getInt("frs_stat_val"));
                    case "reviewedPages-NS:0" -> optReviewed = Optional.of(rs.getInt("frs_stat_val"));
                    case "syncedPages-NS:0" -> optSynced = Optional.of(rs.getInt("frs_stat_val"));
                }
            }
        }

        if (optTotal.isEmpty() || optReviewed.isEmpty() || optSynced.isEmpty()) {
            throw new IllegalStateException("Missing stats");
        }

        return new Stats(optTotal.get(), optReviewed.get(), optSynced.get());
    }

    private static String formatNum(int num) {
        return NUMBER_FORMAT_PL.format(num).toString().replace(" ", "&nbsp;");
    }

    private static String makeSummary(LocalDate startDate, LocalDate endDate, List<Row> rows, Stats stats) {
        // https://stackoverflow.com/a/34936891
        var topUsers = rows.stream()
            .filter(r -> r.total() > REVIEW_COUNT_THRESHOLD)
            .limit(REVIEWER_SUMMARY_LIMIT)
            .map(r -> String.format("[[Wikipedysta:%1$s|%1$s]] (%2$s)", r.user(), formatNum(r.total())))
            .toList();

        var wasLimited = rows.stream().filter(r -> r.total() > REVIEW_COUNT_THRESHOLD).count() > REVIEWER_SUMMARY_LIMIT;

        var last = topUsers.size() - 1;
        var top = String.join(" i ", String.join(", ", topUsers.subList(0, last)), topUsers.get(last));

        var reviews = rows.stream().mapToInt(Row::total).sum();

        var pending = stats.reviewed() - stats.synced();
        var unreviewed = stats.total() - stats.reviewed();

        return String.format(SUMMARY_FMT,
                             String.format("%d.%d", startDate.getDayOfMonth(), startDate.getMonthValue()),
                             String.format("%d.%d", endDate.getDayOfMonth(), endDate.getMonthValue()),
                             PLURAL_PL.pl(rows.size(), "zaakceptował"),
                             formatNum(rows.size()),
                             PLURAL_PL.pl(rows.size(), "redaktor"),
                             PLURAL_PL.pl(reviews, "Przejrzana"),
                             PLURAL_PL.pl(reviews, "została"),
                             formatNum(reviews),
                             PLURAL_PL.pl(reviews, "edycja"),
                             PLURAL_PL.pl(pending, "oczekuje"),
                             formatNum(pending),
                             PLURAL_PL.pl(pending, "zmieniony"),
                             formatNum(unreviewed),
                             PLURAL_PL.pl(unreviewed, "nowy"),
                             PLURAL_PL.pl(unreviewed, "artykuł"),
                             String.format(LOCALE_PL, "%.2f", 100.0 * stats.synced() / stats.total()),
                             formatNum(REVIEW_COUNT_THRESHOLD),
                             wasLimited ? " m.in." : "",
                             top
                            );
    }

    private record Row(String user, int total, int create, int other, int unapprove) {}

    private record Stats(int total, int reviewed, int synced) {}
}
