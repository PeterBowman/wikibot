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
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

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

    private static final long START_TIMESTAMP;

    static {
        var now = OffsetDateTime.now();
        var then = now.minusMonths(MONTHS_TO_ANALYZE);
        var fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

        START_TIMESTAMP = Long.parseLong(fmt.format(then));
    }

    public static void main(String[] args) throws Exception {
        var activity = getUserActivity();

        var report = activity.stream()
            .collect(Collectors.groupingBy(UserActivity::userName))
            .entrySet()
            .stream()
            .map(entry -> analyze(entry.getKey(), entry.getValue()))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(ReportEntry::lowActivityMonths).reversed()
                .thenComparing(Comparator.comparingInt(ReportEntry::normalActivityMonths).reversed())
                .thenComparing(ReportEntry::userName))
            .toList();

        var html = makeHtml(report);

        Files.writeString(LOCATION.resolve("report.html"), html);
    }

    private static ReportEntry analyze(String userName, List<UserActivity> activity) {
        var monthlyEdits = activity.stream().collect(Collectors.toMap(
            UserActivity::period,
            UserActivity::edits,
            Integer::sum,
            TreeMap::new
        ));

        var earliestActivityMonth = monthlyEdits.lastEntry().getValue();
        var currentMonth = 0;

        var lowActivityMonths = 0;
        var lowActivityEdits = 0;

        while (currentMonth <= earliestActivityMonth) {
            var edits = monthlyEdits.getOrDefault(currentMonth, 0);

            if (edits > MAX_LOW_ACTIVITY_EDITS) {
                break;
            }

            lowActivityMonths++;
            lowActivityEdits += edits;
            currentMonth++;
        }

        if (lowActivityMonths < LOW_ACTIVITY_MONTHS) {
            return null;
        }

        var normalActivityMonths = 0;
        var normalActivityEdits = 0;

        while (currentMonth <= earliestActivityMonth) {
            var edits = monthlyEdits.getOrDefault(currentMonth, 0);

            if (edits < MIN_NORMAL_ACTIVITY_EDITS) {
                break;
            }

            normalActivityMonths++;
            normalActivityEdits += edits;
            currentMonth++;
        }

        if (normalActivityMonths < NORMAL_ACTIVITY_MONTHS) {
            return null;
        }

        return new ReportEntry(userName, lowActivityMonths, lowActivityEdits, normalActivityMonths, normalActivityEdits);
    }

    private static String formatRow(ReportEntry entry) {
        return "<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>".formatted(
            entry.userName(),
            entry.lowActivityMonths(),
            entry.lowActivityEdits(),
            entry.normalActivityMonths(),
            entry.normalActivityEdits()
        );
    }

    private static String makeHtml(List<ReportEntry> report) {
        var rows = report.stream()
            .map(NewbieActivityReport::formatRow)
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
                rev_timestamp >= %d
            group by
                user_name,
                months_ago
            """.formatted(START_TIMESTAMP);

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

    record ReportEntry(String userName, int lowActivityMonths, int lowActivityEdits, int normalActivityMonths, int normalActivityEdits) {}
}
