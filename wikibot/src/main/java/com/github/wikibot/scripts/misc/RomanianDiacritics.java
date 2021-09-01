package com.github.wikibot.scripts.misc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class RomanianDiacritics implements Selectorizable {
	private static Wikibot wb;
	private static final Path LOCATION = Paths.get("./data/scripts.misc/RomanianDiacritics/");

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getLists();
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				//edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		List<String> words = wb.getCategoryMembers("rumuński (indeks)", Wiki.MAIN_NAMESPACE);		
		PrintWriter pw_a = null, pw_b = null;
		int count_a = 0, count_b = 0;
		
		List<String> list = new ArrayList<>(300);
		
		try {
			pw_a = new PrintWriter(LOCATION.resolve("list_a.txt").toFile());
			pw_b = new PrintWriter(LOCATION.resolve("list_b.txt").toFile());
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
		List<PageContainer> pages = wb.getContentOfPages(list);
		
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
		
		List<String> temp = pages.stream()
			.map(PageContainer::getTitle)
			.toList();
		
		for (String title : list) {
			if (!temp.contains(title)) {
				out.add(++count + ". " + title + " (REDIRECT)");
			}
		}
		
		Files.write(LOCATION.resolve("work_list.txt"), out);
		
		System.out.println("Analizados: " + testcount);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new RomanianDiacritics());
	}
}
