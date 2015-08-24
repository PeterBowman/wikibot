package com.github.wikibot.scripts.eswikt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

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
	private static ESWikt wb;
	private static List<String> errors;
	
	public static void main(String[] args) throws FailedLoginException, IOException {
		wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		wb.setThrottle(5000);
		
		errors = new ArrayList<String>(500);
		
		if (args.length == 0) {
			final String category = "";
			processCategory(category);
			return;
		} else {
			switch (args[0]) {
				case "-c":
					if (args.length < 2) {
						return;
					}
					
					processCategory(args[1]);
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
	
	private static void processCategory(String category) throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers(category, ESWikt.MAIN_NAMESPACE);
		
		Stream.of(pages)
			.filter(ScheduledEditor::filterPages)
			.forEach(ScheduledEditor::processPage);
	}
	
	private static void processAllpages() {
		
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
		
		Page p = Page.wrap(pc);
		return !p.hasSectionWithHeader("^[Ff]orma .*");
	}
	
	private static void processPage(PageContainer pc) {
		EditorBase editor = new Editor(pc);
		editor.check();
		
		if (editor.isModified()) {
			try {
				wb.edit(pc.getTitle(), editor.getPageText(), editor.getSummary(), pc.getTimestamp());
				System.out.println(editor.getLogs());
			} catch (Exception e) {
				e.printStackTrace();
				errors.add(pc.getTitle());
			}
		}
	}
}
