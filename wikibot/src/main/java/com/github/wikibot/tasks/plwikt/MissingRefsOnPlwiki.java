package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class MissingRefsOnPlwiki {
	private static final String LOCATION = "./data/tasks.plwikt/MissingRefsOnPlwiki/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/brak Wikisłownika na Wikipedii";
	private static final String PAGE_INTRO;
	
	private static final Map<String, List<String>> TARGET_TEMPLATES;
	
	private static PLWikt plwikt;
	private static Wikibot plwiki;
	
	static {
		TARGET_TEMPLATES = new LinkedHashMap<String, List<String>>();
		
		TARGET_TEMPLATES.put("Wikisłownik", null);
		TARGET_TEMPLATES.put("Siostrzane projekty", Arrays.asList("słownik"));
		//TARGET_TEMPLATES.put("Język infobox", Arrays.asList("wikisłownik")); // links back to categories only
		TARGET_TEMPLATES.put("Miasto infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Roślina infobox", Arrays.asList("wikisłownik"));		
		TARGET_TEMPLATES.put("Wieś infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Zwierzę infobox", Arrays.asList("wikisłownik"));
		
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
		
		PAGE_INTRO = "Spis polskich haseł w Wikisłowniku transkludujących szablon {{s|wikipedia}}, " +
			"których docelowy artykuł (po rozwiązaniu przekierowań) w Wikipedii bądź nie istnieje, " +
			"bądź nie linkuje z powrotem do tego samego hasła u nas. " +
			"Obsługiwane szablony w Wikipedii:\n" + 
			templates + "\n" +
			"Statystyka:\n" + 
			"* transkluzji {{s|wikipedia}} (w sumie): %1$s\n" +
			"* transkluzji {{s|wikipedia}} (tylko hasła polskie): %2$s\n" +
			"* jednakowych artykułów docelowych w Wikipedii: %3$s\n" + 
			"* istniejących artykułów w Wikipedii (uwzględniając przekierowania): %4$s\n" +
			"* rozmiar listy: %5$s\n" + 
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
		
		PageContainer[] plwiktTransclusions = plwikt.getContentOfTransclusions("Szablon:wikipedia", Wiki.MAIN_NAMESPACE);
		
		int totalTemplateTransclusions = plwiktTransclusions.length;
		System.out.printf("Total {{wikipedia}} transclusions on plwiktionary: %d%n", totalTemplateTransclusions);
		
		Map<String, Set<String>> plwiktToPlwiki = buildTargetMap(plwiktTransclusions);
		
		int targetedTemplateTransclusions = plwiktToPlwiki.size();
		System.out.printf("Targeted {{wikipedia}} transclusions on plwiktionary: %d%n", targetedTemplateTransclusions);
		
		String[] plwikiTitles = plwiktToPlwiki.values().stream()
			.flatMap(Set::stream)
			.distinct()
			.toArray(String[]::new);
		
		int targetedArticles = plwikiTitles.length;
		System.out.printf("Targeted articles on plwikipedia: %d%n", targetedArticles);
		
		@SuppressWarnings("rawtypes")
		Map[] pageInfos = plwiki.getPageInfo(plwikiTitles);
		
		String[] foundPlwikiTitles = getFoundArticles(plwikiTitles, pageInfos);
		Set<String> missingPlwikiTitles = new HashSet<>(Arrays.asList(plwikiTitles));
		missingPlwikiTitles.removeAll(Arrays.asList(foundPlwikiTitles));
		
		int foundArticles = foundPlwikiTitles.length;
		System.out.printf("Targeted articles on plwikipedia (non-missing): %d%n", foundArticles);
		
		String[] plwikiRedirs = plwiki.resolveRedirects(foundPlwikiTitles);
		String[] resolvedRedirs = updateRedirs(plwiktToPlwiki, foundPlwikiTitles, plwikiRedirs);
		PageContainer[] plwikiContents = plwiki.getContentOfPages(resolvedRedirs);
		Map<String, Set<String>> plwikiToPlwikt = retrievePlwiktBacklinks(plwikiContents);
		removeFoundOccurrences(plwiktToPlwiki, plwikiToPlwikt);
		
		int filteredTitles = plwiktToPlwiki.size();
		System.out.printf("Filtered plwiktionary-to-plwikipedia list: %d%n", filteredTitles);
		
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int) Misc.deserialize(fHash) == plwiktToPlwiki.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(plwiktToPlwiki.hashCode(), fHash);
			String[] arr = plwiktToPlwiki.keySet().toArray(new String[plwiktToPlwiki.size()]);
			Misc.serialize(arr, LOCATION + "stored_titles.ser");
			System.out.printf("%d titles stored.%n", arr.length);
		}
		
		String out = String.format(PAGE_INTRO,
				Misc.makePluralPL(totalTemplateTransclusions),
				Misc.makePluralPL(targetedTemplateTransclusions),
				Misc.makePluralPL(targetedArticles),
				Misc.makePluralPL(foundArticles),
				Misc.makePluralPL(filteredTitles)
			);
		
		out += makeOutput(plwiktToPlwiki, plwikiToPlwikt, missingPlwikiTitles);
		
		plwikt.edit(TARGET_PAGE, out, "aktualizacja");
	}
	
	private static Map<String, Set<String>> buildTargetMap(PageContainer[] pages) {
		Collator coll = Misc.getCollator("pl");
		Map<String, Set<String>> map = new TreeMap<String, Set<String>>(coll);
		
		for (PageContainer page : pages) {
			Page p = Page.wrap(page);
			Section s;
			
			try {
				s = p.getPolishSection().get();
			} catch (NoSuchElementException e) {
				continue;
			}
			
			Set<String> targets = new TreeSet<>();
			
			for (String template : ParseUtils.getTemplates("wikipedia", s.toString())) {
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				String param = params.getOrDefault("ParamWithoutName", "");
				
				if (!param.isEmpty()) {
					targets.add(param);
				} else {
					targets.add(page.getTitle());
				}
			}
			
			if (!targets.isEmpty()) {
				targets = targets.stream().map(StringUtils::capitalize).collect(Collectors.toSet());
				map.put(page.getTitle(), targets);
			}
		}
		
		return map;
	}
	
	private static String[] getFoundArticles(String[] titles, @SuppressWarnings("rawtypes") Map[] pageInfos) {
		List<String> list = new ArrayList<>();
		
		for (int i = 0; i < pageInfos.length; i++) {
			@SuppressWarnings("rawtypes")
			Map pageInfo = pageInfos[i];
			Boolean exists = (Boolean)pageInfo.get("exists");
			
			if (exists) {
				list.add(titles[i]);
			}
		}
		
		return list.toArray(new String[list.size()]);
	}
	
	private static String[] updateRedirs(Map<String, Set<String>> map, String[] titles, String[] redirs) {
		Map<String, String> titleToRedir = new HashMap<>(titles.length);
		String[] updatedTitles = new String[titles.length];
		
		for (int i = 0; i < titles.length; i++) {
			if (redirs[i] != null) {
				titleToRedir.put(titles[i], redirs[i]);
				updatedTitles[i] = redirs[i];
			} else {
				updatedTitles[i] = titles[i];
			}
		}
		
		for (Set<String> targets : map.values()) {
			for (String target : targets) {
				if (titleToRedir.containsKey(target)) {
					targets.remove(target);
					targets.add(titleToRedir.get(target));
				}
			}
		}
		
		return Stream.of(updatedTitles).distinct().toArray(String[]::new);
	}
	
	private static Map<String, Set<String>> retrievePlwiktBacklinks(PageContainer[] pages) {
		return Stream.of(pages)
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				pc -> getPlwiktBacklinks(pc.getTitle(), pc.getText()))
			);
	}
	
	private static Set<String> getPlwiktBacklinks(String title, String text) {
		Set<String> set = new HashSet<>();
		
		for (Map.Entry<String, List<String>> e : TARGET_TEMPLATES.entrySet()) {
			String templateName = e.getKey();
			List<String> params = e.getValue();
			List<String> templates = ParseUtils.getTemplatesIgnoreCase(templateName, text);
			
			for (String template : templates) {
				HashMap<String, String> paramMap = ParseUtils.getTemplateParametersWithValue(template);
				
				if (params != null) {
					for (String param : params) {
						String value = paramMap.getOrDefault(param, "");
						
						if (!value.isEmpty()) {
							set.add(value);
						}
					}
				} else {
					String value = paramMap.getOrDefault("ParamWithoutName1", "");
					
					if (!value.isEmpty()) {
						set.add(value);
					} else {
						set.add(title);
					}
				}
			}
		}
		
		return set;
	}
	
	private static void removeFoundOccurrences(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt) {
		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();
			Iterator<String> it = e.getValue().iterator();
			
			while (it.hasNext()) {
				String plwikiTitle = it.next();
				Set<String> backlinks = plwikiToPlwikt.get(plwikiTitle);
				
				if (backlinks != null && backlinks.contains(plwiktTitle)) {
					it.remove();
				}
			}
		}
		
		plwiktToPlwiki.entrySet().removeIf(e -> e.getValue().isEmpty());
	}
	
	private static String makeOutput(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Set<String> missingPlwikiTitles) {
		List<String> out = new ArrayList<>(plwiktToPlwiki.size());
		
		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();
			
			for (String articleOnPlwiki : e.getValue()) {
				if (missingPlwikiTitles.contains(articleOnPlwiki)) {
					String s = String.format("#[[%s]] ↔ [[w:%s]] (artykuł nie istnieje)", plwiktTitle, articleOnPlwiki);
					out.add(s);
				} else {
					Set<String> entriesOnPlwikt = plwikiToPlwikt.get(articleOnPlwiki);
					
					if (!entriesOnPlwikt.isEmpty()) {
						String links = entriesOnPlwikt.stream()
							.map(entry -> String.format("[[%s]]", entry))
							.collect(Collectors.joining(", "));
						
						String s = String.format("#[[%s]] ↔ [[w:%s]] (linkuje do: %s)", plwiktTitle, articleOnPlwiki, links);
						out.add(s);
					} else {
						String s = String.format("#[[%s]] ↔ [[w:%s]]", plwiktTitle, articleOnPlwiki);
						out.add(s);
					}
				}
			}
		}
		
		return String.join("\n", out);
	}
}