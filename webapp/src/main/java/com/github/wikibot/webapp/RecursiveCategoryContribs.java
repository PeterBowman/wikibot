package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.wikipedia.Wiki;

import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RecursiveCategoryContribs extends HttpServlet {
    private static final String JSP_DISPATCH_TARGET = "/jsp/recursive-category-contribs.jsp";
    private static final Collator POLISH_COLLATOR = Collator.getInstance(Locale.forLanguageTag("pl"));

    private static final DateTimeFormatter DATE_FORMAT;

    private DataSource plwikiDataSource;
    private Wiki wiki = Wiki.newSession("pl.wikipedia.org");

    static {
        DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.YEAR, 4)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();
    }

    @Override
    public void init() throws ServletException {
        try {
            var context = (Context) new InitialContext().lookup("java:comp/env");
            plwikiDataSource = (DataSource) context.lookup("jdbc/plwiki-web");

            wiki.getNamespaces(); // populate cache
        } catch (NamingException | UncheckedIOException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);

        try {
            var optMainCategory = Optional.ofNullable(request.getParameter("mainCategory"))
                .filter(v -> !v.isBlank())
                .map(v -> wiki.removeNamespace(sanitize(v), Wiki.CATEGORY_NAMESPACE));

            if (optMainCategory.isEmpty()) {
                dispatcher.forward(request, response);
                return;
            }

            var optStartDateStr = Optional.ofNullable(request.getParameter("startDate")).filter(v -> !v.isBlank()); // inclusive
            var optEndDateStr = Optional.ofNullable(request.getParameter("endDate")).filter(v -> !v.isBlank()); // inclusive

            Optional<LocalDateTime> optStartDateTime, optEndDateTime;

            try {
                optStartDateTime = optStartDateStr.map(LocalDate::parse).map(LocalDate::atStartOfDay);
                optEndDateTime = optEndDateStr.map(LocalDate::parse).map(LocalDate::atStartOfDay).map(d -> d.plusDays(1));

                if (optStartDateTime.isPresent() && optEndDateTime.isPresent() && !optEndDateTime.get().isAfter(optStartDateTime.get())) {
                    throw new IllegalArgumentException("data zakończenia wcześniejsza niż data początku");
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("nieprawidłowy format daty");
            }

            var optIgnoredCategories = Optional.ofNullable(request.getParameter("ignoredCategories"))
                .filter(v -> !v.isBlank())
                .map(v -> Arrays.asList(v.split("\r?\n")).stream()
                    .filter(vv -> !vv.isBlank())
                    .map(vv -> wiki.removeNamespace(sanitize(vv), Wiki.CATEGORY_NAMESPACE).replace(' ', '_'))
                    .toList()
                );

            var optMaxDepth = Optional.<Integer>empty();

            try {
                optMaxDepth = Optional.ofNullable(request.getParameter("maxDepth")).filter(v -> !v.isBlank()).map(Integer::parseInt);

                if (optMaxDepth.isPresent() && optMaxDepth.get() < 0) {
                    throw new IllegalArgumentException("ujemna maksymalna głębokość");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("nieprawidłowy format maksymalnej głębokości");
            }

            var sysops = new HashSet<String>();
            var contribsPerUser = new TreeMap<String, Integer>(POLISH_COLLATOR);
            var stats = doRecursiveQuery(optMainCategory.get(), optMaxDepth, optIgnoredCategories, optStartDateTime, optEndDateTime, sysops, contribsPerUser);

            var entries = new ArrayList<>(contribsPerUser.entrySet());
            entries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));

            request.setAttribute("results", entries);
            request.setAttribute("stats", stats);
            request.setAttribute("sysops", sysops);

            dispatcher.forward(request, response);
        } catch (IllegalArgumentException e) {
            request.setAttribute("error", e.getMessage());
            dispatcher.forward(request, response);
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\u200e-\u200f]", "").strip(); // remove left-to-right and right-to-left marks
    }

    private Stats doRecursiveQuery(String mainCategory, Optional<Integer> optMaxDepth, Optional<List<String>> optIgnoredCategories,
                                   Optional<LocalDateTime> optStartDateTime, Optional<LocalDateTime> optEndDateTime,
                                   Set<String> sysops, Map<String, Integer> contribsPerUser)
    throws SQLException {
        var stats = new Stats();
        var ignoredCategoriesCount = optIgnoredCategories.map(List::size).orElse(0);
        var visitedCategories = new HashSet<>(optIgnoredCategories.orElse(List.of()));
        var ignoredPages = new HashSet<Integer>();
        var targetCategories = Arrays.asList(mainCategory.replace(' ', '_'));
        var depth = 0;

        ignoredPages.add(0);

        final var queryFmt = """
            SELECT
                page_id,
                page_title,
                page_namespace,
                actor_name,
                COUNT(DISTINCT rev_id) AS contribs,
                (
                    SELECT COUNT(ug_user)
                    FROM user_groups
                    WHERE ug_user = user_id AND ug_group = "sysop"
                ) AS is_sysop
            FROM page
                LEFT JOIN categorylinks ON cl_from = page_id
                INNER JOIN revision ON rev_page = page_id
                INNER JOIN actor ON rev_actor = actor_id
                LEFT JOIN user ON user_id = actor_user
            WHERE
                page_is_redirect = 0 AND
                (page_namespace != 0 OR %s) AND
                (page_namespace != 0 OR %s) AND
                (page_namespace = 0 OR page_latest = rev_id) AND
                cl_to IN (%%s) AND
                page_id NOT IN (%%s) AND
                (page_namespace != 0 OR user_id NOT IN (
                    SELECT user_id
                    FROM user
                        LEFT JOIN user_groups ON ug_user = user_id
                        LEFT JOIN user_former_groups ON ufg_user = user_id
                    WHERE
                        ug_user IN (
                            SELECT ug_user
                            FROM user_groups
                            WHERE ug_group = "bot"
                        ) OR
                        ufg_user IN (
                            SELECT ufg_user
                            FROM user_former_groups
                            WHERE ufg_group = "bot"
                        )
                    GROUP BY
                        user_id
                ))
            GROUP BY
                actor_id,
                page_id;
            """.formatted(
                optStartDateTime
                    .map(DATE_FORMAT::format)
                    .map(v -> "rev_timestamp >= " + v) // inclusive
                    .orElse("TRUE"),
                optEndDateTime
                    .map(DATE_FORMAT::format)
                    .map(v -> "rev_timestamp < " + v) // exclusive
                    .orElse("TRUE")
            );

        try (var connection = plwikiDataSource.getConnection()) {
            while (!targetCategories.isEmpty() && depth <= optMaxDepth.orElse(Integer.MAX_VALUE)) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var pagesArray = ignoredPages.stream()
                    .map(page -> String.format("%d", page))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray, pagesArray);
                var rs = connection.createStatement().executeQuery(query);
                var subcats = new ArrayList<String>();

                while (rs.next()) {
                    var id = rs.getInt("page_id");
                    var title = rs.getString("page_title");
                    var ns = rs.getInt("page_namespace");
                    var actor = rs.getString("actor_name");
                    var contribs = rs.getInt("contribs");
                    var isSysop = rs.getBoolean("is_sysop");

                    if (ns == Wiki.CATEGORY_NAMESPACE) {
                        subcats.add(title); // these are unique per query
                    } else if (ns == Wiki.MAIN_NAMESPACE) {
                        ignoredPages.add(id); // these may be not
                        contribsPerUser.merge(actor, contribs, Integer::sum);

                        if (isSysop) {
                            sysops.add(actor);
                        }
                    }
                }

                visitedCategories.addAll(targetCategories);
                subcats.removeAll(visitedCategories);
                targetCategories = subcats;
                depth++;
            }
        }

        stats.depth = depth - 1;
        stats.categories = visitedCategories.size() - ignoredCategoriesCount;
        stats.articles = ignoredPages.size() - 1;
        stats.edits = contribsPerUser.values().stream().mapToInt(Integer::intValue).sum();

        return stats;
    }

    public static class Stats {
        private int depth;
        private int categories;
        private int articles;
        private int edits;

        public int getDepth() {
            return depth;
        }

        public int getCategories() {
            return categories;
        }

        public int getArticles() {
            return articles;
        }

        public int getEdits() {
            return edits;
        }
    }
}
