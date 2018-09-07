package com.github.wikibot.scripts.misc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class RomanianDiacritics implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.misc/RomanianDiacritics/";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getLists();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				//edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		String[] words = wb.getCategoryMembers("rumuński (indeks)", 0);		
		PrintWriter pw_a = null, pw_b = null;
		int count_a = 0, count_b = 0;
		
		List<String> list = new ArrayList<>(300);
		
		try {
			pw_a = new PrintWriter(new File(location + "list_a.txt"));
			pw_b = new PrintWriter(new File(location + "list_b.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
			return;
		}
		
		for (String word : words) {
			if (word.contains("ş") || word.contains("ţ")) {
				pw_a.println(++count_a + ". " + word);
				list.add(word);
			} else if (word.contains("ș") || word.contains("ț")) {
				pw_b.println(++count_b + ". " + word);
				list.add(word.replace("ș", "ş").replace("ț", "ţ"));
			}
		}
		
		pw_a.close();
		pw_b.close();
		
		System.out.println("Tamaño de la lista: a - " + count_a + ", b - " + count_b);
		System.out.println("Tamaño de la lista para análisis: " + list.size());
		int count = 0, testcount = 0;
		
		List<String> out = new ArrayList<>();
		PageContainer[] pages = wb.getContentOfPages(list.toArray(new String[list.size()]));
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			String content = page.getText();
			
			if (content.contains("#PATRZ [[")) {
				out.add(++count + ". " + title + " (REDIRECT)");
				return;
			}
			
			boolean has_RO = !ParseUtils.getTemplates("język rumuński", content).isEmpty();
    		boolean has_more_sections = ParseUtils.countOccurrences(content, "==[^\n]+==\n") > 1;
    		
    		if (!has_RO) {
    			out.add(++count + ". " + title + " (OTHER LANGUAGE SECTION)");
        	} else if (has_more_sections) {
        		out.add(++count + ". " + title + " (HAS ROMANIAN AND OTHER SECTION)");
        	} else {
        		out.add(++count + ". " + title + " (HAS ONLY ROMANIAN SECTION)");
        	}
		}
		
		List<String> temp = Stream.of(pages)
			.map(PageContainer::getTitle)
			.collect(Collectors.toList());
		
		for (String title : list) {
			if (!temp.contains(title)) {
				out.add(++count + ". " + title + " (REDIRECT)");
			}
		}
		
		Files.write(Paths.get(location + "work_list.txt"), out);
		
		System.out.println("Analizados: " + testcount);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new RomanianDiacritics());
	}
}
