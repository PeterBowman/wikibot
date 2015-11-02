package com.github.wikibot.scripts;

import java.io.File;
import java.io.IOException;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Users;

public final class Rollback {
	private static Wikibot wb;
	private static final Domains domain = Domains.PLWIKT;
	private static final String location = "./data/scripts/Rollback/";
	private static final String worklist = location + "worklist.txt";
	
	public static void main(String[] args) throws IOException, LoginException {
		wb = Login.retrieveSession(domain, Users.USER1);
		wb.setThrottle(2000);
		
		String[] titles = IOUtils.loadFromFile(worklist, "", "UTF8");
		String reason = "omijanie blokady";
		
		for (String title : titles) {
			Revision rev = wb.getTopRevision(title);
			rev.rollback(true, reason);
		}
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		(new File (worklist)).renameTo(f);
		(new File(worklist)).createNewFile();
		
		Login.saveSession(wb);
	}
}
