package com.github.wikibot.scripts.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.CredentialExpiredException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

public final class ResolveLinks {
    private static final Path LOCATION = Paths.get("./data/scripts.plwiki/ResolveLinks/");
    private static final Path PATH_SOURCES = LOCATION.resolve("sources.txt");
    private static final Path PATH_TARGETS = LOCATION.resolve("targets.txt");
    private static final Path PATH_BACKLINKS = LOCATION.resolve("backlinks.txt");
    private static final Path PATH_EDITED = LOCATION.resolve("edited.txt");
    private static final Path PATH_ERRORS = LOCATION.resolve("errors.txt");

    private static final String SQL_PLWIKI_URI_SERVER = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    private static final String SQL_PLWIKI_URI_LOCAL = "jdbc:mysql://localhost:4715/plwiki_p";

    // from Linker::formatLinksInComment in Linker.php (now CommentParser::doWikiLinks in CommentParser.php)
    private static final Pattern PATT_LINK = Pattern.compile("\\[{2} *?:?(?!(?i:Plik|File):)([^\\[\\]\\|#]+)(#[^\\|\\]]*?)?(?:\\|((?:]?[^\\]])*+))?\\]{2}([a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)?");

    private static final List<String> SOFT_REDIR_TEMPLATES = List.of(
        "Osobny artykuł", "Osobna strona", "Główny artykuł", "Main", "Mainsec", "Zobacz też", "Seealso"
    );

    private static final List<String> IGNORED_REDIR_TEMPLATES = List.of(
        "Inne znaczenia", "DisambigR"
    );

    private static final Set<Integer> TARGET_NAMESPACES = Set.of(
        Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.PROJECT_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, 100 /* Portal */
    );

    private static final List<String> PROJECT_WHITELIST = List.of(
        "Wikipedia:Skarbnica Wikipedii", "Wikipedia:Indeks biografii"
    );

    private static final int USER_SUBPAGE_SIZE_LIMIT = 500000;

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    public static void main(String[] args) throws Exception {
        var line = parseArguments(args);

        Login.login(wb);

        var sourceToTarget = new HashMap<String, String>();
        var summary = prepareSourceMappings(line, sourceToTarget);

        sourceToTarget.keySet().removeIf(title -> wb.namespace(title) != Wiki.MAIN_NAMESPACE);

        System.out.printf("Got %d items to work on%n", sourceToTarget.size());

        if (sourceToTarget.isEmpty()) {
            return;
        }

        var backlinkToSources = getBacklinkMap(sourceToTarget, line.hasOption("namespaces"));

        System.out.printf("%d unique backlinks found.%n", backlinkToSources.size());

        if (backlinkToSources.isEmpty()) {
            return;
        }

        var infos = getBacklinkInfo(backlinkToSources.keySet());
        var isDisambMode = line.getOptionValue("mode").equals("disamb");

        backlinkToSources.keySet().retainAll(infos.keySet());

        backlinkToSources.keySet().removeIf(backlink ->
            infos.get(backlink).isRedirect() ||
            (isDisambMode && infos.get(backlink).isDisambiguation()) ||
            (wb.namespace(backlink) == Wiki.USER_NAMESPACE && infos.get(backlink).length() > USER_SUBPAGE_SIZE_LIMIT)
        );

        System.out.printf("%d backlinks left after page info filter step.%n", backlinkToSources.size());

        if (backlinkToSources.isEmpty()) {
            return;
        }

        Files.write(PATH_BACKLINKS, backlinkToSources.keySet());

        if (line.hasOption("backlinks")) {
            return;
        }

        var isRedirMode = line.getOptionValue("mode").equals("redirsource") || line.getOptionValue("mode").equals("redirtarget");
        var isDryRunMode = line.hasOption("dry");
        var replaceText = line.hasOption("replaceText");

        var edited = Collections.synchronizedList(new ArrayList<String>());
        var errors = new ArrayList<String>();

        var keepGoing = new MutableBoolean(true);

        var stream = wb.getContentOfPages(backlinkToSources.keySet()).stream();

        if (isDryRunMode) {
            stream = stream.parallel();
        }

        wb.setMarkMinor(true);

        stream.takeWhile(t -> keepGoing.booleanValue()).forEach(page -> {
            // Wikipedystka: -> Wikipedysta: (in order to match SQL results)
            var title = wb.normalize(page.title());
            var newText = prepareText(page.text(), backlinkToSources.get(title), sourceToTarget, isRedirMode, replaceText);

            if (!newText.equals(page.text())) {
                try {
                    if (!isDryRunMode) {
                        wb.edit(page.title(), newText, summary, page.timestamp());
                    }

                    edited.add(title);
                } catch (AssertionError | CredentialExpiredException | AccountLockedException e) {
                    System.out.println(e.getMessage());
                    keepGoing.setFalse();
                } catch (Throwable t) {
                    System.out.printf("Error in %s: %s%n", title, t.getMessage());
                    errors.add(title);
                }
            }
        });

        System.out.println("Edited: " + edited.size());
        System.out.println("Errors: " + errors.size());

        Files.write(PATH_EDITED, edited);
        Files.write(PATH_ERRORS, errors);

        if (!errors.isEmpty() && errors.size() < 25) {
            errors.forEach(System.out::println);
        }
    }

