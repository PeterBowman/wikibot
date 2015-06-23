package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

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

public final class SpanishCanonicalInflectedForms implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/tasks.plwikt/SpanishCanonicalInflectedForms/";
	private static final String targetpage = "Wikipedysta:Peter Bowman/hiszpańskie formy czasownikowe";
	private static final Map<Character, Character> strippedAccentsMap;
	
	static {
		strippedAccentsMap = new HashMap<Character, Character>(5, 1);
		
		strippedAccentsMap.put('á', 'a');
		strippedAccentsMap.put('é', 'e');
		strippedAccentsMap.put('í', 'i');
		strippedAccentsMap.put('ó', 'o');
		strippedAccentsMap.put('ú', 'u');
	}
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
			case '2':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User2);
				getList(op == '2' ? true : false);
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList(boolean edit) throws IOException, LoginException {
		PageContainer[] pages = wb.getContentOfCategorymembers("Formy czasowników hiszpańskich", PLWikt.MAIN_NAMESPACE);
		List<String> forms = new ArrayList<String>(1500);
		
		for (PageContainer page : pages) {
			Page p = Page.wrap(page);
			Section s = p.getSection("język hiszpański");
			Field definitions = s.getField(FieldTypes.DEFINITIONS);
			boolean anyMatch = Stream.of(definitions.getContent().split("\n"))
				.anyMatch(SpanishCanonicalInflectedForms::matchNonInflectedDefinition);
			
			if (anyMatch) {
				forms.add(page.getTitle());
			}
		}

		System.out.printf("Se han extraído %d formas verbales\n", forms.size());
		
		Misc.sortList(forms, "es");
		
		com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(targetpage);
		
		page.setIntro(String.format(
			"Lista zawiera %s. Aktualizacja: ~~~~~.",
			Misc.makePluralPL(forms.size(), "hasła", "haseł")
		));
		
		forms.stream()
			.collect(Collectors.groupingBy(
				SpanishCanonicalInflectedForms::getFirstChar,
				LinkedHashMap::new,
				Collectors.mapping(
					title -> String.format("[[%s]]", title),
					Collectors.joining(" • ")
				)
			))
			.forEach((letter, content) -> {
				com.github.wikibot.parsing.Section section = com.github.wikibot.parsing.Section.create(letter.toString(), 2);
				section.setIntro(content);
				page.appendSections(section);
			});
		
		IOUtils.writeToFile(page.toString(), location + "lista.txt");
		
		if (edit) {
			String pageContent = wb.getPageText(targetpage);
			pageContent = pageContent.substring(0, pageContent.indexOf("-->") + 3);
			page.setIntro(pageContent + "\n" + page.getIntro());
			
			wb.edit(targetpage, page.toString(), "aktualizacja");
		}
	}
	
	private static Character getFirstChar(String title) {
		char letter = title.charAt(0);
		return strippedAccentsMap.getOrDefault(letter, letter);
	}
	
	private static boolean matchNonInflectedDefinition(String line) {
		return !line.startsWith(":") && !line.contains("{{forma ") && !line.contains("{{zbitka");
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			Misc.runTimerWithSelector(new SpanishCanonicalInflectedForms());
		} else {
			Misc.runScheduledSelector(new SpanishCanonicalInflectedForms(), args[0]);
		}
	}
}
