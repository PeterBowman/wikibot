package com.github.wikibot.webapp;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.UncheckedIOException;
import org.wikipedia.Wiki;

public class UnreviewedPages extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String JSP_DISPATCH_TARGET = "/jsp/unreviewed-pages.jsp";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int DEFAULT_LIMIT = 500;

    private Map<String, DataSource> dataSources = new HashMap<>();
    private Map<String, Wiki> wikis = new HashMap<>();

    @Override
    public void init() throws ServletException {
        try {
            var context = (Context) new InitialContext().lookup("java:comp/env");

            dataSources.put("plwiktionary", (DataSource) context.lookup("jdbc/plwiktionary-web"));
            dataSources.put("plwiki", (DataSource) context.lookup("jdbc/plwiki-web"));

            wikis.put("plwiktionary", Wiki.newSession("pl.wiktionary.org"));
            wikis.put("plwiki", Wiki.newSession("pl.wikipedia.org"));
        } catch (NamingException | SecurityException e) {
            throw new UnavailableException(e.getMessage());
        }

        try {
            for (var wiki : wikis.values()) {
                wiki.namespace("test"); // populate namespace cache
            }
        } catch (UncheckedIOException e) {
            throw new UnavailableException(e.getMessage());
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var optProject = Optional.ofNullable(request.getParameter("project")).filter(StringUtils::isNotBlank);
        var optCategory = Optional.ofNullable(request.getParameter("category")).filter(StringUtils::isNotBlank);

        int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
        int offset = handleIntParameter(request, "offset", 0);

        var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);

        if (optProject.isPresent() && dataSources.containsKey(optProject.get())) {
            var dataSource = dataSources.get(optProject.get());
            var wiki = wikis.get(optProject.get());

            try {
                var localEntries = doQuery(dataSource, optCategory, wiki);
                var results = getDataView(localEntries, limit, offset);

                request.setAttribute("results", results);
                request.setAttribute("total", localEntries.size());
                request.setAttribute("domain", wiki.getDomain());
            } catch (SQLException e) {
                throw new UncheckedIOException(e.getMessage());
            }
        }

        dispatcher.forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    private static int handleIntParameter(HttpServletRequest request, String param, final int reference) {
        var paramStr = request.getParameter(param);

        try {
            return Integer.parseInt(paramStr);
        } catch (NumberFormatException e) {
            return reference;
        }
    }

    private static List<Entry> getDataView(List<Entry> list, final int limit, final int offset) {
        try {
            return list.subList(offset, Math.min(list.size(), offset + limit));
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    private List<Entry> doQuery(DataSource dataSource, Optional<String> optCategory, Wiki wiki) throws SQLException {
        var today = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate();
        var entries = new ArrayList<Entry>();
        var categoryBranch = "";

        if (optCategory.isPresent()) {
            var normalized = wiki.removeNamespace(optCategory.get(), Wiki.CATEGORY_NAMESPACE).replace(' ', '_');

            categoryBranch = """
                INNER JOIN categorylinks
                    ON cl_from = page_id
                    AND cl_to = '%s'
                """.formatted(normalized);
        }

        var query = """
            SELECT
                page_title,
                rev_timestamp
            FROM page
                INNER JOIN revision
                    ON rev_id = page_latest
                LEFT JOIN flaggedpages
                    ON fp_page_id = page_id
                %s
            WHERE
                page_namespace = 0 AND
                page_is_redirect = 0 AND
                fp_page_id IS NULL
            ORDER BY
                rev_timestamp ASC
            """.formatted(categoryBranch);

        try (var connection = dataSource.getConnection()) {
            var statement = connection.prepareStatement(query);
            var results = statement.executeQuery();

            while (results.next()) {
                var title = results.getString("page_title").replace('_', ' ');
                var timestamp = results.getString("rev_timestamp");
                var refDate = LocalDate.parse(timestamp, TIMESTAMP_FORMAT);

                entries.add(new Entry(title, ChronoUnit.DAYS.between(refDate, today)));
            }
        }

        return entries;
    }

    public static class Entry {
        private String title;
        private long days;

        public Entry(String title, long days) {
            this.title = title;
            this.days = days;
        }

        public String getTitle() {
            return title;
        }

        public long getDays() {
            return days;
        }
    }
}
