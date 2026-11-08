package com.github.wikibot.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;

public class DBUtils {
    private static final Pattern P_CONFIG = Pattern.compile("^(.+?) *= *['\"]?(.+?)['\"]?$", Pattern.MULTILINE);

    private DBUtils() {}

    public static class Helper {
        private String sqlUri;
        private String fallbackSqlUri = "";
        private String category;
        private Properties sqlProperties;
        private int maxDepth = -1;
        private List<String> ignoredCategories = List.of();
        private List<Integer> namespaces = List.of();
        private Collator collator;

        public Helper setSqlProperties(Properties sqlProperties) {
            this.sqlProperties = sqlProperties;
            return this;
        }

        public Helper setSqlUri(String sqlUri) {
            this.sqlUri = sqlUri;
            return this;
        }

        public Helper setFallbackSqlUri(String fallbackSqlUri) {
            this.fallbackSqlUri = fallbackSqlUri;
            return this;
        }

        public Helper setCategory(String category) {
            this.category = category;
            return this;
        }

        public Helper setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Helper setIgnoredCategories(List<String> ignoredCategories) {
            this.ignoredCategories = ignoredCategories;
            return this;
        }

        public Helper setNamespaces(List<Integer> namespaces) {
            this.namespaces = namespaces;
            return this;
        }

        public Helper setCollator(Collator collator) {
            this.collator = collator;
            return this;
        }
    }

    public static Properties prepareSQLProperties() throws IOException {
        var defaultSQLProperties = new Properties();
        defaultSQLProperties.setProperty("tlsVersions", "TLSv1.2");
        return prepareSQLProperties(defaultSQLProperties);
    }

    public static Properties prepareSQLProperties(Properties defaults) throws IOException {
        var properties = new Properties(defaults);
        var cnf = Paths.get("./data/sessions/replica.my.cnf");

        P_CONFIG.matcher(Files.readString(cnf)).results().forEach(m -> properties.setProperty(m.group(1), m.group(2)));
        return properties;
    }

    public static Connection getConnection(String sqlUri, String fallbackSqlUri) throws SQLException {
        try {
            return getConnection(sqlUri, fallbackSqlUri, prepareSQLProperties());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Connection getConnection(String sqlUri, String fallbackSqlUri, Properties props) throws SQLException {
        try {
            if (props == null) {
                props = prepareSQLProperties();
            }

            Class.forName("com.mysql.cj.jdbc.Driver");
            return DriverManager.getConnection(sqlUri, props);
        } catch (SQLException e) {
            if (fallbackSqlUri == null || fallbackSqlUri.isEmpty()) {
                throw e;
            }

            return DriverManager.getConnection(fallbackSqlUri, props);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Set<String> getRecursiveCategoryMembers(Helper options) throws SQLException {
        Objects.requireNonNull(options);
        Objects.requireNonNull(options.sqlUri);
        Objects.requireNonNull(options.category);

        var articles = new HashSet<String>();
        var visitedCats = options.ignoredCategories.stream().map(cat -> cat.replace(' ', '_')).collect(Collectors.toCollection(HashSet::new));
        var targetCategories = Arrays.asList(options.category.replace(' ', '_'));
        var depth = 0;

        final var queryFmt = """
            SELECT
                DISTINCT page_title AS page_title,
                page_namespace
            FROM page
                LEFT JOIN categorylinks ON cl_from = page_id
                LEFT JOIN linktarget ON lt_id = cl_target_id
            WHERE
                page_is_redirect = 0 AND
                lt_title IN (%s);
            """;

        try (var connection = getConnection(options.sqlUri, options.fallbackSqlUri, options.sqlProperties)) {
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

                    if (options.namespaces.isEmpty() || options.namespaces.contains(ns)) {
                        members.add(title.replace('_', ' '));
                    }
                }

                articles.addAll(members);
                visitedCats.addAll(targetCategories);

                System.out.printf("depth = %d, members = %d, subcats = %d%n", depth, members.size(), subcats.size());

                subcats.removeAll(visitedCats);
                targetCategories = subcats;

                depth++;

                if (options.maxDepth != -1 && depth > options.maxDepth) {
                    break;
                }
            }
        }

        System.out.printf("Got %d category members for category \"%s\" (%d subcategories)%n",
                          articles.size(), options.category, visitedCats.size() - 1);

        return articles;
    }

    public static CategoryTree getRecursiveCategoryTree(Helper options) throws SQLException {
        Objects.requireNonNull(options);
        Objects.requireNonNull(options.sqlUri);
        Objects.requireNonNull(options.category);
        Objects.requireNonNull(options.collator);

        var visitedCats = new HashSet<String>();
        var nodes = new HashMap<String, CategoryTree.Node>();
        var targetCategories = Arrays.asList(options.category.replace(' ', '_'));
        var depth = 0;

        final CategoryTree tree;

        try (var connection = getConnection(options.sqlUri, options.fallbackSqlUri, options.sqlProperties)) {
            {
                var query = """
                    SELECT cat_pages - cat_subcats - cat_files AS members
                    FROM category
                    WHERE cat_title = '%s';
                    """.formatted(targetCategories.get(0).replace("'", "\\'"));

                var rs = connection.createStatement().executeQuery(query);

                if (!rs.next()) {
                    throw new SQLException("Category not found: " + options.category);
                }

                tree = new CategoryTree(options.category, rs.getInt("members"), options.collator);
            }

            nodes.put(targetCategories.get(0), tree.getRoot());

            final var queryFmt = """
                SELECT
                    lt_title,
                    page_title,
                    cat_pages - cat_subcats - cat_files AS members
                FROM page
                    LEFT JOIN categorylinks ON cl_from = page_id
                    LEFT JOIN linktarget ON lt_id = cl_target_id
                    LEFT JOIN category ON cat_title = page_title
                WHERE
                    page_namespace = 14 AND
                    lt_title IN (%s);
                """;

            while (!targetCategories.isEmpty()) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray);
                var rs = connection.createStatement().executeQuery(query);
                var subcats = new HashSet<String>();

                while (rs.next()) {
                    var parent = rs.getString("lt_title");
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
