package com.github.wikibot.scripts;

import java.io.IOException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.Wikibot;

public final class UpdateInterwikiPrefixes {
	public static void main(String[] args) throws IOException {
		Wikibot wb = new Wikibot("meta.wikimedia.org");
		String text = wb.getPageText("MediaWiki:Interwiki config-sorting order-native-languagename-firstword");
		IOUtils.writeToFile(text, "./data/interwiki.txt");
	}
}
