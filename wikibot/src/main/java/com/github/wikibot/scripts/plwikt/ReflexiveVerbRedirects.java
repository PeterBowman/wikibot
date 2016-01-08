package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

public final class ReflexiveVerbRedirects implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/ReflexiveVerbRedirects/";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getLists();
				Login.saveSession(wb);
				break;
			case '2':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getDuplicates();
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
		PageContainer[] pages = wb.getContentOfCategorymembers("Język polski - czasowniki", PLWikt.MAIN_NAMESPACE, 400);
		List<String> pron = new ArrayList<>();
		
		System.out.printf("Tamaño de la lista total de verbos: %d%n", pages.length);
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			
			if (title.endsWith(" się")) {
				continue;
			}
			
			Page p = Page.wrap(page);
			Section section = p.getSection("język polski");
			
			if (section == null) {
				section = p.getSection("termin obcy w języku polskim");
			}
			
			Field definitions = section.getField(FieldTypes.DEFINITIONS);
			String content = definitions.getContent();
			String reflexive = title + " się"; 
			
			if (content.contains(reflexive)) {
				pron.add(reflexive);
			}
		}

		System.out.printf("Tamaño de la lista de pronominales: %d%n", pron.size());
		
		@SuppressWarnings("rawtypes")
		Map[] infos = wb.getPageInfo(pron.toArray(new String[pron.size()]));
		
		List<String> missing = Stream.of(infos)
			.filter(Objects::nonNull)
			.filter(info -> !(boolean)info.get("exists"))
			.map(info -> (String)info.get("displaytitle"))
			.collect(Collectors.toList());
		
		System.out.printf("Tamaño de la lista de faltantes: %d%n", missing.size());
		
		IOUtils.writeToFile(String.join("\n", missing), location + "worklist.txt");
		Misc.serialize(missing, location + "missing.ser");
	}
	
	public static void getDuplicates() throws IOException {
		String[] titles = wb.getCategoryMembers("Język polski - czasowniki", PLWikt.MAIN_NAMESPACE);
		List<String> verbs = Arrays.asList(titles);
		
		List<String> duplicates = Stream.of(titles)
			.filter(title -> !title.endsWith(" się"))
			.filter(title -> verbs.contains(title + " się"))
			.collect(Collectors.toList());
		
		System.out.printf("Tamaño de la lista de duplicados: %d%n", duplicates.size());
		IOUtils.writeToFile(String.join("\n", duplicates), location + "duplicates.txt");
	}
	
	public static void edit() throws LoginException, IOException, ClassNotFoundException {
		File f = new File(location + "missing.ser");
		List<String> list = Misc.deserialize(f);
		
		System.out.printf("Tamaño de la lista extraída: %d%n", list.size());
		
		if (list.isEmpty()) {
			return;
		}
		
		wb.setThrottle(2000);
		
		for (String page : list) {
			String target = page.replace(" się", "");
			String content = String.format("#PATRZ[[%s]]", target);
			String summary = String.format("przekierowanie do [[%s]]", target);
			
			wb.edit(page, content, summary, false, true, -2, null);
		}
		
		f.delete();
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new ReflexiveVerbRedirects());
	}
}
