package com.github.wikibot.webapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.json.JSONArray;
import org.json.JSONObject;
import org.wikipedia.Wiki;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class MissingPlwikinewsRefsOnPlwiki extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwikinews-missing-plwiki-backlinks.jsp";
    private static final int DEFAULT_LIMIT = 500;

    private DataSource plwikiDataSource;
    private DataSource plwikinewsDataSource;

    private Wiki plwikinews;

    @Override
    public void init() throws ServletException {
        try {
            Context context = (Context) new InitialContext().lookup("java:comp/env");
            plwikiDataSource = (DataSource) context.lookup("jdbc/plwiki-web");
            plwikinewsDataSource = (DataSource) context.lookup("jdbc/plwikinews-web");
            plwikinews = Wiki.newSession("pl.wikinews.org");
            plwikinews.getNamespaces(); // populate cache
        } catch (NamingException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
        int offset = handleIntParameter(request, "offset", 0);

        var backlinks = getInterwikiLinks();
        var filtered = filterMissingBacklinks(backlinks);
        var results = filtered.subList(offset, Math.min(filtered.size(), offset + limit));

        if (getInitParameter("API") != null) {
            JSONObject json = new JSONObject();
            json.put("results", new JSONArray(results));
            json.put("total", filtered.size());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Content-Type", "application/json");
            response.getWriter().append(json.toString());
        } else {
            RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
            request.setAttribute("results", results);
            request.setAttribute("total", filtered.size());
            dispatcher.forward(request, response);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    private static int handleIntParameter(HttpServletRequest request, String param, final int reference) {
        String paramStr = request.getParameter(param);

        try {
            return Integer.parseInt(paramStr);
        } catch (NumberFormatException e) {
            return reference;
        }
    }

    private Set<String> getInterwikiLinks() {
        try (var connection = plwikiDataSource.getConnection()) {
            final var query = """
                SELECT
                    DISTINCT iwl_title
                FROM
                    iwlinks INNER JOIN page ON page_id = iwl_from
                WHERE
                    page_namespace = 0 AND
                    iwl_prefix = 'n' AND
                    iwl_title != '';
                """;

            var rs = connection.createStatement().executeQuery(query);
            var list = new ArrayList<String>(5000);

            while (rs.next()) {
                var title = rs.getString("iwl_title");
                list.add(title);
            }

            return new HashSet<>(list);
        } catch (SQLException e) {
            e.printStackTrace();
            return Collections.emptySet();
        }
    }

    private List<String> filterMissingBacklinks(Set<String> backlinks) {
        var articles = backlinks.stream()
            .filter(page -> plwikinews.namespace(page) == Wiki.MAIN_NAMESPACE)
            .map(target -> String.format("'%s'", target.replace("'", "\\'")))
            .collect(Collectors.joining(","));

        var categories = backlinks.stream()
            .filter(page -> plwikinews.namespace(page) == Wiki.CATEGORY_NAMESPACE)
            .map(target -> plwikinews.removeNamespace(target))
            .map(target -> String.format("'%s'", target.replace("'", "\\'")))
            .collect(Collectors.joining(","));

        try (var connection = plwikinewsDataSource.getConnection()) {
            final var queryFmt = """
                SELECT
                    page_title
                FROM
                    page
                WHERE
                    page_namespace = 0 AND
                    page_is_redirect = 0 AND
                    page_title NOT IN (%1$s) AND
                    page_title NOT IN (
                        SELECT
                            rd_title
                        FROM
                            redirect INNER JOIN page AS p2 ON rd_from = p2.page_id
                        WHERE
                            p2.page_namespace = 0 AND
                            rd_namespace = 0 AND
                            p2.page_title IN (%1$s)
                    ) AND
                    page_title NOT IN (
                        SELECT
                            p3.page_title
                        FROM
                            categorylinks INNER JOIN page AS p3 ON cl_from = p3.page_id
                        WHERE
                            p3.page_namespace = 0 AND
                            cl_to IN (%2$s)
                    )
                ORDER BY
                    CONVERT(page_title USING utf8) COLLATE utf8_polish_ci;
                """;

            var query = String.format(queryFmt, articles, categories);
            var rs = connection.createStatement().executeQuery(query);
            var list = new ArrayList<String>();

            while (rs.next()) {
                var title = rs.getString("page_title");
                list.add(title);
            }

            return list;
        } catch (SQLException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
