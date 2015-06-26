package com.github.wikibot.scripts;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

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
				wb = Login.retrieveSession(domain, Users.User1);
				getDiffs();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(domain, Users.User2);
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
		String[] titles = IOUtils.loadFromFile(f_titles, "", "UTF8");
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		if (titles.length == 0) {
			return;
		}
		
		PageContainer[] pages = wb.getContentOfPages(titles, 400);
		
		Map<String, String> map = Stream.of(pages)
			.filter(page -> page.getText().contains(target))
			.collect(Collectors.toMap(
				page -> page.getTitle(),
				page -> replace(page.getText(), target, replacement)
			));
		
		IOUtils.writeToFile(Misc.makeList(map), f_worklist);
		
		System.out.printf("Tamaño final: %d%n", map.size());
		
		if (map.size() != pages.length) {
			List<String> all = Stream.of(pages).map(page -> page.getTitle()).collect(Collectors.toList());
			Set<String> found = map.keySet();
			all.removeAll(found);
			
			System.out.printf("No se ha encontrado la secuencia deseada en %d entradas: %s%n", found.size(), found.toString());
		}
		
		Misc.serialize(target, f_target);
		Misc.serialize(replacement, f_replacement);
		Misc.serialize(wb.getTimestamps(pages), f_info);
	}
	
	public void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		String target = Misc.deserialize(f_target);
		String replacement = Misc.deserialize(f_replacement);
		String[] lines = IOUtils.loadFromFile(f_worklist, "", "UTF8");
		Map<String, String> map = Misc.readList(lines);
		Map<String, Calendar> timestamps = Misc.deserialize(f_info);
		
		System.out.printf("Título: %s%n", target);
		System.out.printf("Sustitución por: %s%n", replacement);
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		wb.setThrottle(3000);
		List<String> conflicts = new ArrayList<String>();
		
		String summary = String.format(summaryFormat, target, replacement);
		//String summary = "usunięcie znaków soft hyphen";
		
		for (Entry<String, String> entry : map.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			Calendar timestamp = timestamps.get(title);
			
			try {
				wb.edit(title, text, summary, true, true, -2, timestamp);
			} catch (Exception e) {
				System.out.printf("Error en: %s%n", title);
				conflicts.add(title);
			}
		}
		
		if (!conflicts.isEmpty()) {
			System.out.printf("%d conflictos en: %s%n", conflicts.size(), conflicts.toString());
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
