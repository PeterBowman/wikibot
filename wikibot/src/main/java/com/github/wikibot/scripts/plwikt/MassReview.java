package com.github.wikibot.scripts.plwikt;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.wikipedia.Wiki.Revision;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public final class MassReview {
	private static final Path LOCATION = Paths.get("./data/scripts.plwikt/MassReview/");
	private static final Path DATA = LOCATION.resolve("data.tsv");
	private static final Path LAST = LOCATION.resolve("last.txt");
	private static final Path ERRORS = LOCATION.resolve("errors.txt");
	
	public static void main(String[] args) throws Exception {
		System.out.print("Username: ");
		Wikibot wb = Login.createSession("pl.wiktionary.org", Misc.readLine());
		wb.setThrottle(5000);
		
		List<String[]> list = extractList();
		String lastReviewed = findLastModifiedEntry(list);
		
		// [[Special:PermaLink/revid#Anchor]]
		String summary = (args.length > 0) ? args[0] : "";
		System.out.printf("Summary: %s%n", summary);
		
		ListIterator<String[]> iterator = list.listIterator();
		List<String> errors = new ArrayList<>();
		
		while (iterator.hasNext()) {
			String[] arr = iterator.next();
			String title = arr[0];
			long revid = Long.parseLong(arr[2]);
			
			try {
				Revision rev = wb.getTopRevision(title);
				
				if (rev == null || rev.getID() != revid) {
					errors.add(title);
					continue;
				}
				
				wb.review(rev, summary);
				lastReviewed = title;
			} catch (IOException | UncheckedIOException e1) {
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
		System.out.printf("Last reviewed: %s%n", lastReviewed);
		
		Files.write(ERRORS, errors);
		Files.write(LAST, List.of(lastReviewed));
		
		wb.logout();
	}

	private static List<String[]> extractList() throws IOException, UnsupportedEncodingException, FileNotFoundException {
		TsvParserSettings settings = new TsvParserSettings();
		settings.setHeaderExtractionEnabled(true);
		TsvParser parser = new TsvParser(settings);
		
		List<String[]> list;
		
		try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(DATA.toFile()), StandardCharsets.UTF_8))) {
			list = parser.parseAll(reader);
		}
		
		System.out.printf("Total elements: %d%n", list.size());
		return list;
	}

	private static String findLastModifiedEntry(List<String[]> list) throws IOException {
		String lastReviewed = "";
		
		if (LAST.toFile().exists()) {
			List<String> lines = Files.readAllLines(LAST);
			
			if (!lines.isEmpty()) {
				String lastEntry = lines.get(0);
				int index = list.stream()
					.filter(arr -> arr[0].equals(lastEntry))
					.findFirst()
					.map(list::indexOf)
					.get();
				lastReviewed = list.get(index)[0];
				list.removeAll(list.subList(0, index + 1));
			}
		}
		
		System.out.printf("Remaining: %d%n", list.size());
		return lastReviewed;
	}
}
