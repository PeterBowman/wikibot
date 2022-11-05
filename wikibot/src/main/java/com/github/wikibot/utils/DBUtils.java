package com.github.wikibot.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
        return getRecursiveCategoryMembers(sqlUri, props, category, namespaces);
    }

    public static Set<String> getRecursiveCategoryMembers(String sqlUri, Properties props, String category, int... namespaces) throws SQLException {
		var targetNs = Arrays.stream(namespaces).boxed().toList();
        var articles = new HashSet<String>();
		var visitedCats = new HashSet<String>();
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

		System.out.printf("Got %d category members for category %s%n", articles.size(), category);
		return articles;
	}
}
