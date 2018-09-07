package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class ReviewPolishGerunds implements Selectorizable {
	private static Wikibot wb;
	private static final String location = "./data/scripts.plwikt/ReviewPolishGerunds/";
	private static final String f_pages = location + "pages.txt";
	private static final String f_info = location + "info.ser";
	private static final String f_worklist = location + "worklist.txt";
	private static final Pattern p_relatedTerms = Pattern.compile(": \\{\\{czas\\}\\} \\[\\[.+?\\]\\] \\{\\{n?dk\\}\\}");
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession(Domains.PLWIKT.getDomain());
				getLists();
				break;
			case 'r':
				wb = Login.createSession(Domains.PLWIKT.getDomain());
				review();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		String[] titles = Files.lines(Paths.get(f_pages)).toArray(String[]::new);
		PageContainer[] pages = wb.getContentOfPages(titles);
		Map<String, String> worklist = new LinkedHashMap<>();
		
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section section = p.getPolishSection().get();
			
			String definition = section.getField(FieldTypes.DEFINITIONS).get().getContent();
			List<String> templates = ParseUtils.getTemplates("odczasownikowy od", definition);
			
			if (templates.isEmpty()) {
				continue;
			}
			
			String template = templates.get(0);
			String verb = ParseUtils.getTemplateParam(template, 1);
			
			Field relatedTerms = section.getField(FieldTypes.RELATED_TERMS).get();
			String relatedTermsText = relatedTerms.getContent();
			String patternString = String.format(": (1.1) {{etymn|pol|%s|-anie}}", verb);
			Pattern p_etymology = Pattern.compile(patternString, Pattern.LITERAL);
			Field etymology = section.getField(FieldTypes.ETYMOLOGY).get();
			String etymologyText = etymology.getContent();
			
			if (
				!p_relatedTerms.matcher(relatedTermsText).matches() ||
				!p_etymology.matcher(etymologyText).matches()
			) {
				continue;
			}
			
			String newContent = String.format("{{czas}} [[%s]]", verb);
			relatedTerms.editContent(newContent, true);
			etymology.editContent("");
			
			String modelText = MissingPolishGerunds.makePage(verb, title, false).replace("\r", "").trim();
			String original = p.toString().trim();
			
			if (!modelText.equals(original)) {
				continue;
			}
			
			worklist.put(title, relatedTermsText);
		}
		
		System.out.printf("Tamaño de la lista: %d%n", worklist.size());
		
		Misc.serialize(pages, f_info);
		Files.write(Paths.get(f_worklist), Arrays.asList(Misc.makeList(worklist)));
	}
	
	public static void review() throws ClassNotFoundException, IOException, LoginException {
		PageContainer[] pages = Misc.deserialize(f_info);
		String[] lines = Files.lines(Paths.get(f_worklist)).toArray(String[]::new);
		Map<String, String> worklist = Misc.readList(lines);
		Set<String> titles = worklist.keySet();
		List<String> errors = new ArrayList<>();
		
		for (String title : titles) {
			PageContainer page = Misc.retrievePage(pages, title);
			
			if (page == null) {
				System.out.printf("Error en \"%s\"%n", title);
				continue;
			}
			
			Revision rev = wb.getTopRevision(title);
			
			if (!rev.getTimestamp().equals(page.getTimestamp())) {
				System.out.printf("Conflicto en \"%s\"%n", title);
				errors.add(title);
				continue;
			}
			
			wb.review(rev, "");
		}
		
		System.out.printf("Detectados %d conflictos en: %s%n", errors.size(), errors.toString());
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		new File(f_worklist).renameTo(f);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new ReviewPolishGerunds());
	}
}
