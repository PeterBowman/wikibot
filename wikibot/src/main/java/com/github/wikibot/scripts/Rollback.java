package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class Rollback {
	private static final Wiki wiki = Wiki.newSession("pl.wiktionary.org");
	private static final Path LOCATION = Paths.get("./data/scripts/Rollback/");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	
	public static void main(String[] args) throws IOException, LoginException {
		System.out.print("Username: ");
		Login.login(wiki, Misc.readLine());
		wiki.setThrottle(2000);
		
		var reason = "omijanie blokady";
		
		for (var title : Files.readAllLines(WORKLIST)) {
			var rev = wiki.getTopRevision(title);
			rev.rollback(true, reason);
		}
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
	}
}
