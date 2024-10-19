package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.wikipedia.Wiki;

import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;

public final class MissingWikidataBiograms {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/MissingWikidataBiograms/");
    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");
    private static final int MAX_SPARQL_RETRIES = 15;
    private static final String SQL_WDWIKI_URI_SERVER = "jdbc:mysql://wikidatawiki.analytics.db.svc.wikimedia.cloud:3306/wikidatawiki_p";
    private static final String SQL_WDWIKI_URI_LOCAL = "jdbc:mysql://localhost:4750/wikidatawiki_p";
    private static final String TARGET = "Wikipedysta:PBbot/brakujące biogramy na podstawie WD";
    private static final Collator PL_COLLATOR = Collator.getInstance(Locale.forLanguageTag("pl-PL"));

    private static final List<String> PROPERTY_IDS = List.of(
        "P7305", // identyfikator internetowej Encyklopedii PWN
        "P7982", // identyfikator EFIS
        "P1417"  // identyfikator Encyclopædia Britannica Online
    );

    private static final Map<String, String> PROPERTY_URLS = Map.of(
        "P7305", "https://encyklopedia.pwn.pl/haslo/;%s",
        "P7982", "https://www.enciklopedija.hr/Natuknica.aspx?ID=%s",
        "P1417", "https://www.britannica.com/%s"
    );

    private static final String INTRO = """
        Elementy spełniające {{PID|31}} = {{QID|5}} oraz {{PID|27}} = {{QID|36}} ze wskazanym niżej identyfikatorem i bez artykułu w polskojęzycznej Wikipedii.

        Aktualizacja: ~~~~~. __FORCETOC__
        """;

    private static final Wiki wiki = Wiki.newSession("pl.wikipedia.org");

    static {
        try {
            SPARQL_REPO.setAdditionalHttpHeaders(Collections.singletonMap("User-Agent", Login.getUserAgent()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        var results = new ArrayList<List<Item>>();

        for (var pid : PROPERTY_IDS) {
            System.out.printf("Querying for PID = %s%n", pid);

            var items = queryBiograms(pid);
            System.out.println("Got %d items.".formatted(items.size()));

            querySitelinks(items);
            Collections.sort(items, Comparator.<Item, Integer>comparing(i -> i.sitelinks).reversed().thenComparing(i -> i.label, PL_COLLATOR));
            items.forEach(System.out::println);

            results.add(items);
        }

        var sections = new ArrayList<String>();

        for (var i = 0; i < PROPERTY_IDS.size(); i++) {
            var text = makeText(results.get(i), PROPERTY_IDS.get(i));
            sections.add(text);
        }

        var pageText = INTRO + "\n" + String.join("\n", sections);
        var hashPath = LOCATION.resolve("hash.txt");

        if (Files.exists(hashPath) && Integer.parseInt(Files.readString(hashPath).trim()) == pageText.hashCode()) {
            System.out.println("No changes.");
            return;
        }

        Login.login(wiki);
        wiki.edit(TARGET, pageText, "aktualizacja");

        Files.writeString(hashPath, String.valueOf(pageText.hashCode()));
    }

    private static List<Item> queryBiograms(String pid) {
        try (var connection = SPARQL_REPO.getConnection()) {
            var querySelect = """
                SELECT DISTINCT ?item ?itemLabel (SAMPLE(?source) AS ?source)
                WHERE
                {
                ?item wdt:P31 wd:Q5 ;
                      wdt:P27 wd:Q36 ;
                      wdt:%s ?source .
                MINUS
                {
                    ?article schema:about ?item ;
                             schema:isPartOf <https://pl.wikipedia.org/> .
                }
                SERVICE wikibase:label { bd:serviceParam wikibase:language "[AUTO_LANGUAGE],pl,en". }
                }
                GROUP BY ?item ?itemLabel
                """.formatted(pid);

            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream()
                        .map(Item::fromSparqlResult)
                        .collect(Collectors.toCollection(ArrayList::new)); // mutable
                } catch (QueryEvaluationException e) {
                    if (retry > MAX_SPARQL_RETRIES) {
                        throw e;
                    }

                    System.out.printf("Query failed with: %s (retry %d)%n", e.getMessage(), retry);
                }
            }
        }
    }

    private static void querySitelinks(List<Item> items) throws SQLException, IOException, ClassNotFoundException {
        Function<Item, String> sanitizeQid = i -> i.qid.substring(1); // remove 'Q' prefix

        var mappings = items.stream().collect(Collectors.toMap(sanitizeQid, UnaryOperator.identity()));

        try (var connection = getConnection()) {
            var query = """
                SELECT
                    ips_item_id,
                    COUNT(ips_row_id) AS count
                FROM
                    wb_items_per_site
                WHERE
                    ips_item_id IN (%s)
                GROUP BY
                    ips_item_id
                """.formatted(items.stream().map(sanitizeQid).collect(Collectors.joining(",")));

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var qid = rs.getString("ips_item_id");
                var count = rs.getInt("count");

                mappings.get(qid).sitelinks = count;
            }
        }
    }

    private static Connection getConnection() throws ClassNotFoundException, IOException, SQLException {
        Class.forName("com.mysql.cj.jdbc.Driver");

        try {
            return DriverManager.getConnection(SQL_WDWIKI_URI_SERVER, DBUtils.prepareSQLProperties());
        } catch (SQLException e) {
            return DriverManager.getConnection(SQL_WDWIKI_URI_LOCAL, DBUtils.prepareSQLProperties());
        }
    }

    private static String makeText(List<Item> items, String pid) {
        var rows = items.stream()
            .map(i -> i.makeRow(PROPERTY_URLS.get(pid)))
            .collect(Collectors.joining("\n|-\n"));

        return """
            == {{PID|%s}} ==

            {| class="wikitable"
            ! element WD !! hasło w źródle !! # projektów
            |-
            %s
            |}
            """.formatted(pid.substring(1), rows);
    }

    private static class Item {
        String qid;
        String label;
        String id;
        int sitelinks;

        static Item fromSparqlResult(BindingSet bs) {
            var i = new Item();
            i.qid = ((IRI)bs.getValue("item")).getLocalName();
            i.label = ((Literal)bs.getValue("itemLabel")).stringValue();
            i.id = ((Literal)bs.getValue("source")).stringValue();
            return i;
        }

        String makeRow(String urlFormat) {
            var url = String.format(urlFormat, id);
            return String.format("| [[d:%s]] || [%s %s] || %d", qid, url, label, sitelinks);
        }

        public String toString() {
            return String.format("[%s,%s,%s,%d]", qid, label, id, sitelinks);
        }

        public int hashCode() {
            return qid.hashCode();
        }
    }
}
