package com.github.wikibot.tasks.eswikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.FailedLoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class MaintenanceScript {
	private static final String LOCATION = "./data/tasks.eswikt/MaintenanceScript/";
	private static final String LAST_DATE = LOCATION + "last_date.txt";
	private static final String PICK_DATE = LOCATION + "pick_date.txt";
	private static final String ERROR_LOG = LOCATION + "errors.txt";
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	private static final int THREAD_CHECK_SECS = 5;
	private static RuntimeException threadExecutionException;
	
	public static void main(String[] args) throws FailedLoginException, IOException, ParseException {
		String startTimestamp = extractTimestamp();
		
		int gap;
		
		try {
			gap = Integer.parseInt(args[0]);
		} catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
			gap = 0;
		}
		
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		
		Calendar startCal = Calendar.getInstance();
		startCal.setTime(DATE_FORMAT.parse(startTimestamp));
		
		Calendar endCal = wb.makeCalendar();
		Calendar gapCal = (Calendar) endCal.clone();
		
		if (gap > 0) {
			gapCal.add(Calendar.HOUR, -gap);
		}
		
		if (gapCal.before(startCal)) {
			return;
		}
		
		int rcoptions = Wikibot.HIDE_REDIRECT | Wikibot.HIDE_BOT;
		int rctypes = Wikibot.RC_NEW | Wikibot.RC_EDIT;
		
		List<String> titles = Stream
			.of(wb.recentChanges(startCal, endCal, rcoptions, rctypes, false, Wikibot.MAIN_NAMESPACE))
			.collect(new RevisionCollector(gapCal));
		
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
				Calendar tempCal = pc.getTimestamp();
				tempCal.add(Calendar.SECOND, 1);
				storeTimestamp(tempCal);
				System.exit(0);
			} catch (Throwable t) {
				logError("Editor.check() error", pc.getTitle(), t);
				continue;
			}
			
			if (editor.isModified()) {
				try {
					wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
					System.out.println(editor.getLogs());
				} catch (CredentialException e) {
					logError("Permission denied", pc.getTitle(), e);
					continue;
				} catch (Throwable t) {
					logError("Edit error", pc.getTitle(), t);
					continue;
				}
			}
		}
		
		storeTimestamp(gapCal);
		wb.logout();
	}
	
	private static String extractTimestamp() throws FileNotFoundException {
		File f_last_date = new File(LAST_DATE);
		File f_pick_date = new File(PICK_DATE);
		
		String startTimestamp;
		
		if (f_last_date.exists()) {
			startTimestamp = IOUtils.loadFromFile(LAST_DATE, "", "UTF8")[0];
		} else if (f_pick_date.exists()) {
			startTimestamp = IOUtils.loadFromFile(PICK_DATE, "", "UTF8")[0];
		} else {
			throw new UnsupportedOperationException("No timestamp file found.");
		}
		
		if (startTimestamp.isEmpty()) {
			throw new UnsupportedOperationException("No initial timestamp found.");
		}
		
		return startTimestamp;
	}
	
	private static void storeTimestamp(Calendar cal) {
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
		
		try {
			IOUtils.writeToFile(DATE_FORMAT.format(cal.getTime()), LAST_DATE);
		} catch (IOException e) {}
	}

	private static void logError(String errorType, String entry, Throwable t) {
		System.out.printf("%s in %s%n", errorType, entry);
		t.printStackTrace();
		String[] lines;
		
		try {
			lines = IOUtils.loadFromFile(ERROR_LOG, "", "UTF8");
		} catch (FileNotFoundException e) {
			lines = new String[]{};
		}
		
		List<String> list = new ArrayList<String>(Arrays.asList(lines));
		list.add(entry);
		
		try {
			IOUtils.writeToFile(String.join("\n", list), ERROR_LOG);
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
}

class RevisionCollector implements Collector<Revision, HashMap<String, Revision>, List<String>> {
	// https://weblogs.java.net/blog/kocko/archive/2014/12/19/java8-how-implement-custom-collector
	// http://www.nurkiewicz.com/2014/07/introduction-to-writing-custom.html
	
	private Calendar cal;

	public RevisionCollector(Calendar cal) {
		this.cal = cal;
	}
	
	@Override
	public Supplier<HashMap<String, Revision>> supplier() {
		return HashMap::new;
	}

	@Override
	public BiConsumer<HashMap<String, Revision>, Revision> accumulator() {
		return (accum, rev) -> {
			accum.put(rev.getPage(), rev);
		};
	}

	@Override
	public BinaryOperator<HashMap<String, Revision>> combiner() {
		return null;
	}

	@Override
	public Function<HashMap<String, Revision>, List<String>> finisher() {
		return accum -> {
			return accum.values().stream()
				.filter(rev -> rev.getTimestamp().before(cal))
				.sorted((rev1, rev2) -> rev1.getTimestamp().compareTo(rev2.getTimestamp()))
				.map(Revision::getPage)
				.collect(Collectors.toList());
		};
	}

	@Override
	public Set<java.util.stream.Collector.Characteristics> characteristics() {
		return Collections.emptySet();
	}
}