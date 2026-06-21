package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;

final public class RefreshComputerGamesLists {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/RefreshComputerGamesLists/");
    private static final String TARGET_ARTICLE_LIST = "Wikiprojekt:Gry komputerowe/Lista artykułów";
    private static final String TARGET_MOST_LINKED_MISSING = "Wikiprojekt:Gry komputerowe/Najczęściej linkowane brakujące artykuły";
    private static final String TARGET_PROJECT_MAIN_PAGE = "Wikiprojekt:Gry komputerowe";
    private static final List<String> TARGET_CATEGORIES = List.of("Gry komputerowe", "Kategorie według gier komputerowych");

    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        {
            System.out.println("Querying article list from database...");
            var articles = queryArticleList();
            var outArticleList = makeArticleListsPage(articles);
            Files.writeString(LOCATION.resolve("articles.txt"), outArticleList);
            wb.edit(TARGET_ARTICLE_LIST, outArticleList, "aktualizacja");
        }

        {
            System.out.println("Querying most linked missing articles from database...");
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

    private static final List<ArticleInfo> queryArticleList() throws ClassNotFoundException, SQLException, IOException {
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

        try (var connection = getConnection()) {
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

        sb.append("Poniższa lista zawiera wszystkie artykuły należące do drzew: ").append(categories).append(".\n\n");
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

    private static final String queryMostLinkedMissing() throws ClassNotFoundException, SQLException, IOException {
        try (var connection = getConnection()) {
            return null;
        }
    }

    record ArticleInfo(String title, int id, int length, long lastChange) {}
}
