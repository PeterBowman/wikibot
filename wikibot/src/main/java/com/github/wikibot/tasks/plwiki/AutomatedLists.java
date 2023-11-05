package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.wikipedia.Wiki;

import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;

public final class AutomatedLists {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/AutomatedLists/");
    private static final String BOT_PAGE = "Wikipedysta:PBbot/listy artykułów";
    private static final Wiki wiki = Wiki.newSession("pl.wikipedia.org");
    private static final Locale PL_LOCALE = Locale.forLanguageTag("pl-PL");
    private static final Collator PL_COLLATOR = Collator.getInstance(PL_LOCALE);
    private static final XStream xstream = new XStream();
    private static final TemporalAmount UPDATE_FREQUENCY = Period.ofMonths(4);
    private static final Map<String, Long> CREATION_DATES = new HashMap<>();

    private static final String SQL_PLWIKI_URI = "jdbc:mysql://plwiki.analytics.db.svc.wikimedia.cloud:3306/plwiki_p";
    // private static final String SQL_PLWIKI_URI = "jdbc:mysql://localhost:4715/plwiki_p";

    private static final List<QueryItem> QUERY_CONFIG;

    static {
        try {
            var json = wiki.getPageText(List.of(BOT_PAGE + "/konfiguracja.json")).get(0);
            QUERY_CONFIG = parseQueryConfig(new JSONArray(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        QUERY_CONFIG.forEach(System.out::println);
        Login.login(wiki);

        var stampsPath = LOCATION.resolve("timestamps.xml");
        var hashesPath = LOCATION.resolve("hash.xml");

        var timestamps = new HashMap<>(getTimestamps(stampsPath));
        var hashes = new HashMap<>(getHashes(hashesPath));
        var sizes = new HashMap<String, Integer>();

        var infos = wiki.getPageInfo(QUERY_CONFIG.stream().map(QueryItem::target).toList());
        var today = LocalDate.now();
        var refDate = today.minus(UPDATE_FREQUENCY);
        var exceptions = new ArrayList<String>();
        var anyChanges = false;

        for (var i = 0; i < QUERY_CONFIG.size(); i++) {
            var item = QUERY_CONFIG.get(i);
            var exists = Optional.ofNullable(infos.get(i)).map(info -> (Boolean)info.get("exists")).orElse(false);

            if (!exists || timestamps.get(item.target()).isBefore(refDate) || hashes.get(item.target()) != item.hashCode()) {
                var titles = processItem(item);

                queryCreationDate(titles);

                if (titles.retainAll(CREATION_DATES.keySet())) {
                    System.out.printf("Got %d items after removing those without creation date.%n", titles.size());
                }

                var text = makeList(item, titles);
                var intro = makeIntro(item);

                try {
                    wiki.edit(item.target(), intro + "\n\n" + text, "aktualizacja");
                    anyChanges = true;
                    timestamps.put(item.target(), today);
                    hashes.put(item.target(), item.hashCode());
                    sizes.put(item.target(), titles.size());
                } catch (Throwable t) {
                    t.printStackTrace();
                    exceptions.add(t.getMessage());
                }
            } else {
                System.out.printf("Skipping %s.%n", item.target());
            }
        }

        if (anyChanges) {
            Files.writeString(stampsPath, xstream.toXML(timestamps));
            Files.writeString(hashesPath, xstream.toXML(hashes));

            var text = wiki.getPageText(List.of(BOT_PAGE)).get(0);
            text = makeMainPage(text, QUERY_CONFIG, timestamps, sizes);

            try {
                wiki.edit(BOT_PAGE, text, "aktualizacja");
            } catch (Throwable t) {
                t.printStackTrace();
                exceptions.add(t.getMessage());
            }
        }

        if (!exceptions.isEmpty()) {
            exceptions.forEach(System.err::println);
            throw new RuntimeException("Some errors occurred.");
        }
    }

    private static List<QueryItem> parseQueryConfig(JSONArray arr) {
        var items = new ArrayList<QueryItem>();

        for (var entry : arr) {
            var obj = (JSONObject)entry;
            var tree = obj.getString("tree");

            var intersectsWith = new ArrayList<String>();
            var exclusions = new ArrayList<String>();

            if (obj.has("intersects_with")) {
                for (var intersection : obj.getJSONArray("intersects_with")) {
                    intersectsWith.add((String)intersection);
                }
            }

            if (obj.has("exclusions")) {
                for (var exclusion : obj.getJSONArray("exclusions")) {
                    exclusions.add((String)exclusion);
                }
            }

            var target = obj.getString("target");
            var groupBy = GroupType.NONE;
            var sortBy = SortType.NATURAL_ORDER;

            if (obj.has("group_by")) {
                groupBy = switch (obj.getString("group_by")) {
                    case "year" -> GroupType.YEAR;
                    default -> throw new IllegalArgumentException("Unknown group_by value: " + obj.getString("group_by"));
                };
            }

            if (obj.has("sort_by")) {
                sortBy = switch (obj.getString("sort_by")) {
                    case "creation" -> SortType.CREATION_DATE;
                    case "natural" -> SortType.NATURAL_ORDER;
                    default -> throw new IllegalArgumentException("Unknown sort_by value: " + obj.getString("sort_by"));
                };
            }

            items.add(new QueryItem(tree, Collections.unmodifiableList(intersectsWith), Collections.unmodifiableList(exclusions), target, groupBy, sortBy));
        }

        return Collections.unmodifiableList(items);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, LocalDate> getTimestamps(Path path) {
        try {
            return (Map<String, LocalDate>) xstream.fromXML(Files.readString(path));
        } catch (IOException | ClassCastException e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> getHashes(Path path) {
        try {
            return (Map<String, Integer>) xstream.fromXML(Files.readString(path));
        } catch (IOException | ClassCastException e) {
            return Collections.emptyMap();
        }
    }

    private static List<String> processItem(QueryItem item) throws SQLException, IOException {
        var titles = DBUtils.getRecursiveCategoryMembers(SQL_PLWIKI_URI, item.tree(), Wiki.MAIN_NAMESPACE);
        var hasFiltered = false;

        for (var intersection : item.intersections()) {
            var intersectionTitles = DBUtils.getRecursiveCategoryMembers(SQL_PLWIKI_URI, intersection, Wiki.MAIN_NAMESPACE);
            titles.retainAll(intersectionTitles);
            hasFiltered = true;
        }

        for (var exclusion : item.exclusions()) {
            var exclusionTitles = DBUtils.getRecursiveCategoryMembers(SQL_PLWIKI_URI, exclusion, Wiki.MAIN_NAMESPACE);
            titles.removeAll(exclusionTitles);
            hasFiltered = true;
        }

        if (hasFiltered) {
            System.out.printf("Got %d items after filtering steps.%n", titles.size());
        }

        return new ArrayList<>(titles);
    }

    private static void queryCreationDate(Collection<String> titles) throws SQLException, IOException {
        var targets = titles.stream()
            .filter(title -> !CREATION_DATES.containsKey(title))
            .toList();

        if (targets.isEmpty()) {
            return;
        }

        var targetsStr = targets.stream()
            .map(title -> String.format("'%s'", title.replace(' ', '_').replace("'", "\\'")))
            .collect(Collectors.joining(","));

        try (var connection = DriverManager.getConnection(SQL_PLWIKI_URI, DBUtils.prepareSQLProperties())) {
            var query = """
                SELECT
                    page_title,
                    rev_timestamp
                FROM page
                    INNER JOIN revision ON rev_page = page_id
                WHERE
                    rev_parent_id = 0 AND
                    page_namespace = 0 AND
                    page_title IN (%s)
                """.formatted(targetsStr);

            var rs = connection.createStatement().executeQuery(query);

            while (rs.next()) {
                var title = rs.getString("page_title").replace('_', ' ');
                var timestamp = rs.getLong("rev_timestamp");

                CREATION_DATES.put(title, timestamp);
            }
        }
    }

    private static String makeList(QueryItem item, List<String> titles) {
        if (item.groupBy() == GroupType.YEAR || item.sortBy() == SortType.CREATION_DATE) {
            Comparator<? super String> comparator = switch (item.sortBy()) {
                case NATURAL_ORDER -> PL_COLLATOR;
                case CREATION_DATE -> (a, b) -> Long.compare(CREATION_DATES.get(a), CREATION_DATES.get(b));
                default -> throw new AssertionError();
            };

            if (item.groupBy() == GroupType.YEAR) {
                return titles.stream()
                    .sorted(comparator)
                    .collect(Collectors.groupingBy(
                        title -> extractYear(CREATION_DATES.get(title)),
                        TreeMap::new,
                        Collectors.toCollection(() -> new TreeSet<>(comparator))
                    ))
                    .entrySet().stream()
                    .map(e -> String.format("== Utworzone w %d ==\nŁącznie: %d.\n\n%s", e.getKey(), e.getValue().size(), e.getValue().stream()
                        .map(title -> "[[%s]]".formatted(title))
                        .collect(Collectors.joining(",\n"))
                    ))
                    .collect(Collectors.joining("\n\n"));
            } else {
                return titles.stream()
                    .sorted(comparator)
                    .map(title -> "[[%s]]".formatted(title))
                    .collect(Collectors.joining(",\n"));
            }
        } else {
            return titles.stream()
                .sorted(PL_COLLATOR)
                .map(title -> "[[%s]]".formatted(title))
                .collect(Collectors.joining(",\n"));
        }
    }

    private static int extractYear(long timestamp) {
        return (int) (timestamp / 10000000000L);
    }

    private static String makeIntro(QueryItem item) {
        var sb = new StringBuilder();

        sb.append("Artykuły z drzewa [[:Kategoria:%s]]".formatted(item.tree()));

        if (!item.intersections().isEmpty()) {
            sb.append(" krzyżujące się z ");

            sb.append(item.intersections().stream()
                .map(title -> "[[:Kategoria:%s]]".formatted(title))
                .collect(Collectors.collectingAndThen(Collectors.toList(), joiningLastDelimiter(", ", " oraz ")))
            );
        }

        if (!item.exclusions().isEmpty()) {
            sb.append(" (z wyłączeniem ");

            sb.append(item.exclusions().stream()
                .map(title -> "[[:Kategoria:%s]]".formatted(title))
                .collect(Collectors.collectingAndThen(Collectors.toList(), joiningLastDelimiter(", ", " oraz ")))
            ).append(")");
        }

        sb.append(" posortowane według ");

        switch (item.sortBy()) {
            case NATURAL_ORDER -> sb.append("kolejności alfabetycznej");
            case CREATION_DATE -> sb.append("daty utworzenia");
            default -> throw new AssertionError();
        }

        if (item.groupBy() == GroupType.YEAR) {
            sb.append(" z podziałem na rok pierwszej edycji");
        }

        sb.append(".\n\nOstatnia aktualizacja: ~~~~~.");

        if (item.groupBy() == GroupType.YEAR) {
            sb.append("\n__TOC__");
        }

        return sb.toString();
    }

    private static Function<List<String>, String> joiningLastDelimiter(String delimiter, String lastDelimiter) {
        // https://stackoverflow.com/a/34936891
        return list -> {
            int last = list.size() - 1;

            if (last < 1) {
                return String.join(delimiter, list);
            }

            return String.join(lastDelimiter,
                String.join(delimiter, list.subList(0, last)),
                list.get(last));
        };
    }

    private static String makeMainPage(String text, List<QueryItem> items, Map<String, LocalDate> timestamps, Map<String, Integer> sizes) {
        final var label = "<!-- START -->";

        if (text.contains(label)) {
            var formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(PL_LOCALE);

            return text.substring(0, text.indexOf(label) + label.length()) + "\n" + items.stream()
                .map(i -> String.format("* [[%s]] (%s, {{formatnum:%d}})", i.target(), timestamps.get(i.target()).format(formatter), sizes.get(i.target())))
                .collect(Collectors.joining("\n"));
        } else {
            return text;
        }
    }

    private enum GroupType { NONE, YEAR }

    private enum SortType { NATURAL_ORDER, CREATION_DATE }

    private record QueryItem(String tree, List<String> intersections, List<String> exclusions, String target, GroupType groupBy, SortType sortBy) {}
}
