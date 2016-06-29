package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class RefreshWantedArticles {
	private static final String LOCATION = "./data/tasks.plwikt/RefreshWantedArticles/";
	private static final String TARGET_PAGE = "Szablon:Potrzebne";
	private static final String REFILL_PAGE = "Wikipedysta:AlkamidBot/listy/Najbardziej potrzebne";
	
	private static final int MAX_LENGHT = 100;
	private static final int REFILL_SIZE = 10;
	private static final int REFILL_THRESHOLD = 20;
	
	private static final Pattern P_LINK;
	private static final Pattern P_OCCURRENCES_TARGET;
	private static final Pattern P_OCCURRENCES_REFILL;
	
	private static Wikibot wb;
	
	static {
		P_LINK = Pattern.compile("\\[\\[([^\\]\n]+?)\\]\\]");
		P_OCCURRENCES_TARGET = Pattern.compile("((?: *• *)?" + P_LINK.pattern() + ")+");
		P_OCCURRENCES_REFILL = Pattern.compile("^\\| *" + P_LINK.pattern() + " *\\|\\|.+", Pattern.MULTILINE);
	}

	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		String content = wb.getPageText(TARGET_PAGE);
		
		Document doc = Jsoup.parseBodyFragment(content);
		doc.outputSettings().prettyPrint(false);
		
		Element targetElement = selectTargetElement(doc.body());
		
		List<String> visibleTitles = extractTitles(targetElement.previousSibling());
		List<String> hiddenTitles = extractTitles(targetElement);
		
		Set<String> storeSet = manageStoredTitles(visibleTitles, hiddenTitles);
		
		List<String> doneVisible = filterDoneArticles(visibleTitles);
		
		if (doneVisible.isEmpty()) {
			System.out.println("No articles from the visible list have been created yet.");
			return;
		}
		
		List<String> doneHidden = filterDoneArticles(hiddenTitles);
		
		processElement(targetElement, visibleTitles, doneVisible, hiddenTitles, doneHidden, storeSet);
		
		String text = doc.body().html();
		String counter = Misc.makePluralPL(doneVisible.size() + doneHidden.size(), "utworzone", "utworzonych");
		String summary = String.format("odświeżenie listy (%s)", counter);
		
		wb.edit(TARGET_PAGE, text, summary);
		wb.purge(true, TARGET_PAGE);
	}
	
	private static Element selectTargetElement(Element body) {
		Elements els = body.getElementsByTag("noinclude");
		
		if (els.isEmpty()) {
			throw new RuntimeException("No <noinclude> tags found.");
		}
		
		Element parent = els.get(0).parent();
		
		if (els.stream().anyMatch(el -> el.parent() != parent)) {
			throw new RuntimeException("Multiple <noinclude> tags with no common parent.");
		}
		
		Element targetElement = els.stream()
			.filter(el -> P_OCCURRENCES_TARGET.matcher(el.html().trim()).matches())
			.findFirst()
			.orElseThrow(() -> new RuntimeException("No matching <noinclude> target element found."));
		
		if (!targetElement.children().isEmpty()) {
			throw new RuntimeException("Target <noinclude> node has inner elements.");
		}
		
		Node previousSibling = targetElement.previousSibling();
		
		if (previousSibling == null) {
			throw new RuntimeException("Target <noinclude> node has no previous sibling.");
		}
		
		if (!(previousSibling instanceof TextNode)) {
			throw new RuntimeException("Target <noinclude> node has no previous TextNode sibling.");
		}
		
		if (!P_OCCURRENCES_TARGET.matcher(previousSibling.toString()).matches()) {
			throw new RuntimeException("TextNode sibling does not match the expected pattern.");
		}
		
		return targetElement;
	}
	
	private static List<String> extractTitles(Node node) {
		String text;
		
		if (node instanceof Element) {
			text = ((Element) node).html().trim();
		} else {
			text = node.toString().trim();
		}
		
		List<String> list = new ArrayList<>();
		Matcher m = P_LINK.matcher(text);
		
		while (m.find()) {
			list.add(m.group(1).trim());
		}
		
		if (list.isEmpty()) {
			throw new RuntimeException("No titles have been extracted from " + node.getClass() + ".");
		}
		
		return list;
	}
	
	private static Set<String> manageStoredTitles(List<String> visible, List<String> hidden) throws IOException {
		final String fileName = "store.ser";
		Set<String> storeSet;
		
		try {
			storeSet = Misc.deserialize(LOCATION + fileName);
		} catch (ClassNotFoundException | FileNotFoundException e) {
			storeSet = new HashSet<>();
		}
		
		storeSet.addAll(visible);
		storeSet.addAll(hidden);
		
		Misc.serialize(storeSet, LOCATION + fileName);
		
		return storeSet;
	}
	
	private static List<String> filterDoneArticles(List<String> titles) throws IOException {
		PageContainer[] pages = wb.getContentOfPages(titles.toArray(new String[titles.size()]));
		
		return Stream.of(pages)
			.map(Page::wrap)
			.filter(page -> page.getPolishSection().isPresent())
			.map(Page::getTitle)
			.collect(Collectors.toList());
	}
	
	private static void processElement(Element el, List<String> visibleTitles, List<String> doneVisible,
			List<String> hiddenTitles, List<String> doneHidden, Set<String> storeSet) {
		List<String> visibleList = new ArrayList<>(visibleTitles);
		visibleList.removeAll(doneVisible);
		
		List<String> hiddenList = new ArrayList<>(hiddenTitles);
		hiddenList.removeAll(doneHidden);
		
		while (!checkLength(visibleList)) {
			if (hiddenList.size() < REFILL_THRESHOLD) {
				try {
					fetchMoreArticles(hiddenList, storeSet);
					storeSet.addAll(hiddenList);
				} catch (IOException e) {
					e.printStackTrace();
					break;
				}
			}
			
			if (hiddenList.isEmpty()) {
				break;
			}
			
			visibleList.add(hiddenList.remove(0));
		}
		
		final String fmt = "[[%s]]";
		String visibleText = buildString(visibleList, fmt);
		String hiddenText = " • " + buildString(hiddenList, fmt);
		
		((TextNode) el.previousSibling()).text(visibleText);
		el.text(hiddenText);
	}
	
	private static boolean checkLength(List<String> list) {
		return buildString(list, "%s").length() > MAX_LENGHT;
	}
	
	private static String buildString(List<String> list, String fmt) {
		return list.stream()
			.map(s -> String.format(fmt, s))
			.collect(Collectors.joining(" • "));
	}
	
	private static void fetchMoreArticles(List<String> list, Set<String> storeSet) throws IOException {
		String text = wb.getPageText(REFILL_PAGE);
		Matcher m = P_OCCURRENCES_REFILL.matcher(text);
		List<String> titles = new ArrayList<>(500);
		
		while (m.find()) {
			String title = m.group(1).trim();
			
			if (!storeSet.contains(title)) {
				titles.add(title);
			}
		}
		
		Set<String> doneSet = new HashSet<>(filterDoneArticles(titles));
		
		titles.stream()
			.filter(title -> !doneSet.contains(title))
			.limit(REFILL_SIZE)
			.forEach(list::add);
	}
}