    private static String prepareSourceMappings(CommandLine line, Map<String, String> sourceToTarget) throws IOException, ClassNotFoundException, SQLException {
        var mode = line.getOptionValue("mode");
        var useFile = line.hasOption("useFile");

        if (mode.equals("redirsource")) {
            if (useFile) {
                var sources = Files.readAllLines(PATH_SOURCES).stream().distinct().toList();

                getRedirectTargets(sources, sourceToTarget);
                return String.format("podmiana przekierowań");
            } else {
                var source = line.getOptionValue("source");
                Objects.requireNonNull(source, "missing source");
                var target = wb.resolveRedirects(List.of(source)).get(0);

                if (target.equals(source)) {
                    throw new IllegalArgumentException("Source page is not a redirect");
                }

                sourceToTarget.put(source, target);
                return String.format("zamiana linków z „[[%s]]” na „[[%s]]”", source, target);
            }
        } else if (mode.equals("redirtarget")) {
            if (useFile) {
                var targets = Files.readAllLines(PATH_TARGETS).stream().distinct().toList();

                getRedirectSources(targets, sourceToTarget);
                return String.format("podmiana przekierowań");
            } else {
                var target = line.getOptionValue("target");
                Objects.requireNonNull(target, "missing target");
                var sources = wb.whatLinksHere(List.of(target), true, false, Wiki.MAIN_NAMESPACE).get(0);

                sources.stream().forEach(source -> sourceToTarget.put(source, target));
                return String.format("podmiana przekierowań do „[[%s]]”", target);
            }
        } else if (mode.equals("disamb")) {
            if (useFile) {
                var sources = Files.readAllLines(PATH_SOURCES).stream().distinct().toList();
                var targets = Files.readAllLines(PATH_TARGETS).stream().distinct().toList();

                if (sources.size() != targets.size()) {
                    throw new IllegalArgumentException("Sources and targets have different sizes after uniq step");
                }

                IntStream.range(0, sources.size()).forEach(i -> sourceToTarget.put(sources.get(i), targets.get(i)));
                return String.format("zamiana linków do ujednoznacznień");
            } else {
                var source = line.getOptionValue("source");
                var target = line.getOptionValue("target");

                Objects.requireNonNull(source, "missing source");
                Objects.requireNonNull(target, "missing target");

                sourceToTarget.put(source, target);
                return String.format("zamiana linków z „[[%s]]” na „[[%s]]”", source, target);
            }
        } else {
            throw new IllegalArgumentException("Illegal mode: " + mode);
        }
    }

