package com.github.wikibot.tasks.plwikinews;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleLiteral;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class ReportDeaths {
    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");

    private static final int MAX_SPARQL_RETRIES = 5;
    private static final int LATEST_OFFSET_DAYS = 1;
    private static final int TIME_SPAN_DAYS = 7;

    private static final String SPARQL_ENTRYPOINT = "https://query.wikidata.org/embed.html#";

    private static final String SPARQL_QUERY_TEMPLATE = """
        # Humans who died on a specific date on Polish Wikipedia, ordered by label
        SELECT ?item (MIN(?name) AS ?articlename) (MIN(?img) AS ?image) (MIN(?dob) AS ?dateOfBirth) (MIN(?itemLabel) AS ?label) (MIN(?itemDescription) AS ?description)
        WHERE {
            VALUES ?dod {"+%s"^^xsd:dateTime}
            ?dod ^wdt:P570 ?item .
            ?item ^schema:about ?article .
            ?article schema:isPartOf <https://pl.wikipedia.org/> ;
                     schema:name ?name .
            OPTIONAL {
                ?item wdt:P18 ?img .
            }
            OPTIONAL {
                ?item wdt:P569 ?dob .
            }
            SERVICE wikibase:label {
                bd:serviceParam wikibase:language "pl" .
                ?item rdfs:label ?itemLabel ;
                      schema:description ?itemDescription .
            }
            BIND(REPLACE(?itemLabel, "^.*(?<! [Vv][ao]n| [Dd][aeiu]| [Dd][e][lns]| [Ll][ae]) (?!([SJ]r\\\\.?|[XVI]+)$)", "") AS ?sortname)
        } GROUP BY ?item ORDER BY ASC(?dateOfBirth) ASC(UCASE(?sortname))
        """;

    private static final String ARTICLE_TEMPLATE = """
        {{data|%s}}
        %s
        '''%s zmarli:'''
        %s

        == Źródło ==
        * To zestawienie zostało automatycznie wygenerowane z Wikidanych. Zobacz [%s zapytanie SPARQL].

        {{Pasek boczny|
        {{Społeczeństwo}}
        {{Podziel się boczny}}
        }}

        [[Kategoria:Archiwalne]]
        [[Kategoria:Nekrologi %d]]
        """;

    private static final Wikibot wb = Wikibot.newSession("pl.wikinews.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        final var today = LocalDate.now();
        final var formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(new Locale("pl"));

        for (int n = LATEST_OFFSET_DAYS; n < LATEST_OFFSET_DAYS + TIME_SPAN_DAYS; n++) {
            var refDate = today.minusDays(n);
            var isoDate = refDate.format(DateTimeFormatter.ISO_DATE);
            var query = String.format(SPARQL_QUERY_TEMPLATE, isoDate);
            var encodedQuery = SPARQL_ENTRYPOINT + URLEncoder.encode(query, "UTF-8").replace("+", "%20");
            var shortenedUrl = wb.shortenUrl(encodedQuery);
            var items = queryItems(query);

            if (items.isEmpty()) {
                System.out.println("No items found for " + refDate);
                continue;
            }

            System.out.printf("Retrieved %d items for %s:%n", items.size(), refDate);
            items.forEach(System.out::println);

            var list = items.stream()
                .map(i -> String.format("* [[w:%s|%s]] (%s[[d:%s|WD]])%s",
                    i.article(),
                    i.optLabel().orElse(i.sanitizedArticle()),
                    i.optYearOfBirth().map(year -> String.format("ur. %d, ", year)).orElse(""),
                    i.item(),
                    i.optDescription().map(desc -> String.format(": %s", desc)).orElse("")
                ))
                .toList();

            var image = items.stream()
                .filter(i -> i.optImage().isPresent())
                .findAny()
                .map(i -> String.format("[[Plik:%s|thumb|%s]]",
                    i.optImage().get(),
                    i.optLabel().orElse(i.sanitizedArticle()) +
                        i.optYearOfBirth().map(year -> String.format(" (%d–%d)", year, refDate.getYear())).orElse("")
                ))
                .orElse("");

            var localizedDate = formatter.format(refDate);
            var title = String.format("Zmarli %s", localizedDate);
            var text = String.format(ARTICLE_TEMPLATE, isoDate, image, localizedDate, String.join("\n", list), shortenedUrl, refDate.getYear());

            if (n == LATEST_OFFSET_DAYS) {
                text = text.replace("[[Kategoria:Archiwalne]]\n", "");
            }

            wb.edit(title, text, "nekrolog na podstawie " + shortenedUrl);
        }
    }

    private static List<Item> queryItems(String querySelect) {
        try (var connection = SPARQL_REPO.getConnection()) {
            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream()
                        .map(bs -> new Item(
                            ((IRI)bs.getValue("item")).getLocalName(),
                            ((SimpleLiteral)bs.getValue("articlename")).stringValue(),
                            Optional.ofNullable(((IRI)bs.getValue("image")))
                                .map(IRI::getLocalName)
                                .map(image -> URLDecoder.decode(image, StandardCharsets.UTF_8)),
                            Optional.ofNullable(((SimpleLiteral)bs.getValue("dateOfBirth")))
                                .map(SimpleLiteral::stringValue)
                                .flatMap(ReportDeaths::parseYear),
                            Optional.ofNullable(((SimpleLiteral)bs.getValue("label")))
                                .map(SimpleLiteral::stringValue)
                                .filter(label -> !label.matches("^Q\\d+$")),
                            Optional.ofNullable(((SimpleLiteral)bs.getValue("description")))
                                .map(SimpleLiteral::stringValue)
                        ))
                        .toList();
                } catch (QueryEvaluationException e) {
                    if (retry > MAX_SPARQL_RETRIES) {
                        throw e;
                    }

                    System.out.printf("Query failed with: %s (retry %d)%n", e.getMessage(), retry);
                }
            }
        }
    }

    private static Optional<Integer> parseYear(String date) {
        try {
            return Optional.of(Instant.parse(date).atZone(ZoneOffset.UTC).getYear());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private record Item(String item, String article, Optional<String> optImage, Optional<Integer> optYearOfBirth, Optional<String> optLabel, Optional<String> optDescription) {
        String sanitizedArticle() {
            return article.replaceFirst("\\([^\\(\\)]*+\\)$", "").stripTrailing();
        }
    }
}
