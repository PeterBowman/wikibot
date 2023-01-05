package com.github.wikibot.tasks.eswikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.CredentialException;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class MaintenanceScript {
    private static final Path LOCATION = Paths.get("./data/tasks.eswikt/MaintenanceScript/");
    private static final Path LAST_DATE = LOCATION.resolve("last_date.txt");
    private static final Path PICK_DATE = LOCATION.resolve("pick_date.txt");
    private static final Path ERROR_LOG = LOCATION.resolve("errors.txt");

    private static final int THREAD_CHECK_SECS = 5;
    private static volatile RuntimeException threadExecutionException;

    private static final Wikibot wb = Wikibot.newSession("es.wiktionary.org");

    public static void main(String[] args) throws Exception {
        String startTimestamp = extractTimestamp();

        int gapHours;

        try {
            gapHours = Integer.parseInt(args[0]);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            gapHours = 0;
        }

        Login.login(wb);

        OffsetDateTime earliest = OffsetDateTime.parse(startTimestamp);
        OffsetDateTime latest = OffsetDateTime.now(wb.timezone());
        OffsetDateTime gap = latest;

        if (gapHours > 0) {
            gap = gap.minusHours(gapHours);
        }

        if (gap.isBefore(earliest)) {
            return;
        }

        Map<String, Boolean> rcoptions = Map.of("redirect", false);
        List<String> rctypes = List.of("new", "edit");
        List<Wiki.Revision> revs = wb.recentChanges(earliest, latest, rcoptions, rctypes, false, wb.getCurrentUser().getUsername(), Wiki.MAIN_NAMESPACE);

        Wiki.RequestHelper helper = wb.new RequestHelper().withinDateRange(earliest, latest);
        List<Wiki.LogEntry> logs = wb.getLogEntries(Wiki.MOVE_LOG, "move", helper);

        List<String> titles = Stream.of(
                revs.stream().collect(new RevisionCollector(gap)),
                logs.stream().collect(new LogCollector(gap))
            )
            .flatMap(Collection::stream)
            .distinct()
            .filter(title -> !StringUtils.containsAny(title, '/', ':'))
            .toList();

        List<PageContainer> pages = wb.getContentOfPages(titles).stream()
            // TODO: implement a Comparator in PageContainer so this is not necessary anymore
            .sorted((pc1, pc2) -> Integer.compare(titles.indexOf(pc1.getTitle()), titles.indexOf(pc2.getTitle())))
            .toList();

        for (PageContainer pc : pages) {
            AbstractEditor editor = new Editor(pc);
            Thread thread = new Thread(editor::check);

            try {
                monitorThread(thread);
            } catch (TimeoutException e) {
                logError("Editor.check() timeout", pc.getTitle(), e);
                OffsetDateTime tempTimestamp = pc.getTimestamp().plusSeconds(1);
                storeTimestamp(tempTimestamp);
                System.exit(0);
            } catch (UnsupportedOperationException e) {
                continue;
            } catch (Throwable t) {
                logError("Editor.check() error", pc.getTitle(), t);
                continue;
            }

            if (editor.isModified()) {
                try {
                    wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
                    System.out.println(editor.getLogs());
                } catch (CredentialException ce) {
                    logError("Permission denied", pc.getTitle(), ce);
                    continue;
                } catch (ConcurrentModificationException cme) {
                    logError("Edit conflict", pc.getTitle(), cme);
                    continue;
                } catch (AccountLockedException | AssertionError e) {
                    logError("Blocked or session lost", pc.getTitle(), e);
                    break;
                } catch (Throwable t) {
                    logError("Edit error", pc.getTitle(), t);
                    continue;
                }
            }
        }

        storeTimestamp(gap);
        wb.logout();
    }

    private static String extractTimestamp() throws IOException {
        String startTimestamp;

        if (Files.exists(LAST_DATE)) {
            startTimestamp = Files.readString(LAST_DATE).strip();
        } else if (Files.exists(PICK_DATE)) {
            startTimestamp = Files.readString(PICK_DATE).strip();
        } else {
            throw new UnsupportedOperationException("No timestamp file found.");
        }

        if (startTimestamp.isEmpty()) {
            throw new UnsupportedOperationException("No initial timestamp found.");
        }

        return startTimestamp;
    }

    private static void storeTimestamp(OffsetDateTime timestamp) {
        try {
            Files.write(LAST_DATE, List.of(timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        } catch (IOException e) {}
    }

    private static void logError(String errorType, String entry, Throwable t) {
        Date date = new Date();

        String log = String.format(
            "%s %s in %s (%s: %s)",
            date, errorType, entry, t.getClass().getName(), t.getMessage()
        );

        System.out.println(log);
        t.printStackTrace();

        List<String> list;

        try {
            list = new ArrayList<>(Files.readAllLines(ERROR_LOG));
        } catch (IOException e) {
            list = new ArrayList<>();
        }

        list.add(log);

        try {
            Files.write(ERROR_LOG, list);
        } catch (IOException e) {}
    }

    private static void monitorThread(Thread thread) throws TimeoutException {
        thread.setUncaughtExceptionHandler(new MonitoredThreadExceptionHandler());
        thread.start();

        final long endMs = System.currentTimeMillis() + THREAD_CHECK_SECS * 1000;

        while (thread.isAlive()) {
            if (threadExecutionException != null) {
                throw threadExecutionException;
            }

            if (System.currentTimeMillis() > endMs) {
                throw new TimeoutException("Thread timeout");
            }
        }

        if (threadExecutionException != null) {
            throw threadExecutionException;
        }

        if (System.currentTimeMillis() > endMs) {
            throw new TimeoutException("Thread timeout");
        }
    }

    private static class MonitoredThreadExceptionHandler implements Thread.UncaughtExceptionHandler {
        MonitoredThreadExceptionHandler() {
            threadExecutionException = null;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            threadExecutionException = new RuntimeException(e.getMessage());
        }
    }

    private static class RevisionCollector implements Collector<Wiki.Revision, Map<String, Wiki.Revision>, List<String>> {
        // https://weblogs.java.net/blog/kocko/archive/2014/12/19/java8-how-implement-custom-collector
        // http://www.nurkiewicz.com/2014/07/introduction-to-writing-custom.html

        private OffsetDateTime dateTime;

        public RevisionCollector(OffsetDateTime dateTime) {
            this.dateTime = dateTime;
        }

        @Override
        public Supplier<Map<String, Wiki.Revision>> supplier() {
            return HashMap::new;
        }

        @Override
        public BiConsumer<Map<String, Wiki.Revision>, Wiki.Revision> accumulator() {
            return (accum, rev) -> accum.put(rev.getTitle(), rev);
        }

        @Override
        public BinaryOperator<Map<String, Wiki.Revision>> combiner() {
            return null;
        }

        @Override
        public Function<Map<String, Wiki.Revision>, List<String>> finisher() {
            return accum -> accum.values().stream()
                .filter(rev -> rev.getTimestamp().isBefore(dateTime))
                .sorted(Comparator.comparing(Wiki.Revision::getTimestamp))
                .map(Wiki.Revision::getTitle)
                .toList();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }
    }

    private static class LogCollector implements Collector<Wiki.LogEntry, Map<String, Wiki.LogEntry>, List<String>> {
        // https://weblogs.java.net/blog/kocko/archive/2014/12/19/java8-how-implement-custom-collector
        // http://www.nurkiewicz.com/2014/07/introduction-to-writing-custom.html

        private OffsetDateTime dateTime;

        public LogCollector(OffsetDateTime dateTime) {
            this.dateTime = dateTime;
        }

        @Override
        public Supplier<Map<String, Wiki.LogEntry>> supplier() {
            return HashMap::new;
        }

        @Override
        public BiConsumer<Map<String, Wiki.LogEntry>, Wiki.LogEntry> accumulator() {
            return (accum, log) -> accum.putIfAbsent(log.getDetails().get("target_title"), log);
        }

        @Override
        public BinaryOperator<Map<String, Wiki.LogEntry>> combiner() {
            return null;
        }

        @Override
        public Function<Map<String, Wiki.LogEntry>, List<String>> finisher() {
            return accum -> accum.values().stream()
                .filter(log -> log.getTimestamp().isBefore(dateTime))
                .sorted(Comparator.comparing(Wiki.LogEntry::getTimestamp))
                .map(log -> log.getDetails().get("target_title"))
                .filter(title -> wb.namespace(title) == Wiki.MAIN_NAMESPACE)
                .toList();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }
    }
}
