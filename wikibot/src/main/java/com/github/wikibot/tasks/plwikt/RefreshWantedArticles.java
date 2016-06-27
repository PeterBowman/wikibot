package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.bind.ValidationException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractPage;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class RefreshWantedArticles {
	private static final String TARGET_PAGE = "Szablon:Potrzebne";
	private static final String REFILL_PAGE = "Wikipedysta:AlkamidBot/listy/Najbardziej potrzebne";
	private static final int MAX_LENGHT = 100;
	private static final int REFILL_SIZE = 25;
	
	private static final Pattern P_LINK;
	private static final Pattern P_OCCURRENCES_TARGET;
	private static final Pattern P_OCCURRENCES_REFILL;
	
	private static Wikibot wb;
	
	static {
		P_LINK = Pattern.compile("\\[\\[([^\\]\n]+?)\\]\\]");
		P_OCCURRENCES_TARGET = Pattern.compile("((?: *• *)?" + P_LINK.pattern() + ")+");
		P_OCCURRENCES_REFILL = Pattern.compile("\\| *" + P_LINK.pattern() + " *\\|\\|.+", Pattern.MULTILINE);
	}

	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		String content = wb.getPageText(TARGET_PAGE);
		Document doc = Jsoup.parseBodyFragment(content);
		doc.outputSettings().prettyPrint(false);
		
		Element targetElement;
		
		try {
			targetElement = selectTargetElement(doc);
		} catch (ValidationException e) {
			System.out.println(e.getMessage());
			return;
		}
		
		List<String> visibleTitles = extractTitles(targetElement.previousSibling());
		List<String> hiddenTitles = extractTitles(targetElement);
		
		if (visibleTitles.isEmpty() || hiddenTitles.isEmpty()) {
			System.out.println("No titles extracted.");
			return;
		}
		
		List<String> doneVisible = filterDoneArticles(visibleTitles);
		
		if (doneVisible.isEmpty()) {
			System.out.println("No articles from the visible list have been created yet.");
			return;
		}
		
		List<String> doneHidden = filterDoneArticles(hiddenTitles);
		
		processElement(targetElement, visibleTitles, doneVisible, hiddenTitles, doneHidden);
		
		String text = doc.body().html();
		String counter = Misc.makePluralPL(doneVisible.size() + doneHidden.size(), "utworzone", "utworzonych");
		String summary = String.format("odświeżenie listy (%s)", counter);
		
		wb.edit(TARGET_PAGE, text, summary);
	}
	
	private static Element selectTargetElement(Document doc) throws ValidationException {
		Elements els = doc.getElementsByTag("noinclude");
		
		if (els.isEmpty()) {
			throw new ValidationException("No <noinclude> tags found.");
		}
		
		Element parent = els.get(0).parent();
		
		if (els.stream().anyMatch(el -> el.parent() != parent)) {
			throw new ValidationException("Multiple <noinclude> tags with no common parent.");
		}
		
		Element targetElement = els.stream()
			.filter(el -> P_OCCURRENCES_TARGET.matcher(el.html()).matches())
			.findFirst()
			.orElseThrow(() -> new ValidationException("No matching <noinclude> target element found."));
		
		if (!targetElement.children().isEmpty()) {
			throw new ValidationException("Target <noinclude> node has inner elements.");
		}
		
		Node previousSibling = targetElement.previousSibling();
		
		if (previousSibling == null) {
			throw new ValidationException("Target <noinclude> node has no previous sibling.");
		}
		
		if (!(previousSibling instanceof TextNode)) {
			throw new ValidationException("Target <noinclude> node has no previous TextNode sibling.");
		}
		
		if (!P_OCCURRENCES_TARGET.matcher(previousSibling.toString()).matches()) {
			throw new ValidationException("TextNode sibling does not match the expected pattern.");
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
		
		return list;
	}
	
	private static List<String> filterDoneArticles(List<String> titles) throws IOException {
		PageContainer[] pages = wb.getContentOfPages(titles.toArray(new String[titles.size()]));
		
		return Stream.of(pages)
			.map(Page::wrap)
			.map(Page::getPolishSection)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(Section::getContainingPage)
			.map(Optional::get)
			.map(AbstractPage::getTitle)
			.collect(Collectors.toList());
	}
	
	private static void processElement(Element el, List<String> visibleTitles, List<String> doneVisible,
			List<String> hiddenTitles, List<String> doneHidden) throws IOException {
		List<String> visibleList = new ArrayList<>(visibleTitles);
		visibleList.removeAll(doneVisible);
		
		List<String> hiddenList = new ArrayList<>(hiddenTitles);
		hiddenList.removeAll(doneHidden);
		
		Set<String> storeSet = new HashSet<>();
		storeSet.addAll(visibleTitles);
		storeSet.addAll(hiddenTitles);
		
		while (!checkLength(visibleList)) {
			if (hiddenList.isEmpty()) {
				fetchMoreArticles(hiddenList, storeSet);
			}
			
			visibleList.add(hiddenList.remove(0));
		}
		
		String visibleText = buildString(visibleList, "[[%s]]");
		String hiddenText = " • " + buildString(hiddenList, "[[%s]]");
		
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
