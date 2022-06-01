package com.github.wikibot.tasks.eswikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.github.wikibot.utils.DBUtils;

public final class LonelyPages {
    private static final Path LOCATION = Paths.get("./data/tasks.eswikt/LonelyPages/");
    private static final String SQL_ESWIKT_URI = "jdbc:mysql://eswiktionary.analytics.db.svc.wikimedia.cloud:3306/eswiktionary_p";

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        var lonelyPages = new ArrayList<String>(75000);

        try (var eswiktConn = DriverManager.getConnection(SQL_ESWIKT_URI, DBUtils.prepareSQLProperties())) {
            var start = System.currentTimeMillis();
            fetchLonelyPages(eswiktConn, lonelyPages);
            var elapsed = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("%d titles fetched in %.3f seconds.%n", lonelyPages.size(), elapsed);
        }

        storeData(lonelyPages);
    }

    private static void fetchLonelyPages(Connection conn, List<String> list) throws SQLException {
        var query = """
            SELECT
                page_title
            FROM page
                LEFT JOIN linktarget ON
                    lt_title = page_title AND
                    lt_namespace = page_title
                LEFT JOIN pagelinks ON
                    pl_namespace = page_namespace AND
                    pl_title = page_title
                LEFT JOIN templatelinks ON
                    tl_target_id = lt_id
            WHERE
                page_namespace = 0 AND
                page_is_redirect = 0 AND
                pl_from IS NULL AND
                tl_from IS NULL;
            """;

        var stmt = conn.createStatement();
        var rs = stmt.executeQuery(query);

        while (rs.next()) {
            var title = rs.getString("page_title").replace("_", " ");
            list.add(title);
        }
    }

    private static void storeData(List<String> list) throws IOException {
        var data = LOCATION.resolve("data.txt");
        var ctrl = LOCATION.resolve("UPDATED");
        var timestamp = LOCATION.resolve("timestamp.txt");

        if (!Files.exists(data) || list.hashCode() != Files.readAllLines(data).hashCode()) {
            Files.write(data, list);
            Files.writeString(timestamp, OffsetDateTime.now().toString());
            Files.deleteIfExists(ctrl);
        }
    }
}
