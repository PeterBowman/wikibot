package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

import com.github.wikibot.main.Wikibot;

public final class UpdateInterwikiPrefixes {
	private static final Path DATA = Paths.get("./data/interwiki.txt");
	
	public static void main(String[] args) throws IOException {
		Wikibot wb = Wikibot.newSession("meta.wikimedia.org");
		String text = wb.getPageText(List.of("MediaWiki:Interwiki config-sorting order-native-languagename-firstword")).get(0).trim();
		
		if (text.isEmpty()) {
			System.out.println("Error: empty page");
			return;
		}
		
		List<String> lines = Files.readAllLines(DATA);
		
		if (String.join("\n", lines).trim().equals(text)) {
			return;
		}
		
		Collection<String> coll = CollectionUtils.disjunction(Arrays.asList(text.split("\n")), lines);
		
		if (!coll.isEmpty()) {
			System.out.printf("Differences: %s%n", coll);
		}
		
		Files.write(DATA, List.of(text));
	}
}
