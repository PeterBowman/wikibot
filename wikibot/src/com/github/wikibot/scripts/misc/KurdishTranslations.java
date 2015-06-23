package com.github.wikibot.scripts.misc;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class KurdishTranslations implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.misc/KurdishTranslations/";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = new PLWikt();
				Login.login(wb, false);
				getList();
				wb.logout();
				break;
			case 'e':
				wb = new PLWikt();
				Login.login(wb, true);
				edit();
				wb.logout();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers("polski (indeks)", PLWikt.MAIN_NAMESPACE);
		Pattern patt = Pattern.compile("\\* ?kurdyjski:? ?[^\\n]*");
		PrintWriter pw = new PrintWriter(new File(location + "worklist.txt"));
		int found = 0;
		int errors = 0;
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			String translationsText = p.getPolishSection().getField(FieldTypes.TRANSLATIONS).getContent();
			
			if (translationsText.contains("kurdyjski")) {
				Matcher m = patt.matcher(translationsText);
				
				if (m.find()) {
					pw.println("# [[" + title + "]] <nowiki>" + m.group(0) + "</nowiki>");
				} else {
					pw.println("# [[" + title + "]]");
					errors++;
				}
				
				found++;
			}
		}
		
		pw.close();
		System.out.println("Encontrados: " + found + ", errores: " + errors);
	}
	
	public static void edit() throws IOException, LoginException {
		final String page = "Wikipedysta:PBbot/tłumaczenia na kurdyjski";
		List<String> lines = Files.readAllLines(Paths.get(location + "worklist.txt"), StandardCharsets.UTF_8);
		String text = String.join("\n", lines);
		
		wb.edit(page, text, "tworzenie listy", false, false, -2, null);
	}

	public static void main(String[] args) {
		Misc.runTimerWithSelector(new KurdishTranslations());
	}
}
