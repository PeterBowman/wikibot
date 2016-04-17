package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Collator;
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

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class EsperantoRelatedTerms {
	private static final String LOCATION = "./data/tasks.plwikt/EsperantoRelatedTerms/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/potencjalne błędy w pokrewnych esperanto";
	private static final String TARGET_PAGE_EXCLUDED = "Wikipedysta:PBbot/potencjalne błędy w pokrewnych esperanto/wykluczenia";
	
	private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]([^\\[]*)"); // from Linker::formatLinksInComment in Linker.php
	private static PLWikt wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Map<String, List<String>> morphemToTitle = new HashMap<>(10000);
		Map<String, List<String>> titleToMorphem = new HashMap<>(15000);
		Map<String, String> contentMap = new HashMap<>(15000);
		
		populateMaps(morphemToTitle, titleToMorphem, contentMap);
		
		Map<String, List<MorphemTitlePair>> items = findItems(morphemToTitle, titleToMorphem, contentMap);
		
		removeIgnoredItems(items);
		
		StringBuilder sb = new StringBuilder(30000);
		
		for (Map.Entry<String, List<MorphemTitlePair>> entry : items.entrySet()) {
			sb.append(String.format("# [[%s]]: ", entry.getKey()));
			sb.append(buildItemList(entry.getValue())).append("\n");
		}
		
		if (!checkAndUpdateStoredData(items)) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		IOUtils.writeToFile(sb.toString(), LOCATION + "output.txt");
		editPage(sb.toString());
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
	
	private static Map<String, List<MorphemTitlePair>> findItems(Map<String, List<String>> morphemToTitle,
			Map<String, List<String>> titleToMorphem, Map<String, String> contentMap) throws IOException {
		Map<String, List<MorphemTitlePair>> items = new TreeMap<>(Misc.getCollator("eo"));
		String[] allEsperantoTitles = wb.getCategoryMembers("esperanto (indeks)", 0);
		Set<String> esperantoSet = new HashSet<>(Arrays.asList(allEsperantoTitles));
		
		for (Map.Entry<String, String> entry : contentMap.entrySet()) {
			String title = entry.getKey();
			
			Page p = Page.store(title, entry.getValue());
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
			List<MorphemTitlePair> list = new ArrayList<>();
			
			for (String morphem : morphems) {
				List<String> wrong = new ArrayList<>(relatedTerms);
				wrong.removeAll(allTitles);
				wrong.removeIf(t -> t.toLowerCase().contains(morphem) && !esperantoSet.contains(t));
				wrong.removeIf(t -> t.contains(" "));
				
				wrong.stream()
					.map(t -> new MorphemTitlePair(t, morphem))
					.sorted()
					.forEach(list::add);
			}
			
			if (!list.isEmpty()) {
				items.put(title, list);
			}
		}
		
		System.out.printf("%d items extracted%n", items.size());
		return items;
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
	
	private static void removeIgnoredItems(Map<String, List<MorphemTitlePair>> items) throws IOException {
		Pattern patt = Pattern.compile("# *\\[\\[([^\\]]+?)\\]\\]: *\\[\\[([^\\]]+?)\\]\\] *\\(([^\\)]+?)\\)$", Pattern.MULTILINE);
		String pageText = wb.getPageText(TARGET_PAGE_EXCLUDED);
		Matcher m = patt.matcher(pageText);
		
		while (m.find()) {
			String target = m.group(1).trim();
			String title = m.group(2).trim();
			String morphem = m.group(3).trim();
			
			List<MorphemTitlePair> list = items.get(target);
			
			if (list != null) {
				MorphemTitlePair mtp = new MorphemTitlePair(title, morphem);
				list.remove(mtp);
				
				if (list.isEmpty()) {
					items.remove(target);
				}
			}
		}
		
		System.out.printf("%d items after removing ignored from subpage%n", items.size());
	}
	
	private static boolean checkAndUpdateStoredData(Map<String, List<MorphemTitlePair>> items)
			throws FileNotFoundException, IOException {
		int newHashCode = items.hashCode();
		int storedHashCode;
		
		File fHash = new File(LOCATION + "hash.ser");
		
		try {
			storedHashCode = Misc.deserialize(fHash);
		} catch (ClassNotFoundException | IOException e) {
			storedHashCode = 0;
		}
		
		if (storedHashCode != newHashCode) {
			Misc.serialize(newHashCode, fHash);
			return true;
		} else {
			return false;
		}
	}
	
	private static String buildItemList(List<MorphemTitlePair> list) {
		return list.stream()
			.collect(Collectors.groupingBy(
				i -> i.morphem,
				() -> new TreeMap<>(Misc.getCollator("eo")),
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
	
	private static void editPage(String text) throws IOException, LoginException {
		String pageText = wb.getPageText(TARGET_PAGE);
		
		if (pageText.contains("<!--") && pageText.contains("-->")) {
			pageText = pageText.substring(0, pageText.lastIndexOf("-->") + 3) + "\n";
		} else {
			pageText = "{{język linków|esperanto}}\n";
			pageText += "<!-- nie edytuj poniżej tej linii -->\n";
		}
		
		pageText += text;
		
		wb.setMarkBot(false);
		wb.edit(TARGET_PAGE, pageText, "aktualizacja");
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
			Collator col = Misc.getCollator("eo");
			return col.compare(title, o.title);
		}
		
		@Override
		public int hashCode() {
			return title.hashCode() + morphem.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			
			if (!(o instanceof MorphemTitlePair)) {
				return false;
			}
			
			MorphemTitlePair mtp = (MorphemTitlePair) o;
			return title.equals(mtp.title) && morphem.equals(mtp.morphem);
		}
		
		@Override
		public String toString() {
			return String.format("[%s: %s]", title, morphem);
		}
	}
}
