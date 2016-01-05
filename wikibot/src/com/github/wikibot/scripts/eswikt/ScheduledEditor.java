package com.github.wikibot.scripts.eswikt;

import static org.wikiutils.ParseUtils.getTemplates;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.FailedLoginException;
import javax.xml.stream.XMLStreamException;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.IOUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class ScheduledEditor {
	private static final String LOCATION = "./data/scripts.eswikt/ScheduledEditor/";
	private static final String LAST_ENTRY = LOCATION + "last.ser";
	private static final String ERROR_LOG = LOCATION + "errors.txt";
	
	private static final int BATCH = 500;
	private static final int SLEEP_MINS = 5;
	private static final int THREAD_CHECK_SECS = 5;
	
	private static ESWikt wb;
	private static volatile RuntimeException threadExecutionException;
	private static ExitCode exitCode = ExitCode.SUCCESS;
	
	public static void main(String[] args) throws FailedLoginException, IOException {
		wb = Login.retrieveSession(Domains.ESWIKT, Users.USER2);
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
					
					String decoded = URLDecoder.decode(args[1], "UTF8");
					processCategorymembers(decoded);
					break;
				case "-a":
					processAllpages();
					break;
				case "-d":
					XMLDumpReader reader = new XMLDumpReader(args[1]);
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
		PageContainer[] pages = wb.getContentOfCategorymembers(category, ESWikt.MAIN_NAMESPACE);
		
		Stream.of(pages)
			.filter(ScheduledEditor::filterPages)
			.allMatch(ScheduledEditor::processPage);
	}
	
	private static void processAllpages() {
		String lastEntry = retrieveLastEntry();
		
		while (true) {
			PageContainer[] pages;
			
			try {
				pages = wb.listPagesContent(lastEntry, BATCH, ESWikt.MAIN_NAMESPACE);
			} catch (IOException | UnknownError e) {
				e.printStackTrace();
				sleep();
				continue;
			} catch (Throwable t) {
				t.printStackTrace();
				return;
			}
			
			if (pages.length < 2) {
				break;
			}
			
			// TODO: enable parallelization?
			for (int i = 0; i < pages.length - 1; i++) {
				PageContainer pc = pages[i];
				
				if (!filterPages(pc)) {
					continue;
				}
				
				if (!processPage(pc)) {
					String nextEntry = pages[i + 1].getTitle();
					storeEntry(nextEntry);
					return;
				}
			}
			
			lastEntry = pages[pages.length - 1].getTitle();
			storeEntry(lastEntry);
		}
	}
	
	private static void processDumpFile(XMLDumpReader dumpReader) {
		try (Stream<XMLRevision> r = dumpReader.getStAXReader().stream(wb.getSiteStatistics().get("pages"))) {
			r.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(XMLRevision::toPageContainer)
				.forEach(ScheduledEditor::processPage); // TODO: review exit codes
		} catch (XMLStreamException | IOException e) {
			e.printStackTrace();
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
			} catch (CredentialException e) {
				logError("Permission denied", pc.getTitle(), e);
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
		
		String[] lines;
		
		try {
			lines = IOUtils.loadFromFile(ERROR_LOG, "", "UTF8");
		} catch (FileNotFoundException e) {
			lines = new String[]{};
		}
		
		List<String> list = new ArrayList<>(Arrays.asList(lines));
		list.add(log);
		
		try {
			IOUtils.writeToFile(String.join("\n", list), ERROR_LOG);
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
