package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;

final public class RefreshVideogameLists {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/RefreshVideogameLists/");
    private static final String TARGET_MAIN_PAGE = "Wikiprojekt:Gry komputerowe";
    private static final String TARGET_ARTICLE_LIST = "Wikiprojekt:Gry komputerowe/Lista artykułów";
    private static final String TARGET_MOST_LINKED_MISSING = "Wikiprojekt:Gry komputerowe/Najczęściej linkowane brakujące artykuły";
    private static final String TARGET_PROJECT_SUBPAGE_MISSING = "Wikiprojekt:Gry komputerowe/Najwięcej linkowań (rubryka)";
    private static final String TARGET_PROJECT_SUBPAGE_LANGLINKS = "Wikiprojekt:Gry komputerowe/Najwięcej interwiki (rubryka)";
    private static final List<String> TARGET_CATEGORIES = List.of("Gry komputerowe", "Kategorie według gier komputerowych");

    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";

    private static final SPARQLRepository SPARQL_REPO = new SPARQLRepository("https://query.wikidata.org/sparql");

    private static final int MAX_MOST_LINKED_PAGES = 500;
    private static final int MAX_PROJECT_SUBPAGE_SIZE = 15;
    private static final int MAX_SPARQL_RETRIES = 15;

    private static final Wikibot plwiki = Wikibot.newSession("pl.wikipedia.org");
    private static final Wikibot wd = Wikibot.newSession("www.wikidata.org");

