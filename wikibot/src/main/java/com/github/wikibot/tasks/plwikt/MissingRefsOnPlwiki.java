package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class MissingRefsOnPlwiki {
	private static final String LOCATION = "./data/tasks.plwikt/MissingRefsOnPlwiki/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/brak Wikisłownika na Wikipedii";
	private static final String PAGE_INTRO;
	
	private static final List<String> TARGET_CATEGORIES = Arrays.asList(
		"Język polski - rzeczowniki",
		"Język polski - skrótowce"
	);
	
	private static final Map<String, List<String>> TARGET_TEMPLATES;
	
	private static PLWikt plwikt;
	private static Wikibot plwiki;
	
	static {
		TARGET_TEMPLATES = new LinkedHashMap<String, List<String>>();
		
		TARGET_TEMPLATES.put("Wikisłownik", null);
		TARGET_TEMPLATES.put("Siostrzane projekty", Arrays.asList("słownik"));
		TARGET_TEMPLATES.put("Język infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Miasto infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Roślina infobox", Arrays.asList("wikisłownik"));		
		TARGET_TEMPLATES.put("Wieś infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Zwierzę infobox", Arrays.asList("wikisłownik"));
		
		final String categories = TARGET_CATEGORIES.stream()
			.map(category -> String.format("* [[:Kategoria:%s]]", category))
			.collect(Collectors.joining("\n"));
		
		final String templates = TARGET_TEMPLATES.entrySet().stream()
			.map(e -> {
				List<String> values = e.getValue();
				
				if (values == null) {
					return String.format("* [[w:Szablon:%s]]", e.getKey());
				} else {
					return String.format("* [[w:Szablon:%s]] (parametry: %s)", e.getKey(), values);
				}
			})
			.collect(Collectors.joining("\n"));
		
		PAGE_INTRO = "Spis polskich haseł w Wikisłowniku oraz artykułów o takiej samej nazwie w polskojęzycznej Wikipedii, " +
			"które nie zawierają szablonu odsyłającego do naszego projektu. " +
			"Przekierowania zastąpiono odnośnikiem do artykułu docelowego tamże. " +
			"Uwzględniono strony zawarte w następujących kategoriach:\n" +
			categories + "\n" +
			"Obsługiwane szablony w Wikipedii:\n" + 
			templates + "\n" +
			"Statystyka:\n" + 
			"* haseł w Wikisłowniku spełniających powyższe kryteria: %1$s\n" +
			"* artykułów w Wikipedii (uwzględniając przekierowania) o takiej samej nazwie: %2$s\n" + 
			"* rozmiar listy: %3$s\n" + 
			"Zobacz też:\n" + 
			"* [[Wikipedysta:Azureus/brak Wikisłownika na Wikipedii]] (do 2010)\n" + 
			"* [[w:Wikipedysta:Nostrix/Wikisłownik]]\n" + 
			"Zmiany wykonane ręcznie na tej stronie zostaną nadpisane przez bota. " +
			"Wygenerowano ~~~~~.\n" + 
			"----\n";
	}

	public static void main(String[] args) throws Exception {
		plwikt = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		plwiki = Login.retrieveSession(Domains.PLWIKI, Users.USER2);
		
		List<String> targets = new ArrayList<String>();
		
		for (String category : TARGET_CATEGORIES) {
			targets.addAll(Arrays.asList(plwikt.getCategoryMembers(category, Wiki.MAIN_NAMESPACE)));
		}
		
		targets = targets.stream().distinct().collect(Collectors.toList());
		
		int titlesOnPlwikt = targets.size();
		System.out.printf("Target plwiktionary entries: %d%n", titlesOnPlwikt);
		
		@SuppressWarnings("rawtypes")
		Map[] pageInfos = plwiki.getPageInfo(targets.toArray(new String[targets.size()]));
		removeMissingArticles(targets, pageInfos);
		
		int titlesOnPlwiki = targets.size();
		System.out.printf("Target plwikipedia entries: %d%n", titlesOnPlwiki);
		
		String[] resolvedRedirs = plwiki.resolveRedirects(targets.toArray(new String[targets.size()]));
		Map<String, String> targetMap = buildTargetMap(targets, resolvedRedirs);
		String[] targetsOnPlwiki = targetMap.values().toArray(new String[targetMap.size()]);
		PageContainer[] pages = plwiki.getContentOfPages(targetsOnPlwiki);
		
		List<String> filteredTargets = Stream.of(pages)
			.filter(p -> isMissingBacklink(p.getText()))
			.map(PageContainer::getTitle)
			.collect(Collectors.toList());
		
		targetMap.values().retainAll(filteredTargets);
		
		int filteredTitles = targetMap.size();
		System.out.printf("Filtered target list: %d%n", filteredTitles);
		
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int) Misc.deserialize(fHash) == targetMap.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(targetMap.hashCode(), fHash);
			String[] arr = targetMap.keySet().toArray(new String[targetMap.size()]);
			Misc.serialize(arr, LOCATION + "stored_titles.ser");
			System.out.printf("%d titles stored.%n", arr.length);
		}
		
		String out = String.format(PAGE_INTRO,
				Misc.makePluralPL(titlesOnPlwikt),
				Misc.makePluralPL(titlesOnPlwiki),
				Misc.makePluralPL(filteredTitles)
			);
		
		out += targetMap.entrySet().stream()
			.map(e -> String.format("#[[%s]] ↔ [[w:%s]]", e.getKey(), e.getValue()))
			.collect(Collectors.joining("\n"));
		
		plwikt.edit(TARGET_PAGE, out, "aktualizacja");
	}
	
	private static void removeMissingArticles(List<String> targets, @SuppressWarnings("rawtypes") Map[] pageInfos) {
		ListIterator<String> lit = targets.listIterator();
		int removed = 0;
		
		while (lit.hasNext()) {
			int index = lit.nextIndex() + removed;
			@SuppressWarnings("rawtypes")
			Map pageInfo = pageInfos[index];
			Boolean exists = (Boolean)pageInfo.get("exists");
			lit.next();
			
			if (!exists) {
				lit.remove();
				removed++;
			}
		}
	}
	
	private static Map<String, String> buildTargetMap(List<String> targets, String[] resolvedRedirs) {
		Collator coll = Misc.getCollator("pl");
		Map<String, String> map = new TreeMap<String, String>(coll);
		
		for (int i = 0; i < targets.size(); i++) {
			String target = targets.get(i);
			String redir = resolvedRedirs[i];
			
			if (redir != null) {
				map.put(target, redir);
			} else {
				String upperCasedTarget = StringUtils.capitalize(target);
				map.put(target, upperCasedTarget);
			}
		}
		
		return map;
	}
	
	private static boolean isMissingBacklink(String text) {
		for (Map.Entry<String, List<String>> e : TARGET_TEMPLATES.entrySet()) {
			String templateName = e.getKey();
			List<String> params = e.getValue();
			List<String> templates = ParseUtils.getTemplatesIgnoreCase(templateName, text);
			
			if (params != null) {
				for (String template : templates) {
					HashMap<String, String> paramMap = ParseUtils.getTemplateParametersWithValue(template);
					
					for (String param : params) {
						if (!paramMap.getOrDefault(param, "").trim().isEmpty()) {
							return false;
						}
					}
				}
			} else if (!templates.isEmpty()) {
				return false;
			}
		}
		
		return true;
	}
}
