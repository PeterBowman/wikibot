package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.security.auth.login.AccountLockedException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class AuthorityControl {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/AuthorityControl/");

    private static final List<String> TEMPLATES = List.of("Kontrola autorytatywna", "Authority control", "Ka");
    private static final List<String> PROPERTIES;
    private static final List<String> PROPERTIES_ENC; // only encyclopaedias, split from above for efficiency

    private static final Properties SQL_PROPS;
    private static final String SQL_WDWIKI_URI = "jdbc:mysql://wikidatawiki.analytics.db.svc.wikimedia.cloud:3306/wikidatawiki_p";
    private static final String SQL_PLWIKI_URI = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    private static final Pattern P_TEXT = Pattern.compile(
        """
        # DEFAULTSORT + category + HTML comments (no strict order and optional)
        (?:
            \\s*+\\{{2}\\s*+(?:SORTUJ|DOMYŚLNIESORTUJ|DEFAULTSORT|DEFAULTSORTKEY|DEFAULTCATEGORYSORT):[^}]*+\\}{2}
            |
            \\s*+\\[{2}\\ *+(?i:Kategoria|Category)\\ *+:[^]\n]*+\\]{2}
            |
            \\s*+<!--.+?-->
        )*+

        # __NOINDEX__
        (?:\\s*+_{2}(?i:NOINDEX|NIEINDEKSUJ|NOTOC|BEZSPISU)_{2})?+

        # interwiki
        (?:\\s*+\\[{2}\\ *+[a-z-]++\\ *+:[^]\n]*+\\]{2})*+

        # end of article
        $
        """, Pattern.COMMENTS);

    static {
        record Item(String property, boolean isEncyclopaedia) {}

        var patt = Pattern.compile("^(P\\d+)( ++\\(e\\))? *+(?=#|$)");

        try {
            // https://pl.wikipedia.org/wiki/Szablon:Kontrola_autorytatywna#Lista_wspieranych_baz
            var items = Files.readAllLines(LOCATION.resolve("properties.txt")).stream()
                .flatMap(text -> patt.matcher(text).results())
                .map(m -> new Item(m.group(1), m.group(2) != null))
                .toList();

            PROPERTIES = items.stream().map(Item::property).toList();
            PROPERTIES_ENC = items.stream().filter(Item::isEncyclopaedia).map(Item::property).toList();

            SQL_PROPS = DBUtils.prepareSQLProperties();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        var cli = readOptions(args);
        var articles = new HashSet<String>();

        if (cli.hasOption("file")) {
            var file = cli.getOptionValue("file");
            articles.addAll(Files.readAllLines(LOCATION.resolve(file)));
            System.out.printf("Retrieved %d articles from stored list.%n", articles.size());
        } else {
            if (!cli.hasOption("dump") && !cli.hasOption("incr")) {
                throw new RuntimeException("missing mandatory CLI parameters");
            }

            if (cli.hasOption("dump")) {
                var datePath = LOCATION.resolve("last_dump_date.txt");
                var config = new XMLDumpConfig("wikidatawiki").type(XMLDumpTypes.PAGES_ARTICLES_MULTISTREAM).local();

                if (Files.exists(datePath)) {
                    config.after(Files.readString(datePath).strip());
                }

                var optDump = config.fetch();

                if (optDump.isEmpty()) {
                    System.out.println("No multistream dump found.");
                } else {
                    var dump = optDump.get();
                    articles.addAll(processBiweeklyDump(dump));
                    Files.writeString(datePath, dump.getDirectoryName());
                }
            }

            if (cli.hasOption("incr")) {
                var datePath = LOCATION.resolve("last_incr_date.txt");
                var today = LocalDate.now();

                var refDate = Files.exists(datePath)
                    ? LocalDate.parse(Files.readString(datePath).strip(), DateTimeFormatter.BASIC_ISO_DATE)
                    : today.minusDays(1);

                articles.addAll(processIncrementalDumps(refDate, today));
                Files.writeString(datePath, today.format(DateTimeFormatter.BASIC_ISO_DATE));
            }

            if (articles.isEmpty()) {
                System.out.println("No articles found.");
                return;
            }

            System.out.printf("Got %d unfiltered articles.%n", articles.size());
            Files.write(LOCATION.resolve("latest-unfiltered.txt"), articles);

            articles.removeAll(retrieveTemplateTransclusions());
            articles.removeAll(retrieveDisambiguations());
            articles.retainAll(retrieveNonRedirects());
            articles.removeIf(title -> wb.namespace(title) != Wiki.MAIN_NAMESPACE);

            System.out.printf("Got %d filtered articles.%n", articles.size());
            Files.write(LOCATION.resolve("latest-filtered.txt"), articles);
        }

        if (!articles.isEmpty() && (cli.hasOption("edit") || cli.hasOption("file"))) {
            Login.login(wb);

            var warnings = new ArrayList<String>();
            var errors = new ArrayList<String>();

            for (var page : wb.getContentOfPages(articles)) {
                try {
                    var optText = prepareText(page.getText());

                    if (optText.isPresent()) {
                        wb.edit(page.getTitle(), optText.get(), "wstawienie {{Kontrola autorytatywna}}", page.getTimestamp());
                    }
                } catch (UnsupportedOperationException e) {
                    warnings.add(page.getTitle());
                    System.out.printf("Parse exception in %s: %s%n", page.getTitle(), e.getMessage());
                } catch (Throwable t) {
                    errors.add(page.getTitle());
                    t.printStackTrace();

                    if (t instanceof AccountLockedException | t instanceof AssertionError) {
                        break;
                    }
                }
            }

            if (!warnings.isEmpty()) {
                System.out.printf("%d warnings: %s%n", warnings.size(), warnings);
            }

            updateWarningsList(warnings);

            if (!errors.isEmpty()) {
                System.out.printf("%d errors: %s%n", errors.size(), errors);
                throw new RuntimeException("Errors: " + errors.size());
            }
        }
    }

    private static CommandLine readOptions(String[] args) throws ParseException {
        var options = new Options();

        options.addOption("i", "incr", false, "inspect latest daily incr dump");
        options.addOption("d", "dump", false, "inspect biweekly public dump, if available");
        options.addOption("e", "edit", false, "edit articles on wiki");
        options.addOption("f", "file", true, "process list of articles saved on disk");

        if (args.length == 0) {
            System.out.print("Option(s): ");
            String input = Misc.readLine();
            args = input.split(" ");
        }

        try {
            return new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            new HelpFormatter().printHelp(AuthorityControl.class.getName(), options);
            throw e;
        }
    }

    private static List<String> processIncrementalDumps(final LocalDate startDate, final LocalDate endDate) {
        var titles = new ArrayList<String>();
        var date = startDate;

        var stubsConfig = new XMLDumpConfig("wikidatawiki").type(XMLDumpTypes.STUBS_META_HISTORY_INCR).local();
        var pagesConfig = new XMLDumpConfig("wikidatawiki").type(XMLDumpTypes.PAGES_META_HISTORY_INCR).local();

        while (date.isBefore(endDate)) {
            var optStubsDump = stubsConfig.at(date).fetch();
            var optPagesDump = pagesConfig.at(date).fetch();

            if (optStubsDump.isPresent() && optPagesDump.isPresent()) {
                var revids = retrieveRevids(optStubsDump.get());
                titles.addAll(processDumpFile(optPagesDump.get(), rev -> revids.contains(rev.getRevid())));
            }

            date = date.plusDays(1);
        }

        return titles;
    }

    private static Set<Long> retrieveRevids(XMLDump dump) {
        final var patt = Pattern.compile("^Q\\d+$");
        final Map<Long, Long> newestRevids;

        try (var stream = dump.stream()) {
            newestRevids = stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .filter(rev -> patt.matcher(rev.getTitle()).matches())
                .collect(Collectors.toMap(
                    XMLRevision::getPageid,
                    XMLRevision::getRevid,
                    (a, b) -> b
                ));
        }

        var revids = new HashSet<>(newestRevids.values());
        System.out.printf("Retrieved %d incr dump revisions.%n", revids.size());
        return revids;
    }

    private static List<String> processDumpFile(XMLDump dump) {
        return processDumpFile(dump, rev -> true);
    }

    private static List<String> processDumpFile(XMLDump dump, Predicate<XMLRevision> pred) {
        final var qPatt = Pattern.compile("^Q\\d+$");

        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .filter(rev -> qPatt.matcher(rev.getTitle()).matches())
                .filter(pred)
                .filter(rev -> !rev.isRevisionDeleted())
                .map(rev -> new JSONObject(rev.getText()))
                // https://doc.wikimedia.org/Wikibase/master/php/md_docs_topics_json.html
                .filter(json -> json.optString("type").equals("item"))
                .filter(json -> Optional.ofNullable(json.optJSONObject("sitelinks")).filter(sl -> sl.has("plwiki")).isPresent())
                .filter(json -> Optional.ofNullable(json.optJSONObject("claims")).filter(AuthorityControl::testClaims).isPresent())
                .map(json -> json.getJSONObject("sitelinks").getJSONObject("plwiki").getString("title"))
                .distinct() // hist-incr dumps may list several revisions per page
                .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static boolean testClaims(JSONObject json) {
        var count = PROPERTIES.stream().filter(json::has).count();

        if (count >= 3) {
            return true; // criterion 1: has at least three identifiers
        } else if (count == 0) {
            return false; // fail-fast
        } else if (PROPERTIES_ENC.stream().anyMatch(json::has)) {
            return true; // criterion 4: has at least one identifier from the list of encyclopedic identifiers
        } else if (!json.has("P31")) {
            return false; // fail-fast
        }

        var isHuman = StreamSupport.stream(json.getJSONArray("P31").spliterator(), false)
            .map(obj -> ((JSONObject)obj).getJSONObject("mainsnak"))
            .filter(mainsnak -> mainsnak.getString("snaktype").equals("value"))
            .map(mainsnak -> mainsnak.getJSONObject("datavalue"))
            .filter(snakvalue -> snakvalue.getString("type").equals("wikibase-entityid"))
            .map(snakvalue -> snakvalue.getJSONObject("value"))
            .anyMatch(value -> value.getString("id").equals("Q5"));

        return isHuman && (
            // criterion 2: denotes a human and has at least two identifiers
            count >= 2 ||
            // criterion 3: denotes a human and has a VIAF (P214) or NUKAT (P1207) identifier
            json.has("P214") || json.has("P1207")
        );
    }

    private static Map<Long, String> retrievePropertyBacklinks() {
        var wiki = Wiki.newSession("pl.wikipedia.org");
        var backlinks = new HashMap<Long, String>(600000);

        try (var connection = DriverManager.getConnection(SQL_WDWIKI_URI, SQL_PROPS)) {
            var properties = PROPERTIES.stream()
                .map(property -> String.format("'%s'", property))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    DISTINCT(page_id),
                    ips_site_page
                FROM page
                    INNER JOIN pagelinks ON pl_from = page_id
                    INNER JOIN wb_items_per_site ON page_namespace = 0 AND CONCAT('Q', ips_item_id) = page_title
                WHERE
                    pl_from_namespace = 0 AND
                    ips_site_id = 'plwiki' AND
                    pl_namespace = 120 AND
                    pl_title in (%s);
                """.formatted(properties);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var id = rs.getLong("page_id");
                var sitePage = rs.getString("ips_site_page").replace('_', ' ');

                if (wiki.namespace(sitePage) == Wiki.MAIN_NAMESPACE) {
                    backlinks.put(id, sitePage);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        System.out.printf("Got %d property backlinks with plwiki usage.%n", backlinks.size());
        return backlinks;
    }

    private static List<String> processBiweeklyDump(XMLDump dump) throws IOException {
        var backlinks = retrievePropertyBacklinks();
        backlinks.values().removeAll(retrieveTemplateTransclusions());
        backlinks.values().removeAll(retrieveDisambiguations());
        backlinks.values().retainAll(retrieveNonRedirects());

        System.out.printf("Got %d backlink candidates with no transclusions on plwiki.%n", backlinks.size());

        var results = processDumpFile(dump.filterIds(backlinks.keySet()));
        System.out.printf("Got %d filtered articles from biweekly dump.%n", results.size());
        Files.write(LOCATION.resolve("articlesdump.txt"), results);

        return results;
    }

    private static Set<String> retrieveTemplateTransclusions() {
        var transclusions = new HashSet<String>(500000);

        try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, SQL_PROPS)) {
            var templates = TEMPLATES.stream()
                .map(template -> String.format("'%s'", template.replace(' ', '_')))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title
                FROM page
                    INNER JOIN templatelinks ON tl_from = page_id
                    INNER JOIN linktarget ON lt_id = tl_target_id
                WHERE
                    tl_from_namespace = 0 AND
                    lt_namespace = 10 AND
                    lt_title in (%s);
                """.formatted(templates);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title").replace('_', ' ');
                transclusions.add(title);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        System.out.printf("Got %d template transclusions on plwiki.%n", transclusions.size());
        return transclusions;
    }

    private static Set<String> retrieveDisambiguations() {
        var disambigs = new HashSet<String>(100000);

        try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, SQL_PROPS)) {
            var query = """
                SELECT
                    page_title
                FROM page
                    INNER JOIN page_props on pp_page = page_id
                WHERE
                    page_namespace = 0 AND
                    pp_propname = "disambiguation";
                """;

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title").replace('_', ' ');
                disambigs.add(title);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        System.out.printf("Got %d disambiguations on plwiki.%n", disambigs.size());
        return disambigs;
    }

    private static Set<String> retrieveNonRedirects() {
        var articles = new HashSet<String>(2000000);

        try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, SQL_PROPS)) {
            var query = """
                SELECT page_title
                FROM page
                WHERE page_namespace = 0 AND page_is_redirect = 0;
                """;

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title").replace('_', ' ');
                articles.add(title);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        System.out.printf("Got %d non-redirect articles on plwiki.%n", articles.size());
        return articles;
    }

    private static Optional<String> prepareText(String text) {
        if (TEMPLATES.stream().anyMatch(template -> !ParseUtils.getTemplatesIgnoreCase(template, text).isEmpty())) {
            return Optional.empty();
        }

        var m = P_TEXT.matcher(text);

        if (!m.find()) {
            throw new UnsupportedOperationException("no match found");
        }

        var sb = new StringBuilder(text.length());
        var body = text.substring(0, m.start()).stripTrailing();
        var footer = text.substring(m.start()).stripLeading();

        if (StringUtils.containsAnyIgnoreCase(body, "#PATRZ", "#PRZEKIERUJ", "#TAM", "#REDIRECT")) {
            return Optional.empty(); // ignore redirs, there's nothing we can do
        }

        if (body.matches("(?s).*?(?:SORTUJ|DOMYŚLNIESORTUJ|DEFAULTSORT|DEFAULTSORTKEY|DEFAULTCATEGORYSORT).*")) {
            throw new UnsupportedOperationException("sort magic word found in article body");
        }

        if (body.matches("(?s).*?\\[{2} *+(?i:Kategoria|Category) *+:[^\\]\\{\\}\n]*+\\]{2}.*")) {
            throw new UnsupportedOperationException("category found in article body");
        }

        if (footer.matches("(?s).*?\\[{2} *+(?i:Plik|File|Image) *+:.+")) {
            throw new UnsupportedOperationException("file found in article footer");
        }

        var pre = body.endsWith("-->") ? "\n" : "\n\n";
        sb.append(body).append(pre).append("{{Kontrola autorytatywna}}").append("\n\n").append(footer);
        return Optional.of(sb.toString().stripTrailing());
    }

    private static void updateWarningsList(List<String> titles) throws IOException {
        var log = LOCATION.resolve("warnings.txt");
        var set = new TreeSet<>(titles);

        if (Files.exists(log)) {
            set.addAll(Files.readAllLines(log));
        }

        set.removeAll(retrieveTemplateTransclusions());
        Files.write(log, set);
    }
}
