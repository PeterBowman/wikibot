package com.github.wikibot.scripts.eswikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.EditorBase;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class ScheduledEditor {
	private static final String LOCATION = "./data/scripts.eswikt/ScheduledEditor/";
	private static final String LAST_ENTRY = LOCATION + "last.txt";
	private static final int BATCH = 500;
	private static ESWikt wb;
	private static List<String> errors;
	
	public static void main(String[] args) throws FailedLoginException, IOException {
		wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		wb.setThrottle(5000);
		
		errors = new ArrayList<String>(500);
		
		if (args.length == 0) {
			final String category = "";
			processCategorymembers(category);
			return;
		} else {
			switch (args[0]) {
				case "-c":
					if (args.length < 2) {
						return;
					}
					
					processCategorymembers(args[1]);
					break;
				case "-a":
					processAllpages();
					break;
				default:
					return;
			}
		}
		
		System.out.printf("%d errors: %s%n", errors.size(), errors);
	}
	
	private static void processCategorymembers(String category) throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers(category, ESWikt.MAIN_NAMESPACE);
		
		Stream.of(pages)
			.filter(ScheduledEditor::filterPages)
			.forEach(ScheduledEditor::processPage);
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
			} catch (Exception e2) {
				e2.printStackTrace();
				return;
			}
			
			if (pages.length < 2) {
				break;
			}
			
			Stream.of(pages)
				.limit(pages.length - 1)
				.filter(ScheduledEditor::filterPages)
				.forEach(ScheduledEditor::processPage);
			
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
		} catch (ParsingException e) {
			onExceptionThrow(pc, e);
			return false;
		}
		
		return !p.hasSectionWithHeader("^[Ff]orma .*");
	}
	
	private static void processPage(PageContainer pc) {
		EditorBase editor = new Editor(pc);
		
		try {
			editor.check();
		} catch (ParsingException e) {
			onExceptionThrow(pc, e);
			return;
		}
		
		if (editor.isModified()) {
			try {
				editEntry(pc, editor);
			} catch (Exception e) {
				System.out.printf("ERROR: %s%n", pc.getTitle());
				errors.add(pc.getTitle());
				return;
			}
			
			System.out.println(editor.getLogs());
		}
	}
	
	private static void editEntry(PageContainer pc, EditorBase editor) throws Exception {
		try {
			wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
		} catch (IOException e1) {
			e1.printStackTrace();
			sleep();
			editEntry(pc, editor);
		} catch (Exception e2) {
			e2.printStackTrace();
			throw e2;
		}
	}
	
	private static void sleep() {
		try {
			Thread.sleep(1000 * 60 * 5);
		} catch (InterruptedException e2) {}
	}

	private static void onExceptionThrow(PageContainer pc, ParsingException e) {
		System.out.printf("ParsingException in %s%n", pc.getTitle());
		errors.add(pc.getTitle());
		e.printStackTrace();
	}
}
