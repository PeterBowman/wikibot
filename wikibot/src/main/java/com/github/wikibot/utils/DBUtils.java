package com.github.wikibot.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;

public class DBUtils {
    private static final Pattern P_CONFIG = Pattern.compile("^(.+)='(.+)'$", Pattern.MULTILINE);

    private DBUtils() {}

    public static Properties prepareSQLProperties() throws IOException {
        var defaultSQLProperties = new Properties();
        defaultSQLProperties.setProperty("enabledTLSProtocols", "TLSv1.2");
        return prepareSQLProperties(defaultSQLProperties);
    }

    public static Properties prepareSQLProperties(Properties defaults) throws IOException {
        var properties = new Properties(defaults);
        var cnf = Paths.get("./data/sessions/replica.my.cnf");

        P_CONFIG.matcher(Files.readString(cnf)).results().forEach(m -> properties.setProperty(m.group(1), m.group(2)));
        return properties;
    }

    public static Set<String> getRecursiveCategoryMembers(String sqlUri, String category, int... namespaces) throws SQLException, IOException {
        var props = prepareSQLProperties();
        return getRecursiveCategoryMembers(sqlUri, props, category, List.of(), namespaces);
    }

    public static Set<String> getRecursiveCategoryMembers(String sqlUri, String category, List<String> ignoredCategories, int... namespaces) throws SQLException, IOException {
        var props = prepareSQLProperties();
        return getRecursiveCategoryMembers(sqlUri, props, category, ignoredCategories, namespaces);
    }

    public static Set<String> getRecursiveCategoryMembers(String sqlUri, Properties props, String category, int... namespaces) throws SQLException {
        return getRecursiveCategoryMembers(sqlUri, props, category, List.of(), namespaces);
    }

    public static Set<String> getRecursiveCategoryMembers(String sqlUri, Properties props, String category, List<String> ignoredCategories, int... namespaces) throws SQLException {
        var targetNs = Arrays.stream(namespaces).boxed().toList();
        var articles = new HashSet<String>();
        var visitedCats = ignoredCategories.stream().map(cat -> cat.replace(' ', '_')).collect(Collectors.toCollection(HashSet::new));
        var targetCategories = Arrays.asList(category.replace(' ', '_'));
        var depth = 0;

        final var queryFmt = """
            SELECT DISTINCT page_title AS page_title, page_namespace
            FROM page LEFT JOIN categorylinks ON cl_from = page_id
            WHERE page_is_redirect = 0
            AND cl_to IN (%s);
            """;

        try (var connection = DriverManager.getConnection(sqlUri, props)) {
            while (!targetCategories.isEmpty()) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray);
                var rs = connection.createStatement().executeQuery(query);

                var members = new ArrayList<String>();
                var subcats = new ArrayList<String>();

                while (rs.next()) {
                    var title = rs.getString("page_title");
                    var ns = rs.getInt("page_namespace");

                    if (ns == Wiki.CATEGORY_NAMESPACE) {
                        subcats.add(title);
                    }

                    if (targetNs.isEmpty() || targetNs.contains(ns)) {
                        members.add(title.replace('_', ' '));
                    }
                }

                articles.addAll(members);
                visitedCats.addAll(targetCategories);

                System.out.printf("depth = %d, members = %d, subcats = %d%n", depth++, members.size(), subcats.size());

                subcats.removeAll(visitedCats);
                targetCategories = subcats;
            }
        }

        System.out.printf("Got %d category members for category \"%s\" (%d subcategories)%n", articles.size(), category, visitedCats.size() - 1);
        return articles;
    }

    public static CategoryTree getRecursiveCategoryTree(String sqlUri, String category, Collator collator) throws IOException, SQLException {
        var props = prepareSQLProperties();
        return getRecursiveCategoryTree(sqlUri, props, category, collator);
    }

    public static CategoryTree getRecursiveCategoryTree(String sqlUri, Properties props, String category, Collator collator) throws SQLException {
        var visitedCats = new HashSet<String>();
        var nodes = new HashMap<String, CategoryTree.Node>();
        var targetCategories = Arrays.asList(category.replace(' ', '_'));
        var depth = 0;

        final CategoryTree tree;

        try (var connection = DriverManager.getConnection(sqlUri, props)) {
            {
                var query = """
                    SELECT cat_pages - cat_subcats - cat_files AS members
                    FROM category
                    WHERE cat_title = '%s';
                    """.formatted(targetCategories.get(0).replace("'", "\\'"));

                var rs = connection.createStatement().executeQuery(query);

                if (!rs.next()) {
                    throw new SQLException("Category not found: " + category);
                }

                tree = new CategoryTree(category, rs.getInt("members"), collator);
            }

            nodes.put(targetCategories.get(0), tree.getRoot());

            final var queryFmt = """
                SELECT
                    cl_to,
                    page_title,
                    cat_pages - cat_subcats - cat_files AS members
                FROM page
                    LEFT JOIN categorylinks ON cl_from = page_id
                    LEFT JOIN category ON cat_title = page_title
                WHERE
                    page_namespace = 14 AND
                    cl_to IN (%s);
                """;

            while (!targetCategories.isEmpty()) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray);
                var rs = connection.createStatement().executeQuery(query);
                var subcats = new HashSet<String>();

                while (rs.next()) {
                    var parent = rs.getString("cl_to");
                    var subcat = rs.getString("page_title");
                    var members = rs.getInt("members");

                    var parentNode = nodes.get(parent);

                    if (!nodes.containsKey(subcat)) {
                        var childNode = parentNode.registerChild(subcat.replace("_", " "), members);
                        nodes.put(subcat, childNode);
                    } else {
                        var childNode = nodes.get(subcat);
                        parentNode.connectChild(childNode);
                    }

                    subcats.add(subcat);
                }

                visitedCats.addAll(targetCategories);

                System.out.printf("depth = %d, subcats = %d%n", depth++, subcats.size());

                subcats.removeAll(visitedCats);
                targetCategories = new ArrayList<>(subcats);
            }
        }

        return tree;
    }
}
