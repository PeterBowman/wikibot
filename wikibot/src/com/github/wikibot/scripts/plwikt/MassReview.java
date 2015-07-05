package com.github.wikibot.scripts.plwikt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import javax.security.auth.login.FailedLoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Users;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public final class MassReview {
	private static final String LOCATION = "./data/scripts.plwikt/MassReview/";
	private static final String F_DATA = LOCATION + "data.tsv";
	private static final String F_LAST = LOCATION + "last.txt";
	private static final String F_ERRORS = LOCATION + "errors.txt";
	
	public static void main(String[] args) throws FailedLoginException, IOException {
		PLWikt wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
		
		TsvParserSettings settings = new TsvParserSettings();
		settings.setHeaderExtractionEnabled(true);
		TsvParser parser = new TsvParser(settings);
		
		List<String[]> list;
		
		try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(F_DATA), "UTF-8"))) {
			list = parser.parseAll(reader);
		}
		
		System.out.printf("Total elements: %d%n", list.size());
		
		File f = new File(F_LAST);
		String lastReviewed = "";
		
		if (f.exists()) {
			String lastEntry = IOUtils.loadFromFile(F_LAST, "", "UTF8")[0];
			List<String[]> temp = new ArrayList<String[]>(list);
			int index = list.stream()
				.filter(arr -> arr[0].equals(lastEntry))
				.findFirst()
				.map(temp::indexOf)
				.get();
			lastReviewed = list.get(index)[0];
			list = list.subList(index + 1, list.size());
		}
		
		System.out.printf("Remaining: %d%n", list.size());
		
		ListIterator<String[]> iterator = list.listIterator();
		List<String> errors = new ArrayList<String>();
		String comment = "[[Specjalna:Niezmienny link/4711423#Masowe oznaczanie importu]]";
		wb.setThrottle(5000);
		
		while (iterator.hasNext()) {
			String[] arr = iterator.next();
			String title = arr[0];
			long revid = Long.parseLong(arr[2]);
			
			try {
				Revision rev = wb.getTopRevision(title);
				
				if (rev == null || rev.getRevid() != revid) {
					errors.add(title);
					continue;
				}
				
				wb.review(rev, comment);
				lastReviewed = title;
			} catch (IOException e1) {
				System.out.println(e1.getMessage());
				e1.printStackTrace();
				iterator.previous();
				
				try {
					Thread.sleep(10 * 60 * 1000);
				} catch (InterruptedException e) {}
			} catch (Exception e2) {
				System.out.println(e2.getMessage());
				e2.printStackTrace();
				break;
			}
		}
		
		System.out.printf("%d errors: %s%n", errors.size(), errors);
		IOUtils.writeToFile(String.join("\n", errors), F_ERRORS);
		IOUtils.writeToFile(lastReviewed, F_LAST);
		
		wb.logout();
	}
}
