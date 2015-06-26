package com.github.wikibot.scripts;

import java.io.IOException;

import javax.security.auth.login.FailedLoginException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.Users;

public final class PurgeCategory {
	public static void main(String[] args) throws IOException, FailedLoginException {
		Wikibot wiki = Login.retrieveSession(Domains.PLWIKT, Users.User1);
		
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
		
		Login.saveSession(wiki);
	}
}