    private static CommandLine parseArguments(String[] args) throws ParseException {
        var options = new Options();
        options.addRequiredOption("m", "mode", true, "script mode (redirsource, redirtarget, disamb)");
        options.addOption("s", "source", true, "source page");
        options.addOption("t", "target", true, "target page");
        options.addOption("f", "useFile", false, "retrieve sources/targets from file (only main namespace!)");
        options.addOption("d", "dry", false, "dry run");
        options.addOption("b", "backlinks", false, "retrieve backlinks and exit");
        options.addOption("n", "namespaces", false, "ignore all namespace restrictions");
        options.addOption("r", "replaceText", false, "replace link text, if applicable");

        if (args.length == 0) {
            System.out.print("Options: ");
            args = Misc.readArgs();
        }

        var line = new DefaultParser().parse(options, args);

        if (line.hasOption("useFile") && (line.hasOption("source") || line.hasOption("target"))) {
            throw new IllegalArgumentException("Incompatible useFile option and source/target");
        }

        if (line.hasOption("backlinks") && line.hasOption("dry")) {
            throw new IllegalArgumentException("Incompatible backlinks option and dry run");
        }

        return line;
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

    private static void getRedirectSources(List<String> targets, Map<String, String> sourceToTarget)
            throws ClassNotFoundException, SQLException, IOException {
        try (var connection = getConnection()) {
            var targetsString = targets.stream()
                .map(target -> String.format("'%s'", target.replace(' ', '_').replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title, pl_title
                FROM page
                    INNER JOIN pagelinks ON pl_from = page_id
                WHERE
                    page_namespace = 0 AND
                    page_is_redirect = 1 AND
                    pl_namespace = 0 AND
                    pl_title IN (%s);
                """.formatted(targetsString);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var source = rs.getString("page_title");
                var target = rs.getString("pl_title");

                sourceToTarget.put(wb.normalize(source), wb.normalize(target));
            }
        }
    }

    private static void getRedirectTargets(List<String> sources, Map<String, String> sourceToTarget)
            throws ClassNotFoundException, SQLException, IOException {
        try (var connection = getConnection()) {
            var sourcesString = sources.stream()
                .map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title, pl_title
                FROM page
                    INNER JOIN pagelinks ON pl_from = page_id
                WHERE
                    page_namespace = 0 AND
                    page_is_redirect = 1 AND
                    page_title IN (%s) AND
                    pl_namespace = 0;
                """.formatted(sourcesString);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var source = rs.getString("page_title");
                var target = rs.getString("pl_title");

                sourceToTarget.put(wb.normalize(source), wb.normalize(target));
            }
        }
    }

    private static Map<String, List<String>> getBacklinkMap(Map<String, String> sourceToTarget, boolean allNamespaces) throws IOException {
        var comparator = Comparator.comparingInt(wb::namespace).thenComparing(Comparator.naturalOrder());
        var backlinkToSources = new TreeMap<String, List<String>>(comparator);
        var sources = sourceToTarget.keySet().stream().toList();

        try (var connection = getConnection()) {
            var sourcesString = sources.stream()
                .map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title, page_namespace, pl_title
                FROM page
                    INNER JOIN pagelinks ON pl_from = page_id
                WHERE
                    page_is_redirect = 0 AND
                    pl_namespace = 0 AND
                    pl_title IN (%s);
                """.formatted(sourcesString);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title");
                var ns = rs.getInt("page_namespace");
                var backlink = wb.normalize(String.format("%s:%s", wb.namespaceIdentifier(ns), title));
                var source = wb.normalize(rs.getString("pl_title"));

                backlinkToSources.computeIfAbsent(backlink, k -> new ArrayList<>()).add(source);
            }
        } catch (ClassNotFoundException | SQLException e) {
            var backlinksPerSource = wb.whatLinksHere(sources, false, false);

            for (var i = 0; i < backlinksPerSource.size(); i++) {
                var source = sources.get(i);
                var backlinks = backlinksPerSource.get(i);

                for (var backlink : backlinks) {
                    backlinkToSources.computeIfAbsent(wb.normalize(backlink), k -> new ArrayList<>()).add(source);
                }
            }
        }

        if (!allNamespaces) {
            var bots = wb.allUsersInGroup("bot");

            backlinkToSources.keySet().removeIf(t -> !TARGET_NAMESPACES.contains(wb.namespace(t)));

            // retain user sandboxes (no bots)
            backlinkToSources.keySet().removeIf(t -> wb.namespace(t) == Wiki.USER_NAMESPACE && (
                wb.getRootPage(t).equals(t) || bots.contains(wb.removeNamespace(wb.getRootPage(t)))
            ));

            // retain biography notes
            backlinkToSources.keySet().removeIf(t -> wb.namespace(t) == Wiki.PROJECT_NAMESPACE && !PROJECT_WHITELIST.contains(wb.getRootPage(t)));
        }

        return backlinkToSources;
    }

    private static Map<String, PageInfo> getBacklinkInfo(Set<String> titles) throws IOException {
        var map = new HashMap<String, PageInfo>(titles.size());
        var titleList = new ArrayList<>(titles);

        try (var connection = getConnection()) {
            var titlesString = titleList.stream()
                .map(wb::removeNamespace)
                .map(source -> String.format("'%s'", source.replace(' ', '_').replace("'", "\\'")))
                .distinct()
                .collect(Collectors.joining(","));

            var query = """
                SELECT
                    page_title, page_namespace, page_len, page_is_redirect, pp_propname
                FROM page
                    LEFT JOIN page_props ON
                        pp_page = page_id AND
                        pp_propname = "disambiguation"
                WHERE
                    page_title IN (%s);
                """.formatted(titlesString);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title");
                var ns = rs.getInt("page_namespace");
                var pagename = wb.normalize(String.format("%s:%s", wb.namespaceIdentifier(ns), title));

                if (titles.contains(pagename)) {
                    var length = rs.getInt("page_len");
                    var isRedirect = rs.getBoolean("page_is_redirect");
                    var isDisambiguation = "disambiguation".equals(rs.getString("pp_propname"));

                    map.put(pagename, new PageInfo(isRedirect, isDisambiguation, length));
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            var props = wb.getPageProperties(titleList);
            var infos = wb.getPageInfo(titleList);

            for (var i = 0; i < titleList.size(); i++) {
                if (props.get(i) == null || infos.get(i) == null) {
                    continue;
                }

                var isDisambig = props.get(i).containsKey("disambiguation");
                var title = (String)infos.get(i).get("pagename");
                var isRedir = (Boolean)infos.get(i).get("redirect");
                var length = (Integer)infos.get(i).get("size");

                map.put(title, new PageInfo(isRedir, isDisambig, length));
            }
        }

        return map;
    }

    private static String prepareText(String text, List<String> sources, Map<String, String> sourceToTarget, boolean isRedirMode, boolean replaceText) {
        text = Utils.replaceWithIgnoredRanges(text, PATT_LINK, getIgnoredRanges(text, isRedirMode), mr -> {
            var link = normalizeTitle(mr.group(1));
            var source = StringUtils.capitalize(link);

            final String replacement;

            if (sources.contains(source)) {
                var target = sourceToTarget.get(source);
                var fragment = Optional.ofNullable(mr.group(2)).orElse("");
                var content = Optional.ofNullable(mr.group(3)).map(s -> s.replaceAll(" {2,}", " ")).orElse(link);
                var trail = Optional.ofNullable(mr.group(4)).orElse("");

                if (replaceText && source.equals(content + trail)) {
                    replacement = String.format("[[%s%s]]", target, fragment);
                } else if (fragment.isEmpty() && target.equals(content + trail)) {
                    replacement = String.format("[[%s]]", target);
                } else if (fragment.isEmpty() && target.equals(content)) {
                    replacement = String.format("[[%s]]%s", target, trail);
                } else {
                    replacement = String.format("[[%s%s|%s]]", target, fragment, content + trail);
                }
            } else {
                replacement = mr.group();
            }

            return Matcher.quoteReplacement(replacement);
        });

        for (var source : sources) {
            var target = sourceToTarget.get(source);
            text = replaceAdditionalOccurrences(text, source, target);
        }

        return text;
    }

    private static String normalizeTitle(String title) {
        return title.replace('_', ' ').replace('\u00A0', ' ').replaceAll(" {2,}", " ").trim();
    }

    private static List<Range<Integer>> getIgnoredRanges(String text, boolean ignoreRedirTemplates) {
        var ignoredRanges = new ArrayList<List<Range<Integer>>>();

        if (!ignoreRedirTemplates) {
            IGNORED_REDIR_TEMPLATES.stream()
                .flatMap(templateName -> ParseUtils.getTemplatesIgnoreCase(templateName, text).stream())
                .distinct()
                .map(template -> Pattern.compile(template, Pattern.LITERAL))
                .map(patt -> Utils.findRanges(text, patt))
                .forEach(ignoredRanges::add);
        }

        ignoredRanges.add(Utils.getStandardIgnoredRanges(text));
        return Utils.getCombinedRanges(ignoredRanges);
    }

    private static String replaceAdditionalOccurrences(String text, String source, String target) {
        var sources = List.of(StringUtils.capitalize(source), StringUtils.uncapitalize(source));

        for (var templateName : SOFT_REDIR_TEMPLATES) {
            for (var template : ParseUtils.getTemplatesIgnoreCase(templateName, text)) {
                var params = ParseUtils.getTemplateParametersWithValue(template);
                var hash = params.hashCode();

                params.entrySet().stream()
                    .filter(e -> StringUtils.equalsAny(templateName, "Zobacz też", "Seealso")
                        ? e.getKey().equals("ParamWithoutName1")
                        : e.getKey().startsWith("ParamWithoutName"))
                    .filter(e -> sources.contains(normalizeTitle(e.getValue())))
                    .forEach(e -> e.setValue(target));

                if (params.hashCode() != hash) {
                    text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
                }
            }
        }

        for (var template : ParseUtils.getTemplatesIgnoreCase("Link-interwiki", text)) {
            var params = ParseUtils.getTemplateParametersWithValue(template);
            var local = normalizeTitle(params.getOrDefault("pl", params.getOrDefault("ParamWithoutName1", "")));

            if (sources.contains(local)) {
                if (params.containsKey("pl")) {
                    params.put("pl", target);
                } else {
                    params.put("ParamWithoutName1", target);
                }

                if (!params.containsKey("tekst") && !params.containsKey("ParamWithoutName4")) {
                    if (!params.containsKey("ParamWithoutName3")) {
                        params.put("tekst", local);
                    } else {
                        params.put("ParamWithoutName4", local);
                    }
                }

                text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
            }
        }

        for (var template : ParseUtils.getTemplatesIgnoreCase("Sort", text)) {
            var params = ParseUtils.getTemplateParametersWithValue(template);
            var key = normalizeTitle(params.getOrDefault("ParamWithoutName1", ""));

            if (sources.contains(key) && !params.containsKey("ParamWithoutName2")) {
                params.put("ParamWithoutName2", String.format("[[%s|%s]]", target, key));
                text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
            }
        }

        for (var template : ParseUtils.getTemplatesIgnoreCase("Sortname", text)) {
            var params = ParseUtils.getTemplateParametersWithValue(template);

            if (!params.containsKey("nolink")) {
                final String key;

                if (params.containsKey("ParamWithoutName3")) {
                    key = params.get("ParamWithoutName3");
                } else {
                    var name = params.getOrDefault("ParamWithoutName1", "");
                    var surname = params.getOrDefault("ParamWithoutName2", "");
                    key = String.format("%s %s", name, surname);
                }

                if (sources.contains(normalizeTitle(key))) {
                    params.put("ParamWithoutName3", target);
                    text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
                }
            }
        }

        return text;
    }

    private record PageInfo(boolean isRedirect, boolean isDisambiguation, int length) {}
}
