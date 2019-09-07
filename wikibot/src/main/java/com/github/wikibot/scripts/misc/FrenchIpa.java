package com.github.wikibot.scripts.misc;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public class FrenchIpa {
	public static void main (String[] args) throws Exception {
		Wikibot wb = Login.createSession("pl.wiktionary.org");
		
		List<String> pages = wb.getCategoryMembers("francuski (indeks)", Wiki.MAIN_NAMESPACE).stream()
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
			.collect(Collectors.toList());
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		Files.write(Paths.get("./test2.txt"), pages);
		
		List<PageContainer> contents = wb.getContentOfPages(pages);
		Map<String, String> map = new HashMap<>();
		
		for (PageContainer page : contents) {
			String content = Optional.of(Page.wrap(page))
				.flatMap(p -> p.getSection("język francuski"))
				.flatMap(s -> s.getField(FieldTypes.PRONUNCIATION))
				.map(Field::getContent)
				.orElse("");
			
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
		Files.write(Paths.get("./data/scripts.misc/FrenchIpa/list.txt"), List.of(Misc.makeList(map)));
	}
}
