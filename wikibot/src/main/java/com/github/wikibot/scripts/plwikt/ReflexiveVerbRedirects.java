package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class ReflexiveVerbRedirects implements Selectorizable {
	private static Wikibot wb;
	private static final Path LOCATION = Paths.get("./data/scripts.plwikt/ReflexiveVerbRedirects/");

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getLists();
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				getDuplicates();
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		List<PageContainer> pages = wb.getContentOfCategorymembers("Język polski - czasowniki", Wiki.MAIN_NAMESPACE);
		List<String> pron = new ArrayList<>();
		
		System.out.printf("Tamaño de la lista total de verbos: %d%n", pages.size());
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			
			if (title.endsWith(" się")) {
				continue;
			}
			
			String content = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.DEFINITIONS))
				.map(Field::getContent)
				.orElse("");
			
			String reflexive = title + " się"; 
			
			if (content.contains(reflexive)) {
				pron.add(reflexive);
			}
		}

		System.out.printf("Tamaño de la lista de pronominales: %d%n", pron.size());
		
		List<Map<String, Object>> infos = wb.getPageInfo(pron);
		
		List<String> missing = infos.stream()
			.filter(Objects::nonNull)
			.filter(info -> !(boolean)info.get("exists"))
			.map(info -> (String)info.get("displaytitle"))
			.collect(Collectors.toList());
		
		System.out.printf("Tamaño de la lista de faltantes: %d%n", missing.size());
		
		Files.write(LOCATION.resolve("worklist.txt"), missing);
		Misc.serialize(missing, LOCATION.resolve("missing.ser"));
	}
	
	public static void getDuplicates() throws IOException {
		List<String> titles = wb.getCategoryMembers("Język polski - czasowniki", Wiki.MAIN_NAMESPACE);
		
		List<String> duplicates = titles.stream()
			.filter(title -> !title.endsWith(" się"))
			.filter(title -> titles.contains(title + " się"))
			.collect(Collectors.toList());
		
		System.out.printf("Tamaño de la lista de duplicados: %d%n", duplicates.size());
		Files.write(LOCATION.resolve("duplicates.txt"), duplicates);
	}
	
	public static void edit() throws LoginException, IOException, ClassNotFoundException {
		File f = LOCATION.resolve("missing.ser").toFile();
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
