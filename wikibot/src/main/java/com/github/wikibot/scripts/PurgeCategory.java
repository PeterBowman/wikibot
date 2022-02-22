package com.github.wikibot.scripts;

import java.util.List;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class PurgeCategory {
	public static void main(String[] args) throws Exception {
		Wiki wiki = Wiki.newSession("pl.wiktionary.org");
		Login.login(wiki);
		
		//String category = "Język nowogrecki - przymiotniki";
		String category = "Język rosyjski – gwara pomorska";
		//String page = "Szablon:*";
		boolean isTranscluded = false;
		
		List<String> pages = isTranscluded
			? wiki.whatLinksHere(List.of("Kategoria:" + category), false, false, Wikibot.MAIN_NAMESPACE).get(0)
			: wiki.getCategoryMembers(category, Wikibot.MAIN_NAMESPACE);
		
		//String[] pages = wb.whatTranscludesHere(page);
		
		System.out.printf("Se han extraído %d páginas%n", pages.size());
				
		wiki.purge(true, pages.toArray(String[]::new));
	}
}
