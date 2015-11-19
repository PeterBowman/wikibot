package com.github.wikibot.scripts.misc;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class FrenchIpa {
	public static void main (String[] args) throws IOException, FailedLoginException {
		PLWikt wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
		
		String[] pages = Stream.of(wb.getCategoryMembers("francuski (indeks)", 0))
			.filter(title -> (
				title.contains("bl") ||
				title.contains("cl") ||
				title.contains("fl") ||
				title.contains("gl") ||
				title.contains("pl") ||
				title.contains("br") ||
				title.contains("cr") ||
				title.contains("dr") ||
				title.contains("fr") ||
				title.contains("gr") ||
				title.contains("pr") ||
				title.contains("tr") ||
				title.contains("vr")
			))
			.toArray(size -> new String[size]);
		
		System.out.printf("Tamaño de la lista: %d%n", pages.length);
		IOUtils.writeToFile(String.join("\n", pages), "./test2.txt");
		
		PageContainer[] contents = wb.getContentOfPages(pages);
		Map<String, String> map = new HashMap<>();
		
		for (PageContainer page : contents) {
			Page p = Page.wrap(page);
			String content = "";
			
			try {
				content = p.getSection("język francuski").getField(FieldTypes.PRONUNCIATION).getContent();
			} catch (NullPointerException e) {
				System.out.printf("NullPointerException: %s%n", page.getTitle());
				continue;
			}
			
			if (!content.isEmpty() && content.contains("{{IPA") && (
				content.contains("b.l") ||
				content.contains("k.l") ||
				content.contains("f.l") ||
				content.contains("g.l") ||
				content.contains("p.l") ||
				content.contains("b.ʁ") ||
				content.contains("k.ʁ") ||
				content.contains("d.ʁ") ||
				content.contains("f.ʁ") ||
				content.contains("g.ʁ") ||
				content.contains("p.ʁ") ||
				content.contains("t.ʁ") ||
				content.contains("v.ʁ")
			)) {
				map.put(page.getTitle(), content);
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		IOUtils.writeToFile(Misc.makeList(map), "./data/scripts.misc/FrenchIpa/list.txt");
		
		Login.saveSession(wb);
	}
}
