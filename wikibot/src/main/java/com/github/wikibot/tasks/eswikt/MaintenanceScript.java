package com.github.wikibot.tasks.eswikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class MaintenanceScript {
	private static final String LOCATION = "./data/tasks.eswikt/MaintenanceScript/";
	private static final String LAST_DATE = LOCATION + "last_date.txt";
	private static final String PICK_DATE = LOCATION + "pick_date.txt";
	private static final String ERROR_LOG = LOCATION + "errors.txt";
	
	private static final int THREAD_CHECK_SECS = 5;
	private static volatile RuntimeException threadExecutionException;
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		String startTimestamp = extractTimestamp();
		
		int gapHours;
		
		try {
			gapHours = Integer.parseInt(args[0]);
		} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
			gapHours = 0;
		}
		
		wb = Login.createSession(Domains.ESWIKT.getDomain());
		
		OffsetDateTime start = OffsetDateTime.parse(startTimestamp);
		OffsetDateTime end = OffsetDateTime.now(wb.timezone());
		OffsetDateTime gap = end;
		
		if (gapHours > 0) {
			gap = gap.minusHours(gapHours);
		}
		
		if (gap.isBefore(start)) {
			return;
		}
		
		Map<String, Boolean> rcoptions = new HashMap<>();
		rcoptions.put("redirect", false);
		
		List<String> rctypes = Arrays.asList(new String[] {"new", "edit"});
		
		Wiki.Revision[] revs = wb.recentChanges(start, end, rcoptions, rctypes, false, wb.getCurrentUser().getUsername(), Wiki.MAIN_NAMESPACE);
		Wiki.LogEntry[] logs = wb.getLogEntries(Wiki.MOVE_LOG, "move", null, null, end, start, Integer.MAX_VALUE, Wiki.ALL_NAMESPACES);
		
		List<String> titles = Stream.of(
				Stream.of(revs).collect(new RevisionCollector(gap)),
				Stream.of(logs).collect(new LogCollector(gap))
			)
			.flatMap(Collection::stream)
			.distinct()
			.filter(title -> !StringUtils.containsAny(title, '/', ':'))
			.collect(Collectors.toList());
		
		PageContainer[] pages = Stream.of(wb.getContentOfPages(titles.toArray(new String[titles.size()])))
			// TODO: implement a Comparator in PageContainer so this is not necessary anymore
			.sorted((pc1, pc2) -> Integer.compare(titles.indexOf(pc1.getTitle()), titles.indexOf(pc2.getTitle())))
			.toArray(PageContainer[]::new);
		
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
		File f_last_date = new File(LAST_DATE);
		File f_pick_date = new File(PICK_DATE);
		
		String startTimestamp;
		
		if (f_last_date.exists()) {
			startTimestamp = Files.readAllLines(Paths.get(LAST_DATE)).get(0);
		} else if (f_pick_date.exists()) {
			startTimestamp = Files.readAllLines(Paths.get(PICK_DATE)).get(0);
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
			Files.write(Paths.get(LAST_DATE), Arrays.asList(timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
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
			list = new ArrayList<>(Files.readAllLines(Paths.get(ERROR_LOG)));
		} catch (IOException e) {
			list = new ArrayList<>();
		}
		
		list.add(log);
		
		try {
			Files.write(Paths.get(ERROR_LOG), list);
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
				.sorted((rev1, rev2) -> rev1.getTimestamp().compareTo(rev2.getTimestamp()))
				.map(Wiki.Revision::getTitle)
				.collect(Collectors.toList());
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
			return (accum, log) -> accum.putIfAbsent((String) log.getDetails(), log);
		}
	
		@Override
		public BinaryOperator<Map<String, Wiki.LogEntry>> combiner() {
			return null;
		}
	
		@Override
		public Function<Map<String, Wiki.LogEntry>, List<String>> finisher() {
			return accum -> accum.values().stream()
				.filter(log -> log.getTimestamp().isBefore(dateTime))
				.sorted((log1, log2) -> log1.getTimestamp().compareTo(log2.getTimestamp()))
				.map(log -> (String) log.getDetails())
				.filter(title -> wb.namespace(title) == Wiki.MAIN_NAMESPACE)
				.collect(Collectors.toList());
		}
	
		@Override
		public Set<Characteristics> characteristics() {
			return Collections.emptySet();
		}
	}
}
