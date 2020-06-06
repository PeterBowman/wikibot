package com.github.wikibot.scripts;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class Replace implements Selectorizable {
	private static Wikibot wb;
	private static final String DOMAIN = "pl.wiktionary.org";
	private static final Path LOCATION = Paths.get("./data/scripts/replace/");
	private static final Path TITLES = LOCATION.resolve("titles.txt");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path TARGET = LOCATION.resolve("target.ser");
	private static final Path REPLACEMENT = LOCATION.resolve("replacement.ser");
	private static final Path INFO = LOCATION.resolve("info.ser");
	private static final String SUMMARY_FORMAT;
	
	static {
		if (DOMAIN.startsWith("pl.")) {
			SUMMARY_FORMAT = "„%s” → „%s”"; 
		} else {
			SUMMARY_FORMAT = "«%s» → «%s»";
		}
	}

	public void selector(char op) throws Exception {
		switch (op) {
			case 'd':
				wb = Login.createSession(DOMAIN);
				getDiffs();
				break;
			case 'e':
				wb = Login.createSession(DOMAIN);
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public void getDiffs() throws FileNotFoundException, IOException, ClassNotFoundException {
		String target = "prettytable";
		String replacement = "wikitable";
		List<String> titles = Files.lines(TITLES).collect(Collectors.toList());
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", titles.size());
		
		if (titles.isEmpty()) {
			return;
		}
		
		List<PageContainer> pages = wb.getContentOfPages(titles);
		
		Map<String, String> map = pages.stream()
			.filter(page -> page.getText().contains(target))
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				page -> replace(page.getText(), target, replacement)
			));
		
		Files.write(WORKLIST, List.of(Misc.makeList(map)));
		
		System.out.printf("Tamaño final: %d%n", map.size());
		
		if (map.size() != pages.size()) {
			List<String> all = pages.stream().map(PageContainer::getTitle).collect(Collectors.toList());
			Set<String> found = map.keySet();
			all.removeAll(found);
			
			System.out.printf("No se ha encontrado la secuencia deseada en %d entradas: %s%n", found.size(), found.toString());
		}
		
		Misc.serialize(target, TARGET);
		Misc.serialize(replacement, REPLACEMENT);
		
		Map<String, OffsetDateTime> timestamps = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Misc.serialize(timestamps, INFO);
	}
	
	public void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		String target = Misc.deserialize(TARGET);
		String replacement = Misc.deserialize(REPLACEMENT);
		Map<String, String> map = Misc.readList(Files.lines(WORKLIST).toArray(String[]::new));
		Map<String, OffsetDateTime> timestamps = Misc.deserialize(INFO);
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		wb.setThrottle(3000);
		List<String> errors = new ArrayList<>();
		
		String summary = String.format(SUMMARY_FORMAT, target, replacement);
		//String summary = "usunięcie znaków soft hyphen";
		
		for (Entry<String, String> entry : map.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			OffsetDateTime timestamp = timestamps.get(title);
			
			try {
				wb.edit(title, text, summary, true, true, -2, timestamp);
			} catch (Exception e) {
				System.out.printf("Error en: %s%n", title);
				errors.add(title);
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errores en: %s%n", errors.size(), errors.toString());
		}
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
	}
	
	private String replace(String s, String oldstring, String newstring) {
		return Pattern.compile(oldstring, Pattern.LITERAL).matcher(s).replaceAll(newstring);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new Replace());
	}
}
