package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;

public final class MissingPersonInfoboxes {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/MissingPersonInfoboxes/");
    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");
    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";
    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");
    private static final int MAX_SPARQL_RETRIES = 5;

    private static final JSONArray CATEGORY_MAPPINGS;

    static {
        try {
            SPARQL_REPO.setAdditionalHttpHeaders(Collections.singletonMap("User-Agent", Login.getUserAgent()));
            CATEGORY_MAPPINGS = new JSONArray(Files.readString(LOCATION.resolve("categories.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var biograms = queryBiograms();
        System.out.println("Biograms: " + biograms.size());

        var infoboxes = getRecursiveCategoryMembers("Infoboksy – biogramy", Wiki.TEMPLATE_NAMESPACE);
        System.out.println("Infoboxes: " + infoboxes.size());

        var templateLinks = queryTemplateLinks(infoboxes);
        System.out.println("Template links: " + templateLinks.size());

        biograms.removeAll(new HashSet<>(templateLinks));
        System.out.println("Biograms without infoboxes: " + biograms.size());

        var noInfoboxNotices = wb.whatTranscludesHere(List.of("Szablon:bez infoboksu"), Wiki.TALK_NAMESPACE).get(0).stream()
            .map(wb::removeNamespace)
            .toList();

        System.out.println("Talk pages with no_infobox notice: " + noInfoboxNotices.size());

        biograms.removeAll(new HashSet<>(noInfoboxNotices));
        System.out.println("Biograms without infoboxes and no_infobox notice on talk page: " + biograms.size());

        var collator = Collator.getInstance(new Locale("pl"));
        var results = biograms.stream().sorted(collator).toList();

        writeCompressedOutput(results, "all");

        for (var mapping : CATEGORY_MAPPINGS) {
            var category = ((JSONObject)mapping).getString("category");
            var filename = ((JSONObject)mapping).getString("filename");
            var members = getRecursiveCategoryMembers(category, Wiki.MAIN_NAMESPACE);
            var filtered = results.stream().filter(members::contains).toList();

            writeCompressedOutput(filtered, filename);
        }

        var now = DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        var dailyCount = String.format("%s %d%n", now, results.size());
        Files.writeString(LOCATION.resolve("daily-count.txt"), dailyCount, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<String> queryBiograms() {
        final var uriPrefix = "https://pl.wikipedia.org/wiki/";

        try (var connection = SPARQL_REPO.getConnection()) {
            var querySelect = String.format("""
                SELECT ?article
                WHERE
                {
                    ?item wdt:P31 wd:Q5 .
                    ?article schema:about ?item ;
                             schema:isPartOf <https://%s/> .
                }
                """, wb.getDomain());

            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream()
                        .map(bs -> ((IRI)bs.getValue("article")))
                        .map(iri -> iri.stringValue().substring(uriPrefix.length()).replace('_', ' '))
                        .map(pagename -> URLDecoder.decode(pagename, StandardCharsets.UTF_8))
                        .collect(Collectors.toCollection(ArrayList::new));
                } catch (QueryEvaluationException e) {
                    if (retry > MAX_SPARQL_RETRIES) {
                        throw e;
                    }

                    System.out.printf("Query failed with: %s (retry %d)%n", e.getMessage(), retry);
                }
            }
        }
    }

    private static Connection getConnection() throws ClassNotFoundException, IOException, SQLException {
        Class.forName("com.mysql.cj.jdbc.Driver");

        var props = DBUtils.prepareSQLProperties();

        try {
            return DriverManager.getConnection(SQL_PLWIKI_URI_SERVER, props);
        } catch (SQLException e) {
            return DriverManager.getConnection(SQL_PLWIKI_URI_LOCAL, props);
        }
    }

    private static Set<String> getRecursiveCategoryMembers(String category, int... namespaces) throws SQLException, ClassNotFoundException, IOException {
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

		try (var connection = getConnection()) {
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

    private static List<String> queryTemplateLinks(Collection<String> templates) throws SQLException, IOException, ClassNotFoundException {
        var links = new ArrayList<String>(400000);

        try (var connection = getConnection()) {
            var templatesStr = templates.stream()
                .map(title -> String.format("'%s'", title.replace(' ', '_').replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = String.format("""
                SELECT
                    DISTINCT(page_title)
                FROM page
                    INNER JOIN templatelinks ON page_id = tl_from
                    INNER JOIN linktarget ON tl_target_id = lt_id
                WHERE
                    page_namespace = %d AND
                    lt_namespace = %d AND
                    lt_title IN (%s);
                """, Wiki.MAIN_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, templatesStr);

            var result = connection.createStatement().executeQuery(query);

            while (result.next()) {
                links.add(result.getString("page_title").replace('_', ' '));
            }
        }

        return links;
    }

    private static final void writeCompressedOutput(List<String> lines, String filenameNoExt) throws IOException {
        var content = lines.stream().collect(Collectors.joining("\n"));
        System.out.printf("Writing %d entries (%d bytes) to %s.txt%n", lines.size(), content.getBytes().length, filenameNoExt);

        var zipPath = LOCATION.resolve(filenameNoExt + ".zip");
        var zipEntryName = filenameNoExt + ".txt";

        try (var stream = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            stream.putNextEntry(new ZipEntry(zipEntryName));
            stream.write(content.getBytes());
        }
    }
}
