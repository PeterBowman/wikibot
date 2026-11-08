package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

public final class MonitorTalkPages {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MonitorTalkPages/");
    private static final String SQL_URI_SERVER = "jdbc:mysql://plwiktionary.analytics.db.svc.wikimedia.cloud:3306/plwiktionary_p";
    private static final String SQL_URI_LOCAL = "jdbc:mysql://localhost:4713/plwiktionary_p";
    private static final String RESOLVED_TEMPLATE = "załatwione";

    private static final int STALE_DAYS = 90;
    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final OffsetDateTime STALE_THRESHOLD = NOW.minusDays(STALE_DAYS);

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public enum ThreadState {
        RESOLVED("resolved"),
        STALE("stale"),
        ONGOING("ongoing"),
        NEW("new");

        public final String value;

        ThreadState(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        System.out.println("Fetching all talk pages...");

        var talkPageTitles = getAllTalkPages();
        System.out.printf("Got %d talk pages%n", talkPageTitles.size());

        var contentPageTitles = talkPageTitles.stream()
            .map(wb::getContentPage)
            .toList();

        var talkPages = wb.getContentOfPages(talkPageTitles);
        var contentPages = wb.getContentOfPages(contentPageTitles);
        var contentPageMap = contentPages.stream().collect(Collectors.toMap(PageContainer::title, UnaryOperator.identity()));

        var entries = buildEntries(talkPages, contentPageMap);
        var langSections = extractLangSections(contentPages);
        var stats = buildStats(entries);
        storeData(entries, langSections, stats);

        System.out.printf("Stored %d entries%n", entries.size());
        System.out.println("Stats: " + stats);
    }

    private static final List<String> getAllTalkPages() throws SQLException {
        var list = new ArrayList<String>();

        var query = """
            SELECT
                page_title,
                page_namespace
            FROM
                page
            WHERE
                page_namespace % 2 = 1 AND
                page_namespace != 3
            """;

        try (var connection = DBUtils.getConnection(SQL_URI_SERVER, SQL_URI_LOCAL)) {
            var statement = connection.prepareStatement(query);
            var resultSet = statement.executeQuery();

            while (resultSet.next()) {
                var title = resultSet.getString("page_title").replace('_', ' ');
                var ns = resultSet.getInt("page_namespace");
                var pageName = "%s:%s".formatted(wb.namespaceIdentifier(ns), title);
                list.add(pageName);
            }
        }

        return list;
    }

    private static final List<Entry> buildEntries(List<PageContainer> talkPages, Map<String, PageContainer> contentPageMap) {
        var entries = new ArrayList<Entry>();

        for (var talkPage : talkPages) {
            var title = talkPage.title();
            var isOrphaned = !contentPageMap.containsKey(wb.getContentPage(title));
            var page = com.github.wikibot.parsing.Page.wrap(talkPage);
            var sections = page.getAllSections();

            if (!page.getIntro().isBlank()) {
                var entry = makeEntry(title, isOrphaned, null, page.getIntro());
                entries.add(entry);
            }

            for (var section : sections) {
                var entry = makeEntry(title, isOrphaned, section.getHeader(), section.toString());
                entries.add(entry);
            }
        }

        return entries;
    }

    private static final Entry makeEntry(String pagename, boolean orphaned, String thread, String text) {
        var entry = new Entry();
        entry.talkPage = pagename;
        entry.isOrphaned = orphaned;
        entry.threadTitle = thread;

        var timestamps = ArchiveThreads.retrieveTimestamps(text);
        var lastTimestamp = timestamps.isEmpty() ? null : timestamps.last();
        entry.lastTimestamp = lastTimestamp;

        if (isResolved(text)) {
            entry.threadState = ThreadState.RESOLVED.value;
        } else if (timestamps.size() < 2) {
            entry.threadState = ThreadState.NEW.value;
        } else if (lastTimestamp != null && lastTimestamp.isBefore(STALE_THRESHOLD)) {
            entry.threadState = ThreadState.STALE.value;
        } else {
            entry.threadState = ThreadState.ONGOING.value;
        }

        return entry;
    }

    private static final boolean isResolved(String text) {
        var stripped = ArchiveThreads.stripMarkers(text);
        var templates = ParseUtils.getTemplates(RESOLVED_TEMPLATE, stripped);

        return templates.stream()
            .map(ParseUtils::getTemplateParametersWithValue)
            .anyMatch(params -> !params.getOrDefault("ParamWithoutName1", "").equals("-"));
    }

    private static final Map<String, List<String>> extractLangSections(List<PageContainer> contentPages) {
        return contentPages.stream()
            .filter(pc -> wb.namespace(pc.title()) == Wiki.MAIN_NAMESPACE)
            .map(com.github.wikibot.parsing.plwikt.Page::wrap)
            .collect(Collectors.toMap(
                p -> p.getTitle(),
                p -> p.getAllSections().stream()
                    .map(s -> s.getLangShort())
                    // not using .toList() since the serialized XML result is way too bulky
                    .collect(Collectors.toCollection(ArrayList::new))
            ));
    }

    private static final Map<String, Integer> buildStats(List<Entry> entries) {
        var stats = new HashMap<String, Integer>();
        stats.put("threshold", STALE_DAYS);
        stats.put("threads", entries.size());
        stats.put("resolved", (int) entries.stream().filter(e -> e.threadState.equals(ThreadState.RESOLVED.value)).count());
        stats.put("stale", (int) entries.stream().filter(e -> e.threadState.equals(ThreadState.STALE.value)).count());
        stats.put("ongoing", (int) entries.stream().filter(e -> e.threadState.equals(ThreadState.ONGOING.value)).count());
        stats.put("new", (int) entries.stream().filter(e -> e.threadState.equals(ThreadState.NEW.value)).count());

        var pages = entries.stream().collect(Collectors.groupingBy(e -> e.talkPage));
        stats.put("pages", pages.size());
        stats.put("orphaned", (int) pages.values().stream().flatMap(List::stream).filter(e -> e.isOrphaned).count());

        return stats;
    }

    private static void storeData(List<Entry> entries, Map<String, List<String>> langSections, Map<String, Integer> stats) throws IOException {
        var xstream = new XStream(new StaxDriver());
        xstream.processAnnotations(Entry.class);

        try (var bos = new BufferedOutputStream(Files.newOutputStream(LOCATION.resolve("entries.xml")))) {
            xstream.toXML(entries, bos);
        }

        try (var bos = new BufferedOutputStream(Files.newOutputStream(LOCATION.resolve("langSections.xml")))) {
            xstream.toXML(langSections, bos);
        }

        Files.write(LOCATION.resolve("stats.xml"), List.of(xstream.toXML(stats)));
        Files.write(LOCATION.resolve("timestamp.xml"), List.of(xstream.toXML(NOW)));

        Files.deleteIfExists(LOCATION.resolve("UPDATED"));
    }

    // keep in sync with com.github.wikibot.webapp.MonitorTalkPages
    @XStreamAlias("entry")
    static class Entry {
        @XStreamAlias("pagename")
        String talkPage;

        @XStreamAlias("thread")
        String threadTitle;

        @XStreamAlias("state")
        String threadState;

        @XStreamAlias("timestamp")
        OffsetDateTime lastTimestamp;

        @XStreamAlias("orphaned")
        boolean isOrphaned;
    }
}
