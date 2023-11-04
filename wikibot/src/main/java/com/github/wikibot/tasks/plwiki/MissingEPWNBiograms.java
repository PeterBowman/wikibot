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

public final class MissingEPWNBiograms {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/MissingEPWNBiograms/");
    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");
    private static final int MAX_SPARQL_RETRIES = 5;
    private static final String SQL_WDWIKI_URI_SERVER = "jdbc:mysql://wikidatawiki.analytics.db.svc.wikimedia.cloud:3306/wikidatawiki_p";
    private static final String SQL_WDWIKI_URI_LOCAL = "jdbc:mysql://localhost:4750/wikidatawiki_p";
    private static final String TARGET = "Wikipedysta:PBbot/brakujące biogramy na podstawie ePWN i WD";
    private static final Collator PL_COLLATOR = Collator.getInstance(Locale.forLanguageTag("pl-PL"));

    private static final String INTRO = """
        Elementy spełniające {{PID|31}} = {{QID|5}} oraz {{PID|27}} = {{QID|36}} z podanym {{PID|7305}} bez artykułu w polskojęzycznej Wikipedii.

        Aktualizacja: ~~~~~
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
        var hashPath = LOCATION.resolve("hash.txt");
        var items = queryBiograms();

        System.out.println("Got %d items.".formatted(items.size()));
        querySitelinks(items);
        Collections.sort(items, Comparator.<Item, Integer>comparing(i -> i.sitelinks).reversed().thenComparing(i -> i.label, PL_COLLATOR));

        if (Files.exists(hashPath) && Integer.parseInt(Files.readString(hashPath).trim()) == items.hashCode()) {
            System.out.println("No changes.");
            return;
        }

        items.forEach(System.out::println);

        Login.login(wiki);
        wiki.edit(TARGET, INTRO + "\n" + makeText(items), "aktualizacja (%d)".formatted(items.size()));

        Files.writeString(hashPath, String.valueOf(items.hashCode()));
    }

    private static List<Item> queryBiograms() {
        try (var connection = SPARQL_REPO.getConnection()) {
            var querySelect = """
                SELECT DISTINCT ?item ?itemLabel ?epwn
                WHERE
                {
                ?item wdt:P31 wd:Q5 ;
                      wdt:P27 wd:Q36 ;
                      wdt:P7305 ?epwn .
                MINUS
                {
                    ?article schema:about ?item ;
                             schema:isPartOf <https://pl.wikipedia.org/> .
                }
                SERVICE wikibase:label { bd:serviceParam wikibase:language "[AUTO_LANGUAGE],pl,en". }
                }
                """;

            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream().map(Item::fromQuarryResult).collect(Collectors.toCollection(ArrayList::new)); // mutable
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

    private static String makeText(List<Item> items) {
        var rows = items.stream()
            .map(Item::makeRow)
            .collect(Collectors.joining("\n|-\n"));

        return """
            {| class="wikitable"
            ! element WD !! hasło w ePWN !! # projektów
            |-
            %s
            |}
            """.formatted(rows);
    }

    private static class Item {
        String qid;
        String label;
        int pwn;
        int sitelinks;

        static Item fromQuarryResult(BindingSet bs) {
            var i = new Item();
            i.qid = ((IRI)bs.getValue("item")).getLocalName();
            i.label = ((Literal)bs.getValue("itemLabel")).stringValue();
            i.pwn = ((Literal)bs.getValue("epwn")).intValue();
            return i;
        }

        String makeRow() {
            return String.format("| [[d:%s]] || [https://encyklopedia.pwn.pl/haslo/;%d %s] || %d", qid, pwn, label, sitelinks);
        }

        public String toString() {
            return String.format("[%s,%s,%d,%d]", qid, label, pwn, sitelinks);
        }

        public int hashCode() {
            return qid.hashCode();
        }
    }
}
