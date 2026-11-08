package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
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
    private static final int MAX_SPARQL_RETRIES = 15;

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

        var options = new DBUtils.Helper()
            .setSqlUri(SQL_PLWIKI_URI_SERVER)
            .setFallbackSqlUri(SQL_PLWIKI_URI_LOCAL)
            .setCategory("Infoboksy – biogramy")
            .setNamespaces(List.of(Wiki.TEMPLATE_NAMESPACE));

        var infoboxes = DBUtils.getRecursiveCategoryMembers(options);
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

        var collator = Collator.getInstance(Locale.forLanguageTag("pl"));
        var results = biograms.stream().sorted(collator).toList();

        writeCompressedOutput(results, "all");

        for (var mapping : CATEGORY_MAPPINGS) {
            var category = ((JSONObject)mapping).getString("category");
            var filename = ((JSONObject)mapping).getString("filename");
            var members = DBUtils.getRecursiveCategoryMembers(new DBUtils.Helper()
                .setSqlUri(SQL_PLWIKI_URI_SERVER)
                .setFallbackSqlUri(SQL_PLWIKI_URI_LOCAL)
                .setCategory(category)
                .setNamespaces(List.of(Wiki.MAIN_NAMESPACE))
            );
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
            var querySelect = """
                SELECT ?article
                WHERE
                {
                    ?item wdt:P31 wd:Q5 .
                    ?article schema:about ?item ;
                             schema:isPartOf <https://%s/> .
                }
                """.formatted(wb.getDomain());

            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream()
                        .map(bs -> bs.getValue("article"))
                        .filter(Value::isIRI)
                        .map(v -> ((IRI)v).stringValue())
                        .map(iri -> iri.substring(uriPrefix.length()).replace('_', ' '))
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

    private static List<String> queryTemplateLinks(Collection<String> templates) throws SQLException {
        var links = new ArrayList<String>(400000);

        try (var connection = DBUtils.getConnection(SQL_PLWIKI_URI_SERVER, SQL_PLWIKI_URI_LOCAL)) {
            var templatesStr = templates.stream()
                .map(title -> String.format("'%s'", title.replace(' ', '_').replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    DISTINCT(page_title)
                FROM page
                    INNER JOIN templatelinks ON page_id = tl_from
                    INNER JOIN linktarget ON tl_target_id = lt_id
                WHERE
                    page_namespace = %d AND
                    lt_namespace = %d AND
                    lt_title IN (%s);
                """.formatted(Wiki.MAIN_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, templatesStr);

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
