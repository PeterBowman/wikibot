package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.plural4j.Plural;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public class DevanagariIAST implements Selectorizable {
	private static Wikibot wb;
	private static final Plural pluralPL;
	private static final LocalizedNumberFormatter numberFormatPL;
	private static final String location = "./data/scripts.plwikt/DevanagariIAST/";
	private static final String fList = location + "lista.txt";
	private static final String wikipage = "Wikipedysta:PBbot/dewanagari bez transliteracji";

	static {
		pluralPL = new Plural(PluralRules.POLISH, "hasło,hasła,haseł");
		numberFormatPL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
	}
	
	@Override
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				getList(op == '2');
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList(boolean edit) throws IOException, LoginException {
		String[] titles = wb.listPages("", null, Wiki.MAIN_NAMESPACE, "अ", "ॿ", Boolean.FALSE);
		PageContainer[] pages = wb.getContentOfPages(titles);
		List<String> hindi = new ArrayList<>();
		List<String> nonHindi = new ArrayList<>();
		
		for (PageContainer page : pages) {
			Page p = Page.wrap(page);
			
			for (Section s : p.getAllSections()) {
				Field f = s.getField(FieldTypes.TRANSLITERATION).orElse(null);
				
				if (f == null || !f.getContent().contains("{{IAST|")) {
					if (s.getLangShort().equals("hindi")) {
						hindi.add(page.getTitle());
					} else {
						nonHindi.add(page.getTitle());
					}
				}
			}
		}
		
		System.out.printf("Total: %d, hindi: %d, non-hindi: %d%n", pages.length, hindi.size(), nonHindi.size());
		
		String out = makePage(hindi, nonHindi);
		Files.write(Paths.get(fList), Arrays.asList(out));
		
		if (edit) {
			wb.edit(wikipage, out, "aktualizacja", false, false, -2, null);
		}
	}
	
	private static String makePage(List<String> hindi, List<String> nonHindi) {
		StringBuilder sb = new StringBuilder(11000);
		
		sb.append("Hasła w alfabecie dewanagari niekorzystające z szablonu {{s|IAST}} w polu '''transliteracja'''.");
		sb.append(" ");
		sb.append(String.format(
			"Hindi: %d %s, inne: %d.",
			numberFormatPL.format(hindi.size()),
			pluralPL.pl(hindi.size(), "hasło"),
			nonHindi.size()
		));
		sb.append(" ");
		sb.append("Aktualizacja: ~~~~~.");
		sb.append("\n__TOC__\n== hindi ==\n{{język linków|hindi}}\n");
		sb.append(hindi.stream().map(title -> String.format("[[%s]]", title)).collect(Collectors.joining(", ")));
		sb.append("\n\n== inne ==\n{{język linków}}\n");
		sb.append(nonHindi.stream().map(title -> String.format("[[%s]]", title)).collect(Collectors.joining(", ")));
		
		return sb.toString();
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new DevanagariIAST());
	}
}
