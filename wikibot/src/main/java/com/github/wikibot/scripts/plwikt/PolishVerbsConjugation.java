package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class PolishVerbsConjugation implements Selectorizable {
	private static Wikibot wb;
	private static final String location = "./data/scripts.plwikt/PolishVerbsConjugation/";
	private static final String f_serialized = location + "/targets.ser";
	private static final String f_worklist = location + "/worklist.txt";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getLists();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		PageContainer[] pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", Wiki.MAIN_NAMESPACE);
		List<PageContainer> targets = new ArrayList<>();
		Map<String, String> map = new HashMap<>(1000);
		
		outer:
		for (PageContainer page : pages) {
			String content = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.INFLECTION))
				.map(Field::getContent)
				.orElse("");
			
			List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", content);
			
			for (String template : templates) {
				String parameter = ParseUtils.getTemplateParam(template, "koniugacja", true);
				
				if (parameter == null || parameter.isEmpty()) {
					targets.add(page);
					map.put(page.getTitle(), content);
					continue outer;
				}
			}
		}
		
		System.out.printf("Encontrados: %d%n", targets.size());
		Misc.serialize(targets, f_serialized);
		Files.write(Paths.get(f_worklist), Arrays.asList(Misc.makeList(map)));
	}
	
	public static void edit() throws ClassNotFoundException, IOException, LoginException {
		List<PageContainer> pages = Misc.deserialize(f_serialized);
		String[] lines = Files.lines(Paths.get(f_worklist)).toArray(String[]::new);
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
			
			Optional.of(p)
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.INFLECTION))
				.ifPresent(f -> f.editContent(content));
			
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
