package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;

import org.wikipedia.WMFWiki;
import org.wikipedia.Wiki;

public final class ArticleStats {
    private static final Path LOCATION = Paths.get("./data/scripts.plwikt/ArticleStats/");
    private static final Path LAST_DUMP = LOCATION.resolve("last-dump.txt");
    private static final String LANG_TEMPLATE_CATEGORY = "Szablony indeksujące języków";
    private static final Wiki plwikt = Wiki.newSession("pl.wiktionary.org");

    private static final YearMonth START_DATE;
    private static final YearMonth END_DATE;

    static {
        // TODO
        var today = LocalDate.now();
        START_DATE = YearMonth.of(2015, 1);
        END_DATE = YearMonth.from(today);
    }

    public static void main(String[] args) throws Exception {
        //Login.login(plwikt, "Peter Bowman");

        var reader = new XMLDumpReader("plwiktionary");
        var dumpName = reader.getPathToDump().getFileName().toString();

        System.out.println("Last dump: " + getLastDumpName());
        System.out.println("Current dump:" + dumpName);

        if (dumpName.equals(getLastDumpName())) {
            System.out.println("No new dump detected, aborting");
            return;
        }

        var supportedLanguages = getSupportedLanguages();
        var stats = new StatisticsMatrix(supportedLanguages);
        var lastPageTimeline = new PageTimeline();

        try (var stream = reader.getStAXReaderStream()) {
            stream.sequential()
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                //.filter(rev -> OffsetDateTime.parse(rev.getTimestamp()).isAfter(START_DATE))
                .forEach(rev -> {
                    if (rev.getPageid() != lastPageTimeline.getId()) {
                        stats.processPageHistory(lastPageTimeline);
                        lastPageTimeline.reset(rev.getTitle(), rev.getPageid());
                    }

                    lastPageTimeline.acceptRevision(rev, supportedLanguages);
                });

            stats.processPageHistory(lastPageTimeline);
        }

        Files.writeString(LAST_DUMP, dumpName);
    }

    private static String getLastDumpName() {
        try {
            return Files.readString(LAST_DUMP);
        } catch (IOException e) {
            return "";
        }
    }

    private static Set<String> getSupportedLanguages() throws IOException {
        var collator = Collator.getInstance(new Locale("pl"));

        return plwikt.getCategoryMembers(LANG_TEMPLATE_CATEGORY, Wiki.TEMPLATE_NAMESPACE).stream()
            .map(plwikt::removeNamespace)
            .map(name -> name.replaceFirst("^język ", ""))
            .filter(name -> !name.equals("nagłówek języka")) // TODO
            .collect(Collectors.toCollection(() -> new TreeSet<>(collator)));
    }

    private static class PageTimeline {
        private final SortedSet<HistoryEvent> pageHistory = new TreeSet<>();
        private String title = "";
        private long id = 0L;

        public SortedSet<HistoryEvent> getPageHistory() {
            return Collections.unmodifiableSortedSet(pageHistory);
        }

        public void reset(String title, long id) {
            pageHistory.clear();
            this.title = title;
            this.id = id;
        }

        public void acceptRevision(XMLRevision rev, Set<String> supportedLanguages) {
            pageHistory.add(RevisionEvent.fromRevision(rev, supportedLanguages));
        }

        public long getId() {
            return id;
        }
    }

    private static abstract class HistoryEvent implements Comparable<HistoryEvent> {
        private final OffsetDateTime timestamp;

        protected HistoryEvent(OffsetDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public final OffsetDateTime getTimestamp() {
            return timestamp;
        }

        @Override
        public int compareTo(HistoryEvent other) {
            return timestamp.compareTo(other.timestamp);
        }
    }

    private static class RevisionEvent extends HistoryEvent {
        private static final Pattern PATT_CANONICAL_DEF = Pattern.compile(
                "^(?:'++)?+(?!\\{{2}forma |forma (?!ściągnięta))[^\n]+\n: *\\(\\d+\\.\\d+(\\.\\d+)?\\)"
            );

        private final List<String> langSections;
        private final long revid;

        private RevisionEvent(OffsetDateTime timestamp, List<String> langSections, long revid) {
            super(timestamp);
            this.langSections = langSections;
            this.revid = revid;
        }

        public static RevisionEvent fromRevision(XMLRevision rev, Set<String> supportedLanguages) {
            var timestamp = OffsetDateTime.parse(rev.getTimestamp());
            var langSections = parse(Page.wrap(rev), supportedLanguages);
            return new RevisionEvent(timestamp, langSections, rev.getRevid());
        }

        private static List<String> parse(Page page, Set<String> supportedLanguages) {
            return page.getAllSections().stream()
                .filter(RevisionEvent::hasCanonicalForm)
                .map(Section::getLangShort)
                .filter(supportedLanguages::contains)
                .toList();
        }

        private static boolean hasCanonicalForm(Section section) {
            return section.getField(FieldTypes.DEFINITIONS)
                .map(Field::getContent)
                .map(PATT_CANONICAL_DEF::matcher)
                .filter(Matcher::find)
                .isPresent();
        }

        public List<String> getLangSections() {
            return langSections;
        }
    }

    private static class DeletionEvent extends HistoryEvent {
        protected DeletionEvent(OffsetDateTime timestamp) {
            super(timestamp);
        }
    }

    private static class StatisticsMatrix {
        private final List<String> languages;
        private final List<YearMonth> months;
        private final Map<String, Integer> langsLut;
        private final Map<YearMonth, Integer> monthsLut;
        private final int[][] stats;

        public StatisticsMatrix(Set<String> languages) {
            this.languages = languages.stream().toList();

            var months = new ArrayList<YearMonth>();
            var initialMonth = START_DATE;

            while (!initialMonth.isAfter(END_DATE)) {
                months.add(initialMonth);
                initialMonth = initialMonth.plusMonths(1);
            }

            this.months = Collections.unmodifiableList(months);

            langsLut = IntStream.range(0, languages.size()).boxed().collect(Collectors.toMap(this.languages::get, Function.identity()));
            monthsLut = IntStream.range(0, months.size()).boxed().collect(Collectors.toMap(this.months::get, Function.identity()));

            stats = new int[langsLut.size()][monthsLut.size()];
        }

        public void processPageHistory(PageTimeline timeline) {
            HistoryEvent lastEvent = null;

            for (var event : timeline.getPageHistory()) {
                // TODO
            }
        }
    }

    private static class DeletedPageInspectorWiki extends WMFWiki {
        protected DeletedPageInspectorWiki(String domain) {
            super(domain);
        }

        public static DeletedPageInspectorWiki newSession(String domain) {
            return new DeletedPageInspectorWiki(domain);
        }
    }
}
