package com.github.wikibot.scripts;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class Replace implements Selectorizable {
	private static Wikibot wb;
	private static final Domains domain = Domains.PLWIKT;
	private static final String location = "./data/scripts/replace/";
	private static final String f_titles = location + "titles.txt";
	private static final String f_worklist = location + "worklist.txt";
	private static final String f_target = location + "target.ser";
	private static final String f_replacement = location + "replacement.ser";
	private static final String f_info = location + "info.ser";
	private static final String summaryFormat;
	
	static {
		if (domain.getDomain().startsWith("pl.")) {
			summaryFormat = "„%s” → „%s”"; 
		} else {
			summaryFormat = "«%s» → «%s»";
		}
	}

	public void selector(char op) throws Exception {
		switch (op) {
			case 'd':
				wb = Login.retrieveSession(domain, Users.USER1);
				getDiffs();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(domain, Users.USER2);
				edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public void getDiffs() throws FileNotFoundException, IOException, ClassNotFoundException {
		String target = "prettytable";
		String replacement = "wikitable";
		String[] titles = Files.lines(Paths.get(f_titles)).toArray(String[]::new);
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		if (titles.length == 0) {
			return;
		}
		
		PageContainer[] pages = wb.getContentOfPages(titles);
		
		Map<String, String> map = Stream.of(pages)
			.filter(page -> page.getText().contains(target))
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				page -> replace(page.getText(), target, replacement)
			));
		
		Files.write(Paths.get(f_worklist), Arrays.asList(Misc.makeList(map)));
		
		System.out.printf("Tamaño final: %d%n", map.size());
		
		if (map.size() != pages.length) {
			List<String> all = Stream.of(pages).map(PageContainer::getTitle).collect(Collectors.toList());
			Set<String> found = map.keySet();
			all.removeAll(found);
			
			System.out.printf("No se ha encontrado la secuencia deseada en %d entradas: %s%n", found.size(), found.toString());
		}
		
		Misc.serialize(target, f_target);
		Misc.serialize(replacement, f_replacement);
		
		Map<String, OffsetDateTime> timestamps = Stream.of(pages)
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Misc.serialize(timestamps, f_info);
	}
	
	public void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		String target = Misc.deserialize(f_target);
		String replacement = Misc.deserialize(f_replacement);
		Map<String, String> map = Misc.readList(Files.lines(Paths.get(f_worklist)).toArray(String[]::new));
		Map<String, OffsetDateTime> timestamps = Misc.deserialize(f_info);
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		wb.setThrottle(3000);
		List<String> errors = new ArrayList<>();
		
		String summary = String.format(summaryFormat, target, replacement);
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
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		(new File(f_worklist)).renameTo(f);
	}
	
	private String replace(String s, String oldstring, String newstring) {
		return Pattern.compile(oldstring, Pattern.LITERAL).matcher(s).replaceAll(newstring);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new Replace());
	}
}
