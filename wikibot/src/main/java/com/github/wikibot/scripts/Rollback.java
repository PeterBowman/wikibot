package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class Rollback {
	private static Wikibot wb;
	private static final String DOMAIN = "pl.wiktionary.org";
	private static final Path LOCATION = Paths.get("./data/scripts/Rollback/");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	
	public static void main(String[] args) throws IOException, LoginException {
		System.out.print("Username: ");
		wb = Login.createSession(DOMAIN, Misc.readLine());
		wb.setThrottle(2000);
		
		String reason = "omijanie blokady";
		
		for (String title : Files.readAllLines(WORKLIST)) {
			Revision rev = wb.getTopRevision(title);
			rev.rollback(true, reason);
		}
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
	}
}
