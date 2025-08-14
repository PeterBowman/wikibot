package com.github.wikibot.webapp;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.json.JSONObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class PlwiktStatisticsService extends HttpServlet {
    private static final String TARGET_CATEGORY = "Indeks słów wg języków";
    private static final int EXPIRY_TIME_MSECS = 1000 * 60 * 5; // 5 minutes
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final List<String> ALLOWED_ORIGINS = List.of(
        "https://pl.wiktionary.org", "https://pl.m.wiktionary.org"
    );

    private int cachedResult;
    private long lastUpdate;
    private boolean isRunning;

    private DataSource dataSource;

    @Override
    public void init() throws ServletException {
        try {
            var context = (Context) new InitialContext().lookup("java:comp/env");
            dataSource = (DataSource) context.lookup("jdbc/plwiktionary-web");
        } catch (NamingException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var origin = request.getHeader("origin");

        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
        }

        response.setContentType("application/json");

        var now = System.currentTimeMillis();

        int localCachedResult;
        long localLastUpdate;

        final boolean shouldReturnCached;

        synchronized (this) {
            localCachedResult = cachedResult;
            localLastUpdate = lastUpdate;

            if (now - lastUpdate < EXPIRY_TIME_MSECS || isRunning) {
                shouldReturnCached = true;
            } else {
                shouldReturnCached = false;
                isRunning = true;
            }
        }

        if (shouldReturnCached) {
            response.getWriter().print(makeOutput(localCachedResult, true, localLastUpdate));
            return;
        }

        // only one thread can reach this point concurrently

        try {
            localCachedResult = getStats();
            localLastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            e.printStackTrace();
        }

        synchronized (this) {
            cachedResult = localCachedResult;
            lastUpdate = localLastUpdate;
            isRunning = false;
        }

        response.getWriter().print(makeOutput(localCachedResult, false, localLastUpdate));
    }

    private int getStats() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var query = """
                SELECT
                    SUM(cat_pages - cat_subcats - cat_files) AS total_entries
                FROM page
                    INNER JOIN categorylinks ON cl_from = page_id
                    INNER JOIN category ON cat_title = page_title
                WHERE
                    cl_to = '%s'
                    AND page_namespace = 14;
                """.formatted(TARGET_CATEGORY.replace(' ', '_'));

            var statement = connection.createStatement();
            var resultSet = statement.executeQuery(query);

            if (resultSet.next()) {
                return resultSet.getInt("total_entries");
            }
        }

        throw new RuntimeException("no results");
    }

    private JSONObject makeOutput(int value, boolean isCached, long timestamp) {
        var instant = Instant.ofEpochMilli(timestamp);

        return new JSONObject(Map.of(
            "canonical", value,
            "cached", isCached,
            "timestamp", Long.valueOf(DATE_TIME_FORMATTER.format(instant))
        ));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }
}
