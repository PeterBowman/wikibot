package com.github.wikibot.scripts;

import java.io.IOException;
import java.util.List;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class PurgeCategory {
	public static void main(String[] args) throws Exception {
		Wikibot wiki = Login.createSession("pl.wiktionary.org");
		
		//String category = "Język nowogrecki - przymiotniki";
		String category = "Język rosyjski – gwara pomorska";
		//String page = "Szablon:*";
		boolean isTranscluded = false;
		
		List<String> pages = isTranscluded
			? wiki.whatLinksHere(List.of("Kategoria:" + category), false, false, Wikibot.MAIN_NAMESPACE).get(0)
			: wiki.getCategoryMembers(category, Wikibot.MAIN_NAMESPACE);
		
		//String[] pages = wb.whatTranscludesHere(page);
		
		System.out.printf("Se han extraído %d páginas%n", pages.size());
				
		Misc.runTimer(() -> {
			try {
				wiki.purge(true, pages.toArray(String[]::new));
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
}
