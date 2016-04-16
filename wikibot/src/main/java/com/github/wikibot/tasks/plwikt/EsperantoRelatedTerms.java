package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class EsperantoRelatedTerms {
	private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]([^\\[]*)"); // from Linker::formatLinksInComment in Linker.php
	private static PLWikt wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Map<String, List<String>> morphemToTitle = new HashMap<>(10000);
		Map<String, List<String>> titleToMorphem = new HashMap<>(15000);
		Map<String, String> contentMap = new HashMap<>(15000);
		
		populateMaps(morphemToTitle, titleToMorphem, contentMap);
		
		List<Item> items = new ArrayList<>(contentMap.size());
		String[] allEsperantoTitles = wb.getCategoryMembers("esperanto (indeks)", 0);
		Set<String> esperantoSet = new HashSet<>(Arrays.asList(allEsperantoTitles));
		
		for (Map.Entry<String, String> entry : contentMap.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			
			Page p = Page.store(title, text);
			Section s = p.getSection("esperanto").get();
			Field f = s.getField(FieldTypes.RELATED_TERMS).get();
			
			List<String> morphems = titleToMorphem.get(title);
			
			List<String> allTitles = morphemToTitle.entrySet().stream()
				.filter(m2t -> morphems.contains(m2t.getKey()))
				.map(Map.Entry::getValue)
				.flatMap(Collection::stream)
				.distinct()
				.collect(Collectors.toList());
			
			Set<String> relatedTerms = extractLinks(f.getContent());
			Item item = new Item(title);
			
			for (String morphem : morphems) {
				List<String> missing = new ArrayList<>(morphemToTitle.get(morphem));
				missing.removeAll(relatedTerms);
				missing.remove(title);
				
				List<String> wrong = new ArrayList<>(relatedTerms);
				wrong.removeAll(allTitles);
				wrong.removeIf(t -> t.toLowerCase().contains(morphem) && !esperantoSet.contains(t));
				wrong.removeIf(t -> t.contains(" "));
				
				missing.stream()
					.map(t -> new MorphemTitlePair(t, morphem))
					.sorted()
					.forEach(item.missingTitles::add);
				
				wrong.stream()
					.map(t -> new MorphemTitlePair(t, morphem))
					.sorted()
					.forEach(item.wrongTitles::add);
			}
			
			if (!item.missingTitles.isEmpty() || !item.wrongTitles.isEmpty()) {
				items.add(item);
			}
		}
		
		System.out.printf("%d items extracted%n", items.size());
		
		items.sort((i1, i2) -> i1.title.compareTo(i2.title));
		
		StringBuilder sb = new StringBuilder(50000);
		sb.append("{{język linków|esperanto}}\n\n");
		
		/*for (Item item : items) {
			sb.append(String.format("# [[%s]]", item.title)).append("\n");
			
			if (!item.missingTitles.isEmpty()) {
				sb.append("#* brakujące: ").append(buildItemList(item.missingTitles)).append("\n");
			}
			
			if (!item.wrongTitles.isEmpty()) {
				sb.append("#* błędne: ").append(buildItemList(item.wrongTitles)).append("\n");
			}
		}*/
		
		for (Item item : items) {
			if (item.wrongTitles.isEmpty()) {
				continue;
			}
			
			sb.append(String.format("# [[%s]]: ", item.title));
			sb.append(buildItemList(item.wrongTitles)).append("\n");
		}
		
		IOUtils.writeToFile(sb.toString(), "./data/test9a.txt");
		
		/*for (Item item : items) {
			if (item.missingTitles.isEmpty()) {
				continue;
			}
			
			sb.append(String.format("# [[%s]]: ", item.title));
			sb.append(buildItemList(item.missingTitles)).append("\n");
		}
		
		IOUtils.writeToFile(sb.toString(), "./data/test9b.txt");*/
	}

	private static void populateMaps(Map<String, List<String>> morphemToTitle,
			Map<String, List<String>> titleToMorphem,
			Map<String, String> contentMap)
		throws IOException {
		String[] cat1 = wb.getCategoryMembers("Esperanto - końcówki gramatyczne", 0);
		String[] cat2 = wb.getCategoryMembers("Esperanto - morfemy przedrostkowe", 0);
		String[] cat3 = wb.getCategoryMembers("Esperanto - morfemy przyrostkowe", 0);
		
		Set<String> excluded = new HashSet<>(cat1.length + cat2.length + cat3.length);
		excluded.addAll(Arrays.asList(cat1));
		excluded.addAll(Arrays.asList(cat2));
		excluded.addAll(Arrays.asList(cat3));
		
		PageContainer[] morfeoTransclusions = wb.getContentOfTransclusions("Szablon:morfeo", 0);
				
		for (PageContainer page : morfeoTransclusions) {
			for (String template : ParseUtils.getTemplates("morfeo", page.getText())) {
				Map<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				params.remove("templateName");
				params.values().removeAll(excluded);
				
				if (!params.values().isEmpty()) {
					String morphem = String.join("", params.values());
					List<String> titles = morphemToTitle.getOrDefault(morphem, new ArrayList<>());
					
					if (!titles.contains(page.getTitle())) {
						titles.add(page.getTitle());
						morphemToTitle.putIfAbsent(morphem, titles);
					}
					
					List<String> morphems = titleToMorphem.getOrDefault(page.getTitle(), new ArrayList<>());
					
					if (!morphems.contains(morphem)) {
						morphems.add(morphem);
						titleToMorphem.putIfAbsent(page.getTitle(), morphems);
						contentMap.put(page.getTitle(), page.getText());
					}
				}
			}
		}
		
		System.out.printf("morphemToTitle: %d%n", morphemToTitle.size());
		System.out.printf("titleToMorphem: %d%n", titleToMorphem.size());
	}
	
	private static Set<String> extractLinks(String text) {
		if (text.isEmpty()) {
			return Collections.emptySet();
		}
		
		Matcher m = P_LINK.matcher(text);
		Set<String> set = new HashSet<>();
		
		while (m.find()) {
			String target = m.group(1);
			
			if (target.contains("#")) {
				target = target.substring(0, target.indexOf("#"));
			}
			
			set.add(target);
		}
		
		return set;
	}
	
	private static String buildItemList(List<MorphemTitlePair> list) {
		return list.stream()
			.collect(Collectors.groupingBy(
				i -> i.morphem,
				TreeMap::new,
				Collectors.mapping(
					i -> String.format("[[%s]]", i.title),
					Collectors.joining(", ")
				)
			))
			.entrySet().stream()
			.collect(Collectors.mapping(
				entry -> String.format("%s (%s)", entry.getValue(), entry.getKey()),
				Collectors.joining(" • ")
			));
	}
	
	private static class Item implements Comparable<Item> {
		String title;
		List<MorphemTitlePair> missingTitles = new ArrayList<>(0);
		List<MorphemTitlePair> wrongTitles = new ArrayList<>(0);
		
		Item(String title) {
			this.title = title;
		}

		@Override
		public int compareTo(Item i) {
			return title.compareTo(i.title);
		}
	}
	
	private static class MorphemTitlePair implements Comparable<MorphemTitlePair> {
		String title;
		String morphem;
		
		MorphemTitlePair(String title, String morphem) {
			this.title = title;
			this.morphem = morphem;
		}

		@Override
		public int compareTo(MorphemTitlePair o) {
			return title.compareTo(o.title);
		}
	}
}
