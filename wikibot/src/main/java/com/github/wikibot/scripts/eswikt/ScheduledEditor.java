package com.github.wikibot.scripts.eswikt;

import static org.wikiutils.ParseUtils.getTemplates;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.security.auth.login.CredentialException;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class ScheduledEditor {
	private static final Path LOCATION = Paths.get("./data/scripts.eswikt/ScheduledEditor/");
	private static final Path LAST_ENTRY = LOCATION.resolve("last.ser");
	private static final Path ERROR_LOG = LOCATION.resolve("errors.txt");
	
	private static final int BATCH = 500;
	private static final int SLEEP_MINS = 5;
	private static final int THREAD_CHECK_SECS = 5;
	
	private static Wikibot wb;
	private static volatile RuntimeException threadExecutionException;
	private static ExitCode exitCode = ExitCode.SUCCESS;
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("es.wiktionary.org");
		wb.setThrottle(5000);
		
		if (args.length == 0) {
			final String category = "";
			processCategorymembers(category);
		} else {
			switch (args[0]) {
				case "-c":
					if (args.length < 2) {
						System.out.println("Category name is missing.");
						return;
					}
					
					String decoded = URLDecoder.decode(args[1], StandardCharsets.UTF_8);
					processCategorymembers(decoded);
					break;
				case "-a":
					processAllpages();
					break;
				case "-d":
					XMLDumpReader reader = new XMLDumpReader(Paths.get(args[1].trim()));
					processDumpFile(reader);
					break;
				default:
					System.out.println("Insufficient parameters supplied.");
					return;
			}
		}
		
		System.exit(exitCode.value);
	}
	
	private static void processCategorymembers(String category) throws IOException {
		wb.getContentOfCategorymembers(category, Wiki.MAIN_NAMESPACE).stream()
			.filter(ScheduledEditor::filterPages)
			.allMatch(ScheduledEditor::processPage);
	}
	
	private static void processAllpages() {
		String lastEntry = retrieveLastEntry();
		
		while (true) {
			List<PageContainer> pages;
			
			try {
				List<String> titles = wb.listPages("", null, Wiki.MAIN_NAMESPACE, lastEntry, null, Boolean.FALSE);
				List<String> batch = titles.subList(0, BATCH);
				pages = wb.getContentOfPages(batch);
			} catch (IOException | UnknownError e) {
				e.printStackTrace();
				sleep();
				continue;
			} catch (Throwable t) {
				t.printStackTrace();
				return;
			}
			
			if (pages.size() < 2) {
				break;
			}
			
			// TODO: enable parallelization?
			for (int i = 0; i < pages.size() - 1; i++) {
				PageContainer pc = pages.get(i);
				
				if (!filterPages(pc)) {
					continue;
				}
				
				if (!processPage(pc)) {
					String nextEntry = pages.get(i + 1).getTitle();
					storeEntry(nextEntry);
					return;
				}
			}
			
			lastEntry = pages.get(pages.size() - 1).getTitle();
			storeEntry(lastEntry);
		}
	}
	
	private static void processDumpFile(XMLDumpReader dumpReader) throws IOException {
		try (var stream = dumpReader.getStAXReaderStream()) {
			stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(XMLRevision::toPageContainer)
				.forEach(ScheduledEditor::processPage); // TODO: review exit codes
		}
	}
	
	private static String retrieveLastEntry() {
		try {
			return Misc.deserialize(LAST_ENTRY);
		} catch (ClassNotFoundException | IOException e) {
			return null; // TODO: review
		}
	}
	
	private static void storeEntry(String entry) {
		try {
			Misc.serialize(entry, LAST_ENTRY);
			System.out.printf("Last entry: %s%n", entry);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static boolean filterPages(PageContainer pc) {
		String title = pc.getTitle();
		String text = pc.getText();
		
		return
			!StringUtils.containsAny(title, '/', ':') &&
			getTemplates("TRANSLIT", text).isEmpty() &&
			getTemplates("TAXO", text).isEmpty() &&
			getTemplates("carácter oriental", text).isEmpty();
	}
	
	private static boolean processPage(PageContainer pc) {
		AbstractEditor editor = new Editor(pc);
		Thread thread = new Thread(editor::check);
		
		try {
			monitorThread(thread);
		} catch (TimeoutException e) {
			logError("Editor.check() timeout", pc.getTitle(), e);
			exitCode = ExitCode.FAILURE;
			return false;
		} catch (UnsupportedOperationException e) {
			return true;
		} catch (Throwable t) {
			logError("Editor.check() error", pc.getTitle(), t);
			return true;
		}
		
		if (editor.isModified()) {
			try {
				editEntry(pc, editor);
			} catch (CredentialException ce) {
				logError("Permission denied", pc.getTitle(), ce);
				return true;
			} catch (ConcurrentModificationException cme) {
				logError("Edit conflict", pc.getTitle(), cme);
				return true;
			} catch (Throwable t) {
				logError("Edit error", pc.getTitle(), t);
				return false;
			}
		}
		
		return true;
	}
	
	private static void monitorThread(Thread thread) throws TimeoutException {
		// TODO: inspect wait() and notify() methods
		// http://stackoverflow.com/questions/2536692
		
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
	
	private static void editEntry(PageContainer pc, AbstractEditor editor) throws Throwable {
		try {
			wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
			System.out.println(editor.getLogs());
		} catch (IOException | UnknownError e) {
			sleep();
			editEntry(pc, editor);
			return;
		} catch (Throwable t) {
			throw t;
		}
	}
	
	private static void sleep() {
		System.out.printf("Sleeping... (%d minutes)%n", SLEEP_MINS);
		
		try {
			Thread.sleep(1000 * 60 * SLEEP_MINS);
		} catch (InterruptedException e2) {}
	}
	
	private static void logError(String errorType, String entry, Throwable t) {
		Date date = new Date();
		
		String log = String.format(
			"%s %s in %s (%s: %s)",
			date, errorType, entry, t.getClass().getName(), t.getMessage()
		);
		
		System.out.println(log);
		t.printStackTrace();
		
		List<String> list = new ArrayList<>();
		
		try {
			list.addAll(Files.readAllLines(ERROR_LOG));
		} catch (IOException e) {}
		
		list.add(log);
		
		try {
			Files.write(ERROR_LOG, list);
		} catch (IOException e) {}
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
	
	private enum ExitCode {
		SUCCESS (0),
		FAILURE (1); // thread timeout
		
		int value;
		
		ExitCode(int value) {
			this.value = value;
		}
	}
}
