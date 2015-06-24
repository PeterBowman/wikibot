package com.github.wikibot.main;

import java.io.IOException;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.parsing.eswikt.LangSection;
import com.github.wikibot.parsing.eswikt.Page;
import com.github.wikibot.parsing.eswikt.Section;

public final class Test {
	public static void main(String[] args) throws IOException, LoginException {
		String text = String.join("\n", IOUtils.loadFromFile("./data/eswikt.txt", "", "UTF8"));
		Page page = Page.store("teto", text);
		
		for (Section section : page.getAllSections()) {
			if (section instanceof LangSection) {
				System.out.println("test");
			}
			System.out.println(section.getClass().getName());
		}
	}
}
