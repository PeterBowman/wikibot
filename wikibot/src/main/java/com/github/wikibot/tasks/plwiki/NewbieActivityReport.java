package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.mutable.MutableInt;

import com.github.wikibot.utils.DBUtils;

public final class NewbieActivityReport {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/NewbieActivityReport/");

    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";

    private static final int NORMAL_ACTIVITY_MONTHS = 3;
    private static final int LOW_ACTIVITY_MONTHS = 3;

    private static final int MIN_NORMAL_ACTIVITY_EDITS = 20;
    private static final int MAX_LOW_ACTIVITY_EDITS = 5;

    private static final int MONTHS_TO_ANALYZE = 24;

    private static final long EARLIEST_TIMESTAMP;
    private static final long LATEST_TIMESTAMP;

    static {
        var now = OffsetDateTime.now();
        var then = now.minusMonths(MONTHS_TO_ANALYZE);
        var fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        EARLIEST_TIMESTAMP = Long.parseLong(fmt.format(then));
        LATEST_TIMESTAMP = Long.parseLong(fmt.format(now));
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("Generating report: from %d to %d%n", EARLIEST_TIMESTAMP, LATEST_TIMESTAMP);

        var activity = getUserActivity();
        System.out.printf("Total entries: %d%n", activity.size());

        makeReport(activity, "report-decrease", true);
        makeReport(activity, "report-increase", false);
    }

    private static void makeReport(List<UserActivity> activity, String filename, boolean isDecrease) throws IOException {
        var report = activity.stream()
            .collect(Collectors.groupingBy(UserActivity::userName))
            .entrySet()
            .stream()
            .map(entry -> analyze(entry.getKey(), entry.getValue(), isDecrease))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(ReportEntry::lowActivityMonths).reversed()
                .thenComparing(Comparator.comparingInt(ReportEntry::normalActivityMonths).reversed())
                .thenComparing(ReportEntry::userName))
            .toList();

        var html = makeHtml(report);
        Files.writeString(LOCATION.resolve(filename + ".html"), html);

        var wikitable = makeWikitable(report);
        Files.writeString(LOCATION.resolve(filename + ".txt"), wikitable);
    }

    private static ReportEntry analyze(String userName, List<UserActivity> activity, boolean isDecrease) {
        var monthlyEdits = activity.stream().collect(Collectors.toMap(
            UserActivity::period,
            UserActivity::edits,
            Integer::sum,
            TreeMap::new
        ));

        var earliestActivityMonth = monthlyEdits.lastKey();
        var currentMonth = new MutableInt(0);

        var lowActivityMonths = new MutableInt(0);
        var lowActivityEdits = new MutableInt(0);

        var normalActivityMonths = new MutableInt(0);
        var normalActivityEdits = new MutableInt(0);

        if (isDecrease) {
            iterateMonths(monthlyEdits, earliestActivityMonth, currentMonth, lowActivityMonths, lowActivityEdits, edits -> edits <= MAX_LOW_ACTIVITY_EDITS);
            iterateMonths(monthlyEdits, earliestActivityMonth, currentMonth, normalActivityMonths, normalActivityEdits, edits -> edits >= MIN_NORMAL_ACTIVITY_EDITS);
        } else {
            iterateMonths(monthlyEdits, earliestActivityMonth, currentMonth, normalActivityMonths, normalActivityEdits, edits -> edits >= MIN_NORMAL_ACTIVITY_EDITS);
            iterateMonths(monthlyEdits, earliestActivityMonth, currentMonth, lowActivityMonths, lowActivityEdits, edits -> edits <= MAX_LOW_ACTIVITY_EDITS);
        }

        if (lowActivityMonths.intValue() < LOW_ACTIVITY_MONTHS || normalActivityMonths.intValue() < NORMAL_ACTIVITY_MONTHS) {
            return null;
        }

        return new ReportEntry(userName, lowActivityMonths.intValue(), lowActivityEdits.intValue(), normalActivityMonths.intValue(), normalActivityEdits.intValue());
    }

    private static void iterateMonths(Map<Integer, Integer> monthlyEdits, int limit, MutableInt current, MutableInt months, MutableInt edits, Predicate<Integer> cond) {
        while (current.intValue() <= limit) {
            var monthEdits = monthlyEdits.getOrDefault(current.intValue(), 0);

            if (!cond.test(monthEdits)) {
                break;
            }

            months.increment();
            edits.add(monthEdits);
            current.increment();
        }
    }

    private static String makeHtml(List<ReportEntry> report) {
        var rows = report.stream()
            .map(ReportEntry::formatHtmlRow)
            .collect(Collectors.joining("\n"));

        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"></head>
            <body><table border="1">
            <tr>
            <th>Nazwa użytkownika</th>
            <th>Miesiące niskiej aktywności</th>
            <th>Edycje podczas niskiej aktywności</th>
            <th>Miesiące dużej aktywności</th>
            <th>Edycje podczas dużej aktywności</th>
            </tr>
            %s
            </table></body></html>
            """.formatted(rows);
    }

    private static String makeWikitable(List<ReportEntry> report) {
        var rows = report.stream()
            .map(ReportEntry::formatWikitableRow)
            .collect(Collectors.joining("\n"));

        return """
            {| class="wikitable sortable"
            ! Nazwa użytkownika
            ! Miesiące niskiej aktywności
            ! Edycje podczas niskiej aktywności
            ! Miesiące dużej aktywności
            ! Edycje podczas dużej aktywności
            %s
            |}
            """.formatted(rows);
    }

    private static List<UserActivity> getUserActivity() throws ClassNotFoundException, SQLException, IOException {
        var list = new ArrayList<UserActivity>();

        var query = """
            select
                user_name,
                floor((unix_timestamp() - unix_timestamp(rev_timestamp)) / (86400 * 30)) AS months_ago,
                count(*) as monthly_edit_count
            from user
                inner join actor on actor_user = user_id
                inner join revision on rev_actor = actor_id
                left join user_groups on ug_user = user_id and ug_group = "bot"
            where
                user_is_temp = 0 and
                ug_user is null and
                user_name not like "Renamed user %%" and
                rev_timestamp >= %d and
                rev_timestamp <= %d
            group by
                user_name,
                months_ago
            """.formatted(EARLIEST_TIMESTAMP, LATEST_TIMESTAMP);

        try (var connection = getConnection()) {
            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var userName = rs.getString("user_name");
                var monthsAgo = rs.getInt("months_ago");
                var monthlyEditCount = rs.getInt("monthly_edit_count");

                list.add(new UserActivity(userName, monthsAgo, monthlyEditCount));
            }
        }

        return list;
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

    record UserActivity(String userName, int period, int edits) {}

    record ReportEntry(String userName, int lowActivityMonths, int lowActivityEdits, int normalActivityMonths, int normalActivityEdits) {
        String formatHtmlRow() {
            return "<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>".formatted(
                userName(),
                lowActivityMonths(),
                lowActivityEdits(),
                normalActivityMonths(),
                normalActivityEdits()
            );
        }

        String formatWikitableRow() {
            return "|-\n| [[User:%1$s|%1$s]]\n| %2$d\n| %3$d\n| %4$d\n| %5$d".formatted(
                userName(),
                lowActivityMonths(),
                lowActivityEdits(),
                normalActivityMonths(),
                normalActivityEdits()
            );
        }
    }
}
