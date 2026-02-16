package com.github.wikibot.webapp;

import java.io.IOException;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class MissingUkrainianAudio extends HttpServlet {
    private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwikt-missing-ukrainian-audio.jsp";
    private static final String AUDIO_CATEGORY_NAME = "Ukrainian_pronunciation"; // keep the underscore!
    private static final String ENTRIES_CATEGORY_NAME = "ukraiński_(indeks)"; // keep the underscore!

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int DEFAULT_LIMIT = 500;

    private DataSource commonsDataSource;
    private DataSource plwiktionaryDataSource;

    @Override
    public void init() throws ServletException {
        try {
            var context = (Context) new InitialContext().lookup("java:comp/env");
            commonsDataSource = (DataSource) context.lookup("jdbc/commons-web");
            plwiktionaryDataSource = (DataSource) context.lookup("jdbc/plwiktionary-web");
        } catch (NamingException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
        int offset = handleIntParameter(request, "offset", 0);

        try {
            var files = getUkrainianAudioFiles();
            var titles = getTargetedEntries(files);

            Collections.sort(titles, Collator.getInstance(Locale.forLanguageTag("uk")));

            if (request.getRequestURI().endsWith("/raw")) {
                response.setContentType("text/plain");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().println(String.join("\n", titles));
            } else if (request.getRequestURI().endsWith("/download")) {
                var filename = String.format("plwikt-missing-uk-audio-%s.txt", DT_FORMATTER.format(LocalDateTime.now()));
                response.setContentType("text/plain");
                response.setCharacterEncoding("UTF-8");
                // https://stackoverflow.com/a/49297565/10404307
                response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s\"", filename));
                response.getWriter().println(String.join("\n", titles));
            } else {
                var results = titles.subList(offset, Math.min(titles.size(), offset + limit));
                var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
                request.setAttribute("results", results);
                request.setAttribute("total", titles.size());
                // https://stackoverflow.com/a/21046620/10404307
                request.setAttribute("originalContext", request.getRequestURI());
                dispatcher.forward(request, response);
            }
        } catch (SQLException | IOException | IndexOutOfBoundsException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException {
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

    private List<String> getUkrainianAudioFiles() throws SQLException {
        var files = new ArrayList<String>();
        var visitedCats = new HashSet<String>();
        var targetCategories = List.of(AUDIO_CATEGORY_NAME);

        final var queryFmt = """
            SELECT
                page_title,
                page_namespace
            FROM page
                LEFT JOIN categorylinks ON cl_from = page_id
                LEFT JOIN linktarget ON lt_id = cl_target_id
            WHERE
                page_is_redirect = 0 AND
                lt_title IN (%s);
            """;

        try (var connection = commonsDataSource.getConnection()) {
            while (!targetCategories.isEmpty()) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray);
                var rs = connection.createStatement().executeQuery(query);
                var subcats = new ArrayList<String>();

                while (rs.next()) {
                    var title = rs.getString("page_title");
                    var ns = rs.getInt("page_namespace");

                    if (ns == 14) { // Category
                        subcats.add(title);
                    } else if (ns == 6) { // File
                        files.add(title);
                    }
                }

                visitedCats.addAll(targetCategories);
                subcats.removeAll(visitedCats);

                targetCategories = subcats;
            }
        }

        return files.stream().distinct().toList();
    }

    private List<String> getTargetedEntries(List<String> files) throws SQLException {
        var titles = new ArrayList<String>(10000);

        try (var connection = plwiktionaryDataSource.getConnection()) {
            var filesStr = files.stream()
                .map(s -> String.format("'%s'", s.replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title
                FROM page
                    INNER JOIN categorylinks ON cl_from = page_id
                    INNER JOIN linktarget AS lt_cl ON lt_cl.lt_id = cl_target_id
                WHERE
                    page_namespace = 0 AND
                    lt_cl.lt_title = '%s' AND
                    NOT EXISTS (
                        SELECT 1
                        FROM imagelinks
                            INNER JOIN linktarget AS lt_il ON lt_il.lt_id = il_target_id
                        WHERE il_from = page_id AND lt_il.lt_title IN (%s)
                    );
                """.formatted(ENTRIES_CATEGORY_NAME, filesStr);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title").replace('_', ' ');
                titles.add(title);
            }
        }

        return titles;
    }
}