    static {
        try {
            SPARQL_REPO.setAdditionalHttpHeaders(Collections.singletonMap("User-Agent", Login.getUserAgent()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Login.login(plwiki);
        Login.login(wd);

        final List<ArticleInfo> articleInfos;

        {
            System.out.println("Querying article list from database...");
            articleInfos = queryArticleList();
            var outArticleList = makeArticleListsPage(articleInfos);
            Files.writeString(LOCATION.resolve("articles.txt"), outArticleList);
            plwiki.edit(TARGET_ARTICLE_LIST, outArticleList, "aktualizacja");
        }

        final List<MissingTitle> missingTitles;

        {
            System.out.println("Querying most linked missing articles...");
            var titles = articleInfos.stream().map(ArticleInfo::title).toList();
            missingTitles = queryMostLinkedMissing(titles);
            var outMostLinkedMissing = makeMissingTitlesTable(missingTitles);
            Files.writeString(LOCATION.resolve("most_linked_missing.txt"), outMostLinkedMissing);
            plwiki.edit(TARGET_MOST_LINKED_MISSING, outMostLinkedMissing, "aktualizacja");
        }

        {
            System.out.println("Querying Wikidata items for articles...");
            var titles = missingTitles.stream().map(MissingTitle::title).toList();
            var wikidataItems = queryWikidataItems(titles);

            var outMissingMainPage = makeMissingMainPage(missingTitles, wikidataItems);
            Files.writeString(LOCATION.resolve("missing_main_page.txt"), outMissingMainPage);
            plwiki.edit(TARGET_PROJECT_SUBPAGE_MISSING, outMissingMainPage, "aktualizacja");

            var outLanglinksMainPage = makeLanglinksMainPage(wikidataItems);
            Files.writeString(LOCATION.resolve("langlinks_main_page.txt"), outLanglinksMainPage);
            plwiki.edit(TARGET_PROJECT_SUBPAGE_LANGLINKS, outLanglinksMainPage, "aktualizacja");
        }

        plwiki.purge(true, TARGET_MAIN_PAGE);
    }

    private static final List<ArticleInfo> queryArticleList() throws SQLException {
        var articles = new HashSet<ArticleInfo>();
        var visitedCats = new HashSet<String>();
        var targetCategories = TARGET_CATEGORIES.stream().map(cat -> cat.replace(' ', '_')).toList();
        var depth = 0;

        final var queryFmt = """
            SELECT
                DISTINCT page_title,
                page_namespace,
                page_id,
                page_len,
                rev_timestamp
            FROM page
                INNER JOIN revision ON rev_id = page_latest
                LEFT JOIN categorylinks ON cl_from = page_id
                LEFT JOIN linktarget ON lt_id = cl_target_id
            WHERE
                page_is_redirect = 0 AND
                lt_title IN (%s);
            """;

        try (var connection = DBUtils.getConnection(SQL_PLWIKI_URI_SERVER, SQL_PLWIKI_URI_LOCAL)) {
            while (!targetCategories.isEmpty()) {
                var catArray = targetCategories.stream()
                    .map(cat -> String.format("'%s'", cat.replace("'", "\\'")))
                    .collect(Collectors.joining(","));

                var query = String.format(queryFmt, catArray);
                var rs = connection.createStatement().executeQuery(query);

                var members = new ArrayList<ArticleInfo>();
                var subcats = new ArrayList<String>();

                while (rs.next()) {
                    var title = rs.getString("page_title");
                    var ns = rs.getInt("page_namespace");

                    if (ns == Wiki.CATEGORY_NAMESPACE) {
                        subcats.add(title);
                    } else if (ns == Wiki.MAIN_NAMESPACE) {
                        var length = rs.getInt("page_len");
                        var id = rs.getInt("page_id");
                        var lastChange = rs.getLong("rev_timestamp");

                        members.add(new ArticleInfo(title.replace('_', ' '), id, length, lastChange));
                    }
                }

                articles.addAll(members);
                visitedCats.addAll(targetCategories);

                System.out.printf("depth = %d, members = %d, subcats = %d%n", depth++, members.size(), subcats.size());

                subcats.removeAll(visitedCats);
                targetCategories = subcats;
            }
        }

        System.out.printf("Got %d category members (%d subcategories)%n", articles.size(), visitedCats.size() - 1);

        return articles.stream()
            .sorted(Comparator.comparing(ArticleInfo::title, Collator.getInstance(Locale.forLanguageTag("pl"))))
            .toList();
    }

    private static String makeArticleListsPage(List<ArticleInfo> articles) throws IOException {
        var categories = TARGET_CATEGORIES.stream().map(cat -> String.format("[[:Kategoria:%s]]", cat)).collect(Collectors.joining(", "));
        var sb = new StringBuilder();

        sb.append("Poniższa lista zawiera wszystkie artykuły należące do drzew: ");
        sb.append(categories).append(".\n\n");
        sb.append("Ostatnia aktualizacja: ~~~~~.\n----\n");

        sb.append("{| class=\"wikitable sortable\"\n");
        sb.append("! Nazwa strony !! ID !! Długość (w bajtach) !! Ostatnia zmiana \n");

        for (var article : articles) {
            sb.append("|-\n");

            sb.append("|[[").append(article.title()).append("]]||")
                .append(article.id()).append("||")
                .append(article.length()).append("||")
                .append(article.lastChange()).append("\n");
        }

        sb.append("|}\n\n");
        sb.append("[[Kategoria:Wikiprojekt Gry komputerowe]]");

        return sb.toString();
    }

    private static final List<MissingTitle> queryMostLinkedMissing(List<String> titles) throws SQLException {
        var escapedTitles = titles.stream()
            .map(title -> title.replace("'", "\\'").replace(' ', '_'))
            .collect(Collectors.joining("','"));

        final var query = """
            SELECT
                lt_title,
                COUNT(main_page.page_id) AS pagelinks
            FROM page AS main_page
                INNER JOIN pagelinks ON pl_from = main_page.page_id
                INNER JOIN linktarget ON pl_target_id = lt_id
                LEFT JOIN page AS missing_page ON
                    missing_page.page_title = lt_title AND
                    missing_page.page_namespace = lt_namespace
            WHERE
                main_page.page_title IN ('%s') AND
                main_page.page_namespace = 0 AND
                lt_namespace = 0 AND
                missing_page.page_id IS NULL
            GROUP BY
                lt_title
            """.formatted(escapedTitles);

        var missingTitles = new ArrayList<MissingTitle>();

        try (var connection = DBUtils.getConnection(SQL_PLWIKI_URI_SERVER, SQL_PLWIKI_URI_LOCAL)) {
            try (var statement = connection.prepareStatement(query)) {
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        var title = resultSet.getString("lt_title").replace('_', ' ');
                        var pagelinks = resultSet.getInt("pagelinks");
                        missingTitles.add(new MissingTitle(title, pagelinks));
                    }
                }
            }
        }

        var collator = Collator.getInstance(Locale.forLanguageTag("pl"));

        return missingTitles.stream()
            .sorted(Comparator.comparingInt(MissingTitle::pagelinks).reversed().thenComparing(MissingTitle::title, collator))
            .limit(MAX_MOST_LINKED_PAGES)
            .collect(Collectors.toList());
    }

    private static final String makeMissingTitlesTable(List<MissingTitle> missingTitles) {
        var categories = TARGET_CATEGORIES.stream().map(cat -> String.format("[[:Kategoria:%s]]", cat)).collect(Collectors.joining(", "));
        var sb = new StringBuilder();

        sb.append("Poniższa lista zawiera brakujące artykuły linkowane we wszystkich artykułach należących do drzew: ");
        sb.append(categories).append(". Wyniki ograniczono do ").append(MAX_MOST_LINKED_PAGES).append(" stron.\n\n");
        sb.append("Ostatnia aktualizacja: ~~~~~.\n----\n");
        sb.append("{| class=\"wikitable\"\n");
        sb.append("! Nazwa strony !! Linkujących \n");

        for (var missingTitle : missingTitles) {
            sb.append("|-\n");
            sb.append("|[[").append(missingTitle.title()).append("]]||");
            sb.append(missingTitle.pagelinks()).append("\n");
        }

        sb.append("|}\n\n");
        sb.append("[[Kategoria:Wikiprojekt Gry komputerowe]]");

        return sb.toString();
    }

    private static final List<WikidataItem> queryWikidataItems(List<String> titles) {
        var formattedTitles = titles.stream().map(title -> "\"%s\"@en".formatted(title)).collect(Collectors.joining("\n"));

        try (var connection = SPARQL_REPO.getConnection()) {
            var querySelect = """
                SELECT ?search ?item ?itemLabel ?itemDescription ?sitelink
                WHERE {
                    VALUES ?search {
                        %s
                    }
                    ?item rdfs:label ?search ;
                          wdt:P31 wd:Q7889 .
                    OPTIONAL {
                        ?sitelink schema:about ?item ;
                                  schema:isPartOf ?site .
                        FILTER(CONTAINS(STR(?site), "wikipedia.org"))
                    }
                    SERVICE wikibase:label {
                        bd:serviceParam wikibase:language "[AUTO_LANGUAGE],mul,en".
                    }
                }
                """.formatted(formattedTitles);

            var query = connection.prepareTupleQuery(querySelect);

            for (var retry = 1; ; retry++) {
                try (var result = query.evaluate()) {
                    return result.stream()
                        .map(bs -> new WikidataItem(
                            ((IRI)bs.getValue("item")).getLocalName(),
                            ((Literal)bs.getValue("search")).stringValue(),
                            bs.hasBinding("sitelink") ? ((IRI)bs.getValue("sitelink")).stringValue() : ""
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

    private static final String makeMissingMainPage(List<MissingTitle> titles, List<WikidataItem> wikidataItems) {
        var grouped = wikidataItems.stream().collect(Collectors.groupingBy(WikidataItem::title));

        return titles.stream()
            .filter(missingTitleItem -> grouped.containsKey(missingTitleItem.title()))
            .limit(MAX_PROJECT_SUBPAGE_SIZE)
            .map(missingTitleItem -> {
                var qid = grouped.get(missingTitleItem.title()).stream()
                    .collect(Collectors.groupingBy(
                        WikidataItem::qid,
                        Collectors.counting()
                    ))
                    .entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .get();

                return "* {{Link-interwiki|%s|Q=%s}} (%d)\n"
                    .formatted(missingTitleItem.title(), qid, missingTitleItem.pagelinks());
            })
            .collect(Collectors.joining());
    }

    private static final String makeLanglinksMainPage(List<WikidataItem> wikidataItems) {
        var langlinks = wikidataItems.stream()
            .collect(Collectors.groupingBy(WikidataItem::qid))
            .entrySet().stream()
            .map(entry -> new Langlink(
                entry.getValue().get(0).title(),
                entry.getKey(),
                entry.getValue().stream()
                    .map(WikidataItem::sitelink)
                    .filter(sitelink -> !sitelink.isBlank())
                    .distinct()
                    .count()
            ))
            .collect(Collectors.toMap(
                Langlink::title,
                UnaryOperator.identity(),
                (first, second) -> first.count() >= second.count() ? first : second
            ))
            .values().stream()
            .sorted(Comparator.comparingLong(Langlink::count).reversed())
            .limit(MAX_PROJECT_SUBPAGE_SIZE)
            .toList();

        return langlinks.stream()
            .map(langlink -> "* {{Link-interwiki|%s|Q=%s}} (%d)\n"
                .formatted(langlink.title(), langlink.qid(), langlink.count()))
            .collect(Collectors.joining());
    }

    record ArticleInfo(String title, int id, int length, long lastChange) {}
    record MissingTitle(String title, int pagelinks) {}
    record WikidataItem(String qid, String title, String sitelink) {}
    record Langlink(String title, String qid, long count) {}
}
