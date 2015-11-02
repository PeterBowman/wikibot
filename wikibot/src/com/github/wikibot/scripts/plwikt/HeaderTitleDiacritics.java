package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.IOUtils;
import org.xml.sax.SAXException;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.Users;

public final class HeaderTitleDiacritics {
	private static final String LOCATION = "./data/scripts.plwikt/HeaderTitleDiacritics/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/nagłówki";
	private static final int COLUMN_ELEMENT_THRESHOLD = 50;
	private static final int NUMBER_OF_COLUMNS = 3;
	
	public static void main(String[] args) throws IOException, LoginException, SAXException {
		Collator collator = Misc.getCollator("pl");
		Map<String, Collection<String[]>> map = Collections.synchronizedMap(new TreeMap<>(collator::compare));
		PLWikt wb = Login.retrieveSession(Domains.PLWIKT, Users.User2);
		
		wb.readXmlDump(pc -> {
			Page.wrap(pc).getAllSections().stream()
				.filter(HeaderTitleDiacritics::filterSections)
				.forEach(section -> {
					String lang = section.getLangShort();
					String[] arr = {pc.getTitle(), section.getHeaderTitle().replace("&#", "&amp;#")};
					Collection<String[]> coll = map.getOrDefault(lang, new ArrayList<>(100));
					coll.add(arr);
					map.putIfAbsent(lang, coll);
				});
		});
		
		List<String> values = map.values().stream()
			.flatMap(coll -> coll.stream().map(value -> value[0]))
			.collect(Collectors.toList());
		
		int total = values.size();
		int unique = (int) values.stream().distinct().count();
		
		System.out.printf("Found: %d (%d unique)%n", total, unique);
		
		com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(TARGET_PAGE);
		
		page.setIntro(String.format(
			"Znaleziono %s (%s). Aktualizacja: ~~~~~.{{TOCright}}",
			Misc.makePluralPL(total, "hasła", "haseł"),
			Misc.makePluralPL(unique, "jednakowe", "jednakowych")
		));
		
		com.github.wikibot.parsing.Section[] sections = map.entrySet().stream()
			.map(entry -> {
				boolean useColumns = entry.getValue().size() > COLUMN_ELEMENT_THRESHOLD;
				String header = String.format("%s (%d)", entry.getKey(), entry.getValue().size());
				
				com.github.wikibot.parsing.Section section =
					com.github.wikibot.parsing.Section.create(header, 2);
				
				StringBuilder sb = new StringBuilder(200);
				sb.append(String.format("{{język linków|%s}}", entry.getKey()));
				
				if (useColumns) {
					sb.append(String.format("{{columns|liczba=%d|", NUMBER_OF_COLUMNS));
				}
				
				sb.append("\n");
				
				sb.append(entry.getValue().stream()
					.map(value -> String.format("# [[%s]]: %s", value[0], value[1]))
					.collect(Collectors.joining("\n"))
				);
				
				sb.append("\n");
				
				if (useColumns) {
					sb.append("}}");
				}
				
				sb.append("{{język linków}}");
				section.setIntro(sb.toString());
				
				return section;
			})
			.toArray(com.github.wikibot.parsing.Section[]::new);
		
		page.appendSections(sections);

		IOUtils.writeToFile(page.toString(), LOCATION + "list.txt");
		
		wb.setMarkBot(false);
		wb.edit(page.getTitle(), page.toString(), "aktualizacja");
	}
	
	private static boolean filterSections(Section section) {
		String headerTitle = section.getHeaderTitle();
		
		if (StringUtils.containsAny(headerTitle, '{', '}', '[', ']')) {
			return false;
		} else {
			String pageTitle = section.getContainingPage().getTitle();
			pageTitle = pageTitle.replace("ʼ", "'").replace("…", "...");
			headerTitle = headerTitle.replace("ʼ", "'").replace("…", "...");
			return !pageTitle.equals(headerTitle);
		}
	}
}
