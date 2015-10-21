package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class PolishVerbsConjugation implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/PolishVerbsConjugation/";
	private static final String f_serialized = location + "/targets.ser";
	private static final String f_worklist = location + "/worklist.txt";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
				getLists();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User2);
				edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		PageContainer[] pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", PLWikt.MAIN_NAMESPACE);
		List<PageContainer> targets = new ArrayList<>();
		Map<String, String> map = new HashMap<>(1000);
		
		outer:
		for (PageContainer page : pages) {
			Page p = Page.wrap(page);
			Section s = p.getPolishSection();
			
			if (s == null) {
				continue;
			}
			
			Field inflection = s.getField(FieldTypes.INFLECTION);
			String content = inflection.getContent();
			List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", content);
			
			for (String template : templates) {
				String parameter = ParseUtils.getTemplateParam(template, "koniugacja", true);
				
				if (parameter == null || parameter.isEmpty()) {
					targets.add(page);
					map.put(p.getTitle(), content);
					continue outer;
				}
			}
		}
		
		System.out.printf("Encontrados: %d%n", targets.size());
		Misc.serialize(targets, f_serialized);
		IOUtils.writeToFile(Misc.makeList(map), f_worklist);
	}
	
	public static void edit() throws ClassNotFoundException, IOException, LoginException {
		List<PageContainer> pages = Misc.deserialize(f_serialized);
		String[] lines = IOUtils.loadFromFile(f_worklist, "", "UTF8");
		Map<String, String> map = Misc.readList(lines);
		List<String> errors = new ArrayList<>();
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		wb.setThrottle(2000);
		
		for (Entry<String, String> entry : map.entrySet()) {
			String title = entry.getKey();
			String content = entry.getValue();
			
			PageContainer page = Misc.retrievePage(pages, title);
			
			if (page == null) {
				System.out.printf("Error en \"%s\"%n", title);
				continue;
			}
			
			Page p = Page.wrap(page);
			
			p.getPolishSection().getField(FieldTypes.INFLECTION).editContent(content);
			String summary = "wstawienie modelu koniugacji; wer.: [[User:Peter Bowman]]";
			
			try {
				wb.edit(title, p.toString(), summary, false, true, -2, page.getTimestamp());
			} catch (Exception e) {
				errors.add(title);
			}
		}
		
		System.out.printf("Errores: %d - %s%n", errors.size(), errors.toString());
	}

	public static void main(String[] args) {
		Misc.runTimerWithSelector(new PolishVerbsConjugation());
	}
}
