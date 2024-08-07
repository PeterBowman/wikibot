package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class BrokenInterwikiLinksServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Pattern P_TARGET_LINK = Pattern.compile("_*:_*");

    private static final Map<String, String> PREFIX_TO_DATABASE_FAMILY;
    private static final Set<String> MULTILINGUAL_PROJECTS;
    private static final Set<String> LONG_PREFIXES;

    private static final Set<Project> projects = new HashSet<>(1300);
    private static final Set<String> langCodes = new HashSet<>(500);

    private static final ConcurrentMap<Project, Map<String, Integer>> namespaces = new ConcurrentHashMap<>();

    private static final String URI_TMPL = "jdbc:mysql://%1$s.web.db.svc.wikimedia.cloud:3306/%1$s_p";

    private Properties sqlProperties = new Properties();
    private DataSource plwiktDS;

    static {
        Map<String, List<String>> _databaseFamilyToPrefixes = new HashMap<>(40);

        // https://meta.wikimedia.org/wiki/Help:Interwiki_linking#Project_titles_and_shortcuts
        // https://noc.wikimedia.org/conf/highlight.php?file=interwiki.php

        _databaseFamilyToPrefixes.put("wiki", List.of("wikipedia", "w"));
        _databaseFamilyToPrefixes.put("wiktionary", List.of("wiktionary", "wikt"));
        _databaseFamilyToPrefixes.put("wikinews", List.of("wikinews", "n"));
        _databaseFamilyToPrefixes.put("wikibooks", List.of("wikibooks", "b"));
        _databaseFamilyToPrefixes.put("wikiquote", List.of("wikiquote", "q"));
        _databaseFamilyToPrefixes.put("wikisource", List.of("wikisource", "s"));
        _databaseFamilyToPrefixes.put("specieswiki", List.of("wikispecies", "species"));
        _databaseFamilyToPrefixes.put("wikiversity", List.of("wikiversity", "v"));
        _databaseFamilyToPrefixes.put("wikivoyage", List.of("wikivoyage", "voy"));
        _databaseFamilyToPrefixes.put("foundationwiki", List.of("wikimedia", "foundation", "wmf"));
        _databaseFamilyToPrefixes.put("commonswiki", List.of("commons", "c"));
        _databaseFamilyToPrefixes.put("metawiki", List.of("metawikipedia", "meta", "m"));
        _databaseFamilyToPrefixes.put("incubatorwiki", List.of("incubator"));
        _databaseFamilyToPrefixes.put("outreachwiki", List.of("outreachwiki", "outreach"));
        _databaseFamilyToPrefixes.put("mediawikiwiki", List.of("mw"));
        _databaseFamilyToPrefixes.put("testwiki", List.of("testwiki"));
        _databaseFamilyToPrefixes.put("wikidatawiki", List.of("wikidata", "d"));
        //_databaseToPrefixes.put("betawikiversity", List.of("betawikiversity"));
        _databaseFamilyToPrefixes.put("qualitywiki", List.of("quality"));
        _databaseFamilyToPrefixes.put("testwikidatawiki", List.of("testwikidata"));
        _databaseFamilyToPrefixes.put("advisorywiki", List.of("advisory"));
        _databaseFamilyToPrefixes.put("donatewiki", List.of("donate"));
        _databaseFamilyToPrefixes.put("nostalgiawiki", List.of("nostalgia", "nost"));
        _databaseFamilyToPrefixes.put("tenwiki", List.of("tenwiki"));
        _databaseFamilyToPrefixes.put("test2wiki", List.of("test2wiki"));
        _databaseFamilyToPrefixes.put("usabilitywiki", List.of("usability"));
        _databaseFamilyToPrefixes.put("wikimedia", List.of("chapter"));

        Map<String, String> _prefixToDatabase = new HashMap<>(60);

        for (var entry : _databaseFamilyToPrefixes.entrySet()) {
            for (String prefix : entry.getValue()) {
                _prefixToDatabase.put(prefix, entry.getKey());
            }
        }

        MULTILINGUAL_PROJECTS = Set.of(
            "specieswiki", "foundationwiki", "commonswiki", "metawiki", "incubatorwiki", "outreachwiki",
            "mediawikiwiki", "testwiki", "wikidatawiki", "qualitywiki", "testwikidatawiki",
            "advisorywiki", "donatewiki", "nostalgiawiki", "tenwiki", "test2wiki", "usabilitywiki"
        );

        LONG_PREFIXES = Set.of(
            "wikipedia", "wiktionary", "wikinews", "wikibooks", "wikiquote", "wikisource", "wikispecies",
            "wikiversity", "wikivoyage", "wikimedia", "commons", "meta", "wikidata" // "nost"?
        );

        PREFIX_TO_DATABASE_FAMILY = Collections.unmodifiableMap(_prefixToDatabase);
    }

    @Override
    public void init() throws ServletException {
        DataSource metaDS;

        try {
            var context = (Context) new InitialContext().lookup("java:comp/env");
            plwiktDS = (DataSource) context.lookup("jdbc/plwiktionary-web");
            metaDS = (DataSource) context.lookup("jdbc/meta");

            sqlProperties.put("user", context.lookup("user"));
            sqlProperties.put("password", context.lookup("password"));
            sqlProperties.put("enabledTLSProtocols", context.lookup("enabledTLSProtocols"));
        } catch (NamingException | SecurityException e) {
            throw new UnavailableException(e.getMessage());
        }

        try (Connection conn = metaDS.getConnection()) {
            String query = "SELECT dbname, lang, family, url, is_sensitive FROM meta_p.wiki;";
            ResultSet rs = conn.createStatement().executeQuery(query);

            while (rs.next()) {
                String database = rs.getString("dbname");
                String lang = rs.getString("lang");
                String family = rs.getString("family");
                String url = rs.getString("url");
                boolean isCaseSensitive = rs.getBoolean("is_sensitive");

                if (url == null) { // e.g. adywiki, jamwiki
                    continue;
                }

                langCodes.add(lang);

                Project project = new Project(database, lang, family, url, isCaseSensitive);
                projects.add(project);
            }
        } catch (SQLException e) {
            throw new UnavailableException(e.getMessage());
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        RequestInfo requestInfo = new RequestInfo(request);
        PrintWriter writer = response.getWriter();

        Project sourceProject = Project.retrieveProject("plwiktionary");
        Project targetProject;

        try {
            targetProject = Project.retrieveProject(requestInfo.targetDB);
        } catch (NoSuchElementException e) {
            writer.append("<p>")
                .append("Nie rozpoznano bazy danych <em>").append(e.getMessage()).append("</em>.")
                .append("</p>");

            return;
        }

        HttpSession session = request.getSession();
        RequestInfo lastRequest = null;

        if (requestInfo.useCache) {
            try {
                lastRequest = (RequestInfo) session.getAttribute("lastRequest");
            } catch (IllegalStateException e) {
                // do nothing
            } catch (ClassCastException e) {
                session.removeAttribute("lastRequest");
            }
        }

        if (lastRequest != null) {
            if (lastRequest.equals(requestInfo) && lastRequest.output != null && lastRequest.items != null) {
                lastRequest.limit = requestInfo.limit;
                lastRequest.offset = requestInfo.offset;
                printResults(writer, sourceProject, targetProject, request, lastRequest);
                return;
            } else {
                session.removeAttribute("lastRequest");
            }
        }

        try (Connection conn = plwiktDS.getConnection()) {
            final long startTimer = System.currentTimeMillis();

            if (!requestInfo.onlyMainNamespace && !namespaces.containsKey(sourceProject)) {
                fetchNamespaces(sourceProject);
            }

            if (!namespaces.containsKey(targetProject)) {
                fetchNamespaces(targetProject);
            }

            List<Item> items = fetchInterwikiLinks(conn, sourceProject, targetProject, requestInfo);

            final int totalFetched = items.size();

            if (!items.isEmpty()) {
                var url = String.format(URI_TMPL, targetProject.database);

                try (Connection targetConn = DriverManager.getConnection(url, sqlProperties)) {
                    filterMissingTargets(targetConn, items, requestInfo);
                }
            }

            final long endTimer = System.currentTimeMillis();

            requestInfo.output = new RequestInfo.Output(endTimer - startTimer, totalFetched, items.size());

            Collections.sort(items, new ItemComparator(sourceProject.lang, targetProject.lang));
            requestInfo.items = items;

            printResults(writer, sourceProject, targetProject, request, requestInfo);
            session.setAttribute("lastRequest", requestInfo);
        } catch (SQLException | UncheckedIOException e) {
            writer.append("<p>")
                .append("Komunikacja z bazą danych się nie powiodła. Komunikat błędu: ")
                .append("</p>").append("\n")
                .append("<pre>")
                .append(e.getClass().getName()).append(": ").append(e.getMessage())
                .append("</pre>");
        } catch (IllegalStateException e) {
            // do nothing
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    private static void fetchNamespaces(Project project) {
        String url = project.url.replaceFirst("^https?://", "");
        Wiki wiki = Wiki.newSession(url);
        Map<String, Integer> info = wiki.getNamespaces();
        Map<String, Integer> copy = new LinkedHashMap<>(info.size(), 1);

        for (Map.Entry<String, Integer> entry : info.entrySet()) {
            String key = entry.getKey().toLowerCase().replace(" ", "_");
            copy.put(key, entry.getValue());
        }

        namespaces.put(project, Collections.unmodifiableMap(copy));
    }

    private static List<Item> fetchInterwikiLinks(Connection conn, Project sourceProject, Project targetProject,
            RequestInfo request) throws SQLException {
        String query = """
            SELECT
                page_title,
                iwl_prefix,
                iwl_title,
                page_namespace
            FROM iwlinks
                INNER JOIN page ON iwl_from = page_id
            WHERE
                iwl_title != ''
            """;

        if (request.onlyMainNamespace) {
            query += " AND page_namespace = 0";
        }

        // https://dev.mysql.com/doc/connector-j/5.1/en/connector-j-reference-implementation-notes.html
        // http://stackoverflow.com/a/2448019
        Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        stmt.setFetchSize(Integer.MIN_VALUE);
        ResultSet rs = stmt.executeQuery(query);
        List<Item> list = new ArrayList<>(5000);

        while (rs.next()) {
            String sourceTitle = rs.getString("page_title");
            int sourceNamespace = rs.getInt("page_namespace");

            String link = rs.getString("iwl_prefix") + ":" + rs.getString("iwl_title");
            Item.Page targetPage;

            try {
                targetPage = processLink(sourceProject, targetProject, link);
            } catch (NoSuchElementException | NullPointerException e) {
                System.out.println(e.getClass() + ": " + link);
                continue;
            }

            if (targetPage != null) {
                Item.Page sourcePage = new Item.Page(sourceTitle, sourceNamespace);
                Item item = new Item(sourcePage, sourceProject, targetPage, targetProject, link);
                list.add(item);
            }
        }

        return list;
    }

    private static Item.Page processLink(Project context, Project reference, String link) {
        Matcher m = P_TARGET_LINK.matcher(link);
        StringBuilder sb = new StringBuilder();

        Project currentProject = context;
        int currentNamespace = 0;

        while (m.find()) {
            m.appendReplacement(sb, "");
            String token = sb.toString().toLowerCase();

            if (langCodes.contains(token)) {
                if (MULTILINGUAL_PROJECTS.contains(currentProject.database)) {
                    currentProject = Project.retrieveProject(token, "wiki");
                } else if (token.equals(currentProject.lang)) {
                    // do nothing
                } else {
                    currentProject = Project.retrieveProject(token, currentProject.databaseFamily);
                }

                sb.setLength(0);
                continue;
            }

            String databaseFamily = PREFIX_TO_DATABASE_FAMILY.get(token);

            if (databaseFamily != null) {
                if (LONG_PREFIXES.contains(token)) {
                    if (currentProject.databaseFamily.equals(databaseFamily)) {
                        currentNamespace = 4; // 'Project' namespace
                        sb.setLength(0);
                        break;
                    } else {
                        if (MULTILINGUAL_PROJECTS.contains(databaseFamily)) {
                            currentProject = Project.retrieveProject(databaseFamily);
                        } else {
                            currentProject = Project.retrieveProject("en", databaseFamily);
                        }
                    }
                } else {
                    if (MULTILINGUAL_PROJECTS.contains(databaseFamily)) {
                        if (!currentProject.database.equals(databaseFamily)) {
                            currentProject = Project.retrieveProject(databaseFamily);
                        }
                    } else if (MULTILINGUAL_PROJECTS.contains(currentProject.database)) {
                        currentProject = Project.retrieveProject("en", databaseFamily);
                    } else {
                        if (currentProject.databaseFamily.equals(databaseFamily)) {
                            currentProject = Project.retrieveProject("en", databaseFamily);
                        } else {
                            currentProject = Project.retrieveProject(currentProject.lang, databaseFamily);
                        }
                    }
                }

                sb.setLength(0);
                continue;
            }

            // No language nor interwiki prefixes left, analyze last stored project.
            if (!currentProject.equals(reference)) {
                return null;
            }

            // Might return null if something goes wrong.
            Map<String, Integer> namespaceAliases = namespaces.get(currentProject);
            Integer namespace = namespaceAliases.get(token);

            if (namespace != null) {
                currentNamespace = namespace;
                sb.setLength(0);
            } else {
                sb.append(m.group());
            }

            break; // omit any colons in page title
        }

        String remainder = m.appendTail(sb).toString();

        if (remainder.contains("#")) {
            remainder = remainder.substring(0, remainder.indexOf("#"));
        }

        if (remainder.contains("%")) {
            remainder = URLDecoder.decode(remainder, StandardCharsets.UTF_8);
        }

        if (!remainder.matches("_*") && !remainder.contains("?") && currentProject.equals(reference)) {
            if (!reference.isCaseSensitive) {
                remainder = StringUtils.capitalize(remainder);
            }

            return new Item.Page(remainder, currentNamespace);
        } else {
            return null;
        }
    }

    private static void filterMissingTargets(Connection conn, List<Item> items, RequestInfo request) throws SQLException {
        Set<String> targets = new HashSet<>(items.size());

        for (Item item : items) {
            String target = item.targetPage.title;
            target = "'" + target.replace("'", "\\'") + "'";
            targets.add(target);
        }

        String values = String.join(",", targets);

        String query = """
            SELECT
                page_title,
                page_namespace,
                page_is_redirect
            """;

        if (request.includeCreated || request.showDisambigs) {
            query += """
                , EXISTS(
                    SELECT NULL
                    FROM page_props
                    WHERE pp_page = page_id AND pp_propname = 'disambiguation'
                ) AS is_disambig"
                """;
        }

        query += " FROM page";
        query += " WHERE page_title IN (" + values + ")";

        ResultSet rs = conn.createStatement().executeQuery(query);

        Set<Item.Page> results = new HashSet<>(targets.size());
        Set<Item.Page> redirects = new HashSet<>();
        Set<Item.Page> disambigs = new HashSet<>();

        while (rs.next()) {
            String title = rs.getString("page_title");
            int ns = rs.getInt("page_namespace");

            Item.Page page = new Item.Page(title, ns);

            if ((request.includeCreated || request.showRedirects) && rs.getBoolean("page_is_redirect")) {
                redirects.add(page);
                continue;
            }

            if ((request.includeCreated || request.showDisambigs) && rs.getBoolean("is_disambig")) {
                disambigs.add(page);
                continue;
            }

            results.add(page);
        }

        Iterator<Item> i = items.iterator();

        while (i.hasNext()) {
            Item item = i.next();
            Item.Page targetPage = item.targetPage;

            if (targetPage.ns > -1 && !results.contains(targetPage)) {
                if (redirects.contains(targetPage)) {
                    item.isRedirect = true;
                } else if (disambigs.contains(targetPage)) {
                    item.isDisambig = true;
                } else {
                    item.isMissing = true;
                }
            } else if (!request.includeCreated) {
                i.remove();
            }
        }
    }

    private static void printResults(PrintWriter writer, Project sourceProject, Project targetProject,
            HttpServletRequest request, RequestInfo requestInfo) {
        writer.append("<p>");

        writer.append("Czas przetwarzania zapytania: ")
            .append(String.format(new Locale("pl"), "%.3f", ((float) requestInfo.output.timeElapsedMs) / 1000))
            .append(" sekund.");

        writer.append(" Wszystkich linków: ")
            .append("<strong>").append(formatNumber(requestInfo.output.totalSize)).append("</strong>.");

        if (!requestInfo.includeCreated) {
            writer.append(" Spełniających kryteria zapytania: ")
                .append("<strong>").append(formatNumber(requestInfo.output.filteredSize)).append("</strong>.");
        }

        writer.append("</p>");

        if (requestInfo.items.isEmpty()) {
            return;
        }

        final int limit = requestInfo.limit;
        final int offset = requestInfo.offset;

        String paginator = generatePaginator(limit, offset, requestInfo.output.filteredSize, request);

        writer.append("\n").append(paginator).append("\n");
        writer.append("<ol ").append("start=\"").append(Integer.toString(offset + 1)).append("\">");
        writer.append("\n");

        for (int i = offset, max = Math.min(requestInfo.items.size(), offset + limit); i < max; i++) {
            Item item = requestInfo.items.get(i);
            writer.append(item.printHTML()).append("\n");
        }

        writer.append("</ol>").append("\n").append(paginator);
    }

    private static String formatNumber(int n) {
        // TODO: investigate java.text.NumberFormat and java.text.DecimalFormat
        // http://stackoverflow.com/questions/3672731
        // http://stackoverflow.com/questions/10411414
        String strValue = Integer.toString(n);
        int length = strValue.length();

        if (length > 4) {
            return strValue.substring(0, length - 3) + "&nbsp;" + strValue.substring(length - 3);
        } else {
            return strValue;
        }
    }

    private static String generatePaginator(final int limit, final int offset, final int totalSize,
            HttpServletRequest request) {
        // TODO: move presentation layer to the JSP caller, use paginator.tag
        Map<String, String[]> params = request.getParameterMap();
        Map<String, Object> tempMap = new HashMap<>();
        tempMap.put("usecache", "on");

        StringBuilder sb = new StringBuilder(500);
        sb.append("<p>").append("Zobacz (");

        if (offset == 0) {
            sb.append("poprzednie ").append(limit);
        } else {
            tempMap.put("offset", Math.max(offset - limit, 0));
            sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">");
            sb.append("poprzednie ").append(limit);
            sb.append("</a>");
        }

        sb.append(" | ");

        if (offset + limit >= totalSize) {
            sb.append("następne ").append(limit);
        } else {
            tempMap.put("offset", offset + limit);
            sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">");
            sb.append("następne ").append(limit);
            sb.append("</a>");
        }

        sb.append(") (");

        tempMap.put("limit", 20);
        tempMap.put("offset", offset);

        sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">").append(20).append("</a>");
        sb.append(" | ");

        tempMap.put("limit", 50);

        sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">").append(50).append("</a>");
        sb.append(" | ");

        tempMap.put("limit", 100);

        sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">").append(100).append("</a>");
        sb.append(" | ");

        tempMap.put("limit", 250);

        sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">").append(250).append("</a>");
        sb.append(" | ");

        tempMap.put("limit", 500);

        sb.append("<a href=\"").append(replaceParams(params, tempMap)).append("\">").append(500).append("</a>");
        sb.append(")</p>");

        return sb.toString();
    }

    private static String replaceParams(Map<String, String[]> oldParams, Map<String, Object> newParams) {
        StringBuilder sb = new StringBuilder(100);

        for (Map.Entry<String, String[]> entry : oldParams.entrySet()) {
            String key = entry.getKey();

            if (newParams.containsKey(key)) {
                sb.append(key).append("=").append(newParams.get(key)).append("&");
            } else {
                for (String value : entry.getValue()) {
                    sb.append(key).append("=").append(value).append("&");
                }
            }
        }

        for (Map.Entry<String, Object> entry : newParams.entrySet()) {
            if (!oldParams.containsKey(entry.getKey())) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }

        if (sb.length() != 0 && sb.charAt(sb.length() - 1) == '&') {
            sb.deleteCharAt(sb.length() - 1);
        }

        // Assume URL encoding is not needed here.
        return "?" + sb.toString();
    }

    private static class RequestInfo {
        String targetDB;
        boolean onlyMainNamespace;
        boolean showRedirects;
        boolean showDisambigs;
        boolean includeCreated;
        boolean useCache;

        int limit = 100;
        int offset = 0;

        Output output;
        List<Item> items;

        public RequestInfo(HttpServletRequest request) {
            targetDB = request.getParameter("targetdb").trim(); // always present
            onlyMainNamespace = request.getParameter("onlymainnamespace") != null;
            showRedirects = request.getParameter("showredirects") != null;
            showDisambigs = request.getParameter("showdisambigs") != null;
            includeCreated = request.getParameter("includecreated") != null;
            useCache = request.getParameter("usecache") != null;

            final String limitStr = request.getParameter("limit");

            if (limitStr != null) {
                try {
                    limit = Math.max(Integer.parseInt(limitStr), 0);
                } catch (NumberFormatException e) {}
            }

            final String offsetStr = request.getParameter("offset");

            if (offsetStr != null) {
                try {
                    offset = Math.max(Integer.parseInt(offsetStr), 0);
                } catch (NumberFormatException e) {}
            }
        }

        @Override
        public int hashCode() {
            return
                targetDB.hashCode() +
                Boolean.hashCode(onlyMainNamespace) + Boolean.hashCode(showRedirects) +
                Boolean.hashCode(showDisambigs) + Boolean.hashCode(includeCreated);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof RequestInfo r) {
                return
                    targetDB.equals(r.targetDB) && onlyMainNamespace == r.onlyMainNamespace &&
                    showRedirects == r.showRedirects && showDisambigs == r.showDisambigs &&
                    includeCreated == r.includeCreated;
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format(
                "[%s, mainNS: %s, redir: %s, disambig: %s, all: %s, output: %s]",
                targetDB, onlyMainNamespace, showRedirects, showDisambigs, includeCreated, output
            );
        }

        private record Output (long timeElapsedMs, int totalSize, int filteredSize) {}
    }

    private static class Project {
        String database;
        String lang;
        String family;
        String databaseFamily;
        String url;
        boolean isCaseSensitive;

        Project(String database, String lang, String family, String url, boolean isCaseSensitive) {
            this.database = database;
            this.lang = lang;
            this.family = family;
            this.url = url;
            this.isCaseSensitive = isCaseSensitive;

            if (!lang.isEmpty() && database.startsWith(lang)) {
                databaseFamily = database.substring(lang.length());
            } else {
                databaseFamily = database;
            }

            // 'simplewiki' and 'simplewiktionary' are configured as 'en' projects
            if (lang.equals("en") && database.startsWith("simple")) {
                this.lang = "simple";
            }
        }

        static Project retrieveProject(String lang, String databaseFamily) {
            lang = switch (lang) {
                case "be-tarask" -> "be-x-old";
                case "nb" -> "no";
                default -> lang;
            };

            lang = lang.replace("-", "_");
            return retrieveProject(lang + databaseFamily);
        }

        static Project retrieveProject(String database) {
            for (Project project : projects) {
                if (project.database.equals(database)) {
                    return project;
                }
            }

            throw new NoSuchElementException(database);
        }

        @Override
        public int hashCode() {
            return database.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof Project p) {
                return database.equals(p.database);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format(
                "[%s, %s:%s (%s), %s; case_sensitive: %s]",
                database, lang, databaseFamily, family, url, isCaseSensitive
            );
        }
    }

    private static class Item {
        Page sourcePage;
        Project sourceProject;

        Page targetPage;
        Project targetProject;

        String link;

        boolean isMissing;
        boolean isRedirect;
        boolean isDisambig;

        Item(Page sourcePage, Project sourceProject, Page targetPage, Project targetProject, String link) {
            this.sourcePage = sourcePage;
            this.sourceProject = sourceProject;
            this.targetPage = targetPage;
            this.targetProject = targetProject;
            this.link = link;
        }

        @Override
        public String toString() {
            return String.format(
                "%s || %s (%s) || missing: %s, redir: %s, disambig: %s",
                sourcePage, link, targetPage, isMissing, isRedirect, isDisambig
            );
        }

        String printHTML() {
            String sourceLink = "<a href=\"" + sourceProject.url + "/wiki/";
            String sourceArticle = "";

            if (sourcePage.ns != 0) {
                for (Map.Entry<String, Integer> entry : namespaces.get(sourceProject).entrySet()) {
                    if (entry.getValue().equals(sourcePage.ns)) {
                        sourceArticle = entry.getKey() + ":";
                        break;
                    }
                }
            }

            sourceArticle += sourcePage.title;

            sourceLink += URLEncoder.encode(sourceArticle, StandardCharsets.UTF_8);
            sourceLink += "\" target=\"_blank\">" + sourceArticle.replace("_", " ") + "</a>";

            String targetLink = "<a href=\"" + targetProject.url + "/wiki/";
            String targetArticle = "";

            if (targetPage.ns != 0) {
                for (Map.Entry<String, Integer> entry : namespaces.get(targetProject).entrySet()) {
                    if (entry.getValue().equals(targetPage.ns)) {
                        targetArticle = entry.getKey() + ":";
                        break;
                    }
                }
            }

            targetArticle += targetPage.title;

            targetLink += URLEncoder.encode(targetArticle, StandardCharsets.UTF_8);
            targetLink += "\" target=\"_blank\"";

            if (isMissing) {
                targetLink += " class=\"new\"";
            } else if (isRedirect) {
                targetLink += " class=\"redirect\"";
            } else if (isDisambig) {
                targetLink += " class=\"disambig\"";
            }

            targetLink += ">" + link.replace("_", " ") + "</a>";

            return "<li>" + sourceLink + " → " + targetLink + "</li>";
        }

        private record Page (String title, int ns) {}
    }

    private static class ItemComparator implements Comparator<Item> {
        Collator sourceCollator;
        Collator targetCollator;

        public ItemComparator(String sourceLang, String targetLang) {
            sourceCollator = Collator.getInstance(new Locale(sourceLang));
            sourceCollator.setStrength(Collator.TERTIARY);
            targetCollator = Collator.getInstance(new Locale(targetLang));
            targetCollator.setStrength(Collator.TERTIARY);
        }

        @Override
        public int compare(Item i1, Item i2) {
            if (i1.sourcePage.ns != i2.sourcePage.ns) {
                return Integer.compare(i1.sourcePage.ns, i2.sourcePage.ns);
            }

            if (!i1.sourcePage.title.equals(i2.sourcePage.title)) {
                return sourceCollator.compare(i1.sourcePage.title, i2.sourcePage.title);
            }

            if (i1.targetPage.ns != i2.targetPage.ns) {
                return Integer.compare(i1.targetPage.ns, i2.targetPage.ns);
            }

            if (!i1.targetPage.title.equals(i2.targetPage.title)) {
                return targetCollator.compare(i1.targetPage.title, i2.targetPage.title);
            }

            return i1.link.compareTo(i2.link);
        }
    }
}
