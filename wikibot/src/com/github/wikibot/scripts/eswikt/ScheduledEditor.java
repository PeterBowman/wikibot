package com.github.wikibot.scripts.eswikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.EditorBase;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class ScheduledEditor {
	private static final String LOCATION = "./data/scripts.eswikt/ScheduledEditor/";
	private static final String LAST_ENTRY = LOCATION + "last.txt";
	private static final String ERROR_LOG = LOCATION + "errors.txt";
	private static final int BATCH = 500;
	private static final int SLEEP_MINS = 5;
	private static ESWikt wb;
	
	public static void main(String[] args) throws FailedLoginException, IOException {
		wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		wb.setThrottle(5000);
		
		if (args.length == 0) {
			final String category = "";
			processCategorymembers(category);
			return;
		} else {
			switch (args[0]) {
				case "-c":
					if (args.length < 2) {
						System.out.println("Category name is missing.");
						return;
					}
					
					processCategorymembers(args[1]);
					break;
				case "-a":
					processAllpages();
					break;
				default:
					System.out.println("Insufficient parameters supplied.");
					return;
			}
		}
	}
	
	private static void processCategorymembers(String category) throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers(category, ESWikt.MAIN_NAMESPACE);
		
		Stream.of(pages)
			.filter(ScheduledEditor::filterPages)
			.allMatch(ScheduledEditor::processPage);
	}
	
	private static void processAllpages() {
		String lastEntry = getLastSavedEntry();
		
		while (true) {
			PageContainer[] pages;
			
			try {
				pages = wb.listPagesContent(lastEntry, BATCH, ESWikt.MAIN_NAMESPACE);
			} catch (IOException e) {
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
			
			boolean result = Stream.of(pages)
				.limit(pages.length - 1)
				.filter(ScheduledEditor::filterPages)
				.allMatch(ScheduledEditor::processPage);
			
			if (!result) {
				return;
			}
			
			lastEntry = pages[pages.length - 1].getTitle();
			saveLastEntry(lastEntry);
		}
	}
	
	private static String getLastSavedEntry() {
		String[] lines;
		
		try {
			lines = IOUtils.loadFromFile(LAST_ENTRY, "", "UTF8");
		} catch (FileNotFoundException e) {
			return null;
		}
		
		return lines[0];
	}
	
	private static void saveLastEntry(String entry) {
		try {
			IOUtils.writeToFile(entry, LAST_ENTRY);
			System.out.printf("Last entry: %s%n", entry);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private static boolean filterPages(PageContainer pc) {
		String text = pc.getText();
		
		if (
			!ParseUtils.getTemplates("TRANSLIT", text).isEmpty() ||
			!ParseUtils.getTemplates("TRANS", text).isEmpty() ||
			!ParseUtils.getTemplates("TAXO", text).isEmpty() ||
			!ParseUtils.getTemplates("carácter oriental", text).isEmpty() ||
			!ParseUtils.getTemplates("Chono-ES", text).isEmpty() ||
			!ParseUtils.getTemplates("INE-ES", text).isEmpty() ||
			!ParseUtils.getTemplates("POZ-POL-ES", text).isEmpty()
		) {
			return false;
		}
		
		Page p = null;
		
		try {
			p = Page.wrap(pc);
		} catch (Exception e) {
			logError("Page wrap error", pc.getTitle(), e);
			return false;
		}
		
		return !p.hasSectionWithHeader("^([Ff]orma|\\{\\{forma) .+");
	}
	
	private static boolean processPage(PageContainer pc) {
		EditorBase editor = new Editor(pc);
		
		try {
			editor.check();
		} catch (Throwable t) {
			logError("eswikt.Editor error", pc.getTitle(), t);
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
	
	private static void editEntry(PageContainer pc, EditorBase editor) throws Throwable {
		try {
			wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
			System.out.println(editor.getLogs());
		} catch (IOException e) {
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
}
