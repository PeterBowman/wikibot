package com.github.wikibot.scripts;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import org.apache.commons.collections4.CollectionUtils;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.Wikibot;

public final class UpdateInterwikiPrefixes {
	private static final String DATA = "./data/interwiki.txt";
	
	public static void main(String[] args) throws IOException {
		Wikibot wb = new Wikibot("meta.wikimedia.org");
		String text = wb.getPageText("MediaWiki:Interwiki config-sorting order-native-languagename-firstword").trim();
		
		if (text.isEmpty()) {
			System.out.println("Error: empty page");
			return;
		}
		
		String[] lines = IOUtils.loadFromFile(DATA, "", "UTF8");
		
		if (String.join("\n", lines).trim().equals(text)) {
			return;
		}
		
		Collection<String> coll = CollectionUtils.disjunction(Arrays.asList(text.split("\n")), Arrays.asList(lines));
		
		if (!coll.isEmpty()) {
			System.out.printf("Differences: %s%n", coll);
		}
		
		IOUtils.writeToFile(text, DATA);
	}
}
