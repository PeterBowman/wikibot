package com.github.wikibot.scripts;

import java.io.IOException;

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
		
		String[] pages = isTranscluded
			? wiki.whatLinksHere("Kategoria:" + category, Wikibot.MAIN_NAMESPACE)
			: wiki.getCategoryMembers(category, Wikibot.MAIN_NAMESPACE);
		
		//String[] pages = wb.whatTranscludesHere(page);
		
		System.out.printf("Se han extraído %d páginas%n", pages.length);
				
		Misc.runTimer(() -> {
			try {
				wiki.purge(true, pages);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
}
