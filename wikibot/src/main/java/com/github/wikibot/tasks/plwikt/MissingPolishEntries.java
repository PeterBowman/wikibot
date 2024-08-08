package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.MorfeuszLookup;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public class MissingPolishEntries {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishEntries/");
    private static final Path HASH_PATH = LOCATION.resolve("hash.txt");
    private static final Path TITLES_PATH = LOCATION.resolve("titles.txt");

    private static final String TARGET_PAGE = "Wikipedysta:PBbot/brakujące polskie";
    private static final String DOWNLOAD_URL = "http://download.sgjp.pl/morfeusz/";
    private static final String DUMP_FILENAME_FORMAT = "sgjp-%s.tab.gz";

    private static final String SQL_PLWIKT_URI_SERVER = "jdbc:mysql://plwiktionary.analytics.db.svc.wikimedia.cloud:3306/plwiktionary_p";
    private static final String SQL_PLWIKT_URI_LOCAL = "jdbc:mysql://localhost:4713/plwiktionary_p";

    private static final String PAGE_INTRO;
    private static final Stats stats = new Stats();
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    static {
        PAGE_INTRO = """
            {{TOCright}}{{język linków|polski}}

            Spis brakujących haseł polskich na podstawie bazy danych Morfeusz SGJP ([http://morfeusz.sgjp.pl/download/ <tt>%1$s</tt>]).
            Poniższe strony istnieją, lecz brak sekcji polskiej.
            * stron w sumie (przestrzeń główna, bez przekierowań): %2$s
            * haseł polskich: %3$s (podstawowe), %4$s (formy fleksyjne)
            * haseł w bazie SGJP: %5$s (wraz z formami odmienionymi: %6$s)
            * rozmiar listy: %7$s
            Wygenerowano ~~~~~.
            """;
    }

    public static void main(String[] args) throws Exception {
        var latestDumpDir = retrieveLatestDumpDir();
        System.out.println("Latest dump directory: " + latestDumpDir);

        var titles = retrieveNonPolishEntries();
        retainSgjpEntries(latestDumpDir, titles);

        System.out.println(stats);

        if (Files.exists(HASH_PATH) && Integer.parseInt(Files.readString(HASH_PATH)) == titles.hashCode()) {
            System.out.println("No changes detected, aborting.");
            return;
        }

        Files.writeString(HASH_PATH, Integer.toString(titles.hashCode()));
        Files.write(TITLES_PATH, titles);
        System.out.printf("%d titles stored.%n", titles.size());

        Login.login(wb);
        wb.setMarkBot(false);
        wb.edit(TARGET_PAGE, getOutput(titles), "aktualizacja");
    }

    private static String retrieveLatestDumpDir() throws IOException {
        return Jsoup.connect(DOWNLOAD_URL).get().select("td a").stream()
            .map(Element::text)
            .filter(href  -> href.matches("^\\d{8}/$"))
            .map(href -> href.substring(0, href.length() - 1))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private static List<String> retrieveNonPolishEntries() throws IOException, ClassNotFoundException, SQLException {
        var titles = getAllArticles();
        var polishCanonical = getCategoryMembers("polski (indeks)");
        var polishInflected = getCategoryMembers("polski (formy fleksyjne)");

        titles.removeAll(new HashSet<>(polishCanonical));
        titles.removeAll(new HashSet<>(polishInflected));

        stats.allEntries = titles.size();
        stats.polishLemmas = polishCanonical.size();
        stats.polishInflected = polishInflected.size();

        return titles;
    }

    private static Connection getConnection() throws ClassNotFoundException, IOException, SQLException {
        Class.forName("com.mysql.cj.jdbc.Driver");

        var props = DBUtils.prepareSQLProperties();

        try {
            return DriverManager.getConnection(SQL_PLWIKT_URI_SERVER, props);
        } catch (SQLException e) {
            return DriverManager.getConnection(SQL_PLWIKT_URI_LOCAL, props);
        }
    }

    private static List<String> getAllArticles() throws ClassNotFoundException, SQLException, IOException {
        var titles = new ArrayList<String>(1000000);

        try (var connection = getConnection()) {
            var query = """
                SELECT page_title
                FROM page
                WHERE page_namespace = 0 AND page_is_redirect = 0;
                """;

            var resultSet = connection.createStatement().executeQuery(query);

            while (resultSet.next()) {
                titles.add(resultSet.getString("page_title").replace('_', ' '));
            }
        }

        return titles;
    }

    private static List<String> getCategoryMembers(String category) throws ClassNotFoundException, SQLException, IOException {
        var titles = new ArrayList<String>(1000000);

        try (var connection = getConnection()) {
            var query = """
                SELECT page_title
                FROM page
                INNER JOIN categorylinks ON cl_from = page_id
                WHERE page_namespace = 0 AND page_is_redirect = 0 AND cl_to = '%s';
                """.formatted(category.replace(' ', '_').replace("'", "\\'"));

            var resultSet = connection.createStatement().executeQuery(query);

            while (resultSet.next()) {
                titles.add(resultSet.getString("page_title").replace('_', ' '));
            }
        }

        return titles;
    }

    private static void retainSgjpEntries(String dumpDir, List<String> titles) throws IOException {
        stats.dumpFile = String.format(DUMP_FILENAME_FORMAT, dumpDir);
        var url = URI.create(DOWNLOAD_URL + dumpDir + "/" + stats.dumpFile).toURL();

        try (var stream = MorfeuszLookup.fromInputStream(url.openStream())) {
            var database = stream.map(MorfeuszLookup.Record::lemma).collect(Collectors.toSet());
            titles.retainAll(database);

            stats.databaseLemmas = database.size();
            stats.worklistSize = titles.size();
        }

        // cheap enough
        try (var stream = MorfeuszLookup.fromInputStream(url.openStream())) {
            stats.databaseOverall = (int)stream.count();
        }
    }

    private static String getOutput(List<String> titles) {
        var coll = Collator.getInstance(Locale.forLanguageTag("pl-PL"));
        coll.setStrength(Collator.SECONDARY);

        var numberFormatter = NumberFormatter.withLocale(Locale.forLanguageTag("pl-PL")).grouping(GroupingStrategy.MIN2);

        var map = titles.stream()
            .collect(Collectors.groupingBy(
                title -> Character.toString(title.charAt(0)).toLowerCase(),
                () -> new TreeMap<>(coll),
                Collectors.toList()
            ));

        var out = map.entrySet().stream()
            .map(e -> String.format(
                    "== %s ==\n%s",
                    e.getKey(),
                    e.getValue().stream()
                        .sorted(coll)
                        .map(v -> String.format("[[%s]]", v))
                        .collect(Collectors.joining(", ")))
                )
                .collect(Collectors.joining("\n\n"));

        return String.format(PAGE_INTRO,
                stats.dumpFile,
                numberFormatter.format(stats.allEntries),
                numberFormatter.format(stats.polishLemmas),
                numberFormatter.format(stats.polishInflected),
                numberFormatter.format(stats.databaseLemmas),
                numberFormatter.format(stats.databaseOverall),
                numberFormatter.format(stats.worklistSize)
            ) + "\n" + out;
    }

    private static class Stats {
        int allEntries;
        int polishLemmas;
        int polishInflected;
        int databaseLemmas;
        int databaseOverall;
        int worklistSize;

        String dumpFile;

        @Override
        public String toString() {
            return """
                Stats for the current run:
                * reading from: %s
                * total worklist size: %d
                * all entries: %d
                * polish entries (lemmas): %d
                * polish entries (inflected): %d
                * database lemmas: %d
                * database overall size: %d
                """.formatted(
                    dumpFile,
                    worklistSize, allEntries, polishLemmas, polishInflected,
                    databaseLemmas, databaseOverall
                ).stripTrailing();
        }
    }
}
