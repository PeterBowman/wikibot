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

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public class MissingRefsOnPlwiki {
	private static final String LOCATION = "./data/tasks.plwikt/MissingRefsOnPlwiki/";
	private static final String TARGET_PAGE = "Wikisłownikarz:PBbot/brak Wikisłownika na Wikipedii";
	private static final String ERRORS_SUBPAGE = "/błędy";

	private static final String TARGET_PAGE_INTRO;
	private static final String ERRORS_SUBPAGE_INTRO;

	private static final Map<String, List<String>> TARGET_TEMPLATES;

	private static Wikibot plwikt;
	private static Wikibot plwiki;

	static {
		TARGET_TEMPLATES = new LinkedHashMap<String, List<String>>();

		TARGET_TEMPLATES.put("Wikisłownik", null);
		TARGET_TEMPLATES.put("Siostrzane projekty", Arrays.asList("słownik"));
		TARGET_TEMPLATES.put("Artefakt legendarny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Białko infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Biogram infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Element elektroniczny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Grafem infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Imię infobox", Arrays.asList("wikisłownik"));
		//TARGET_TEMPLATES.put("Język infobox", Arrays.asList("wikisłownik")); // links back to categories only
		TARGET_TEMPLATES.put("Klucz infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Kraina historyczna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Miasto infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Miejscowość infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Minerał infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Narzędzie infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Państwo infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Pierwiastek infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Pismo infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Polskie miasto infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Postać fikcyjna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Postać religijna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Preparat leczniczy infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Roślina infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Rzeka infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Takson infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wielokąt infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wieś infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wojna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Złącze infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Związek chemiczny infobox", Arrays.asList("wikisłownik"));
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

		TARGET_PAGE_INTRO = "Spis polskich haseł w Wikisłowniku transkludujących szablon {{s|wikipedia}}, " +
				"których docelowy artykuł (po rozwiązaniu przekierowań) w Wikipedii bądź nie istnieje, " +
				"bądź nie linkuje z powrotem do tego samego hasła u nas. " +
				"Obsługiwane szablony w Wikipedii:\n" +
				templates + "\n" +
				"Statystyka:\n" +
				"* transkluzji {{s|wikipedia}} (w sumie): %1$s\n" +
				"* transkluzji {{s|wikipedia}} (tylko hasła polskie): %2$s\n" +
				"* jednakowych artykułów docelowych w Wikipedii: %3$s\n" +
				"* istniejących artykułów w Wikipedii: %4$s (w tym przekierowań: %5$s)\n" +
				"* rozmiar listy: %6$s\n" +
				"Zobacz też:\n" +
				"* [[Wikisłownikarz:Azureus/brak Wikisłownika na Wikipedii]] (do 2010)\n" +
				"* [[w:Wikipedysta:Nostrix/Wikisłownik]]\n" +
				"* [[%7$s|podstrona z błędami]]\n" +
				"Zmiany wykonane ręcznie na tej stronie zostaną nadpisane przez bota. " +
				"Wygenerowano ~~~~~.\n" +
				"----\n";

		ERRORS_SUBPAGE_INTRO = "Spis wywołań {{s|wikipedia}}, w których podano nieprawidłowy tytuł. " +
				"Wygenerowano ~~~~~.\n" +
				"----\n";
	}

	public static void main(String[] args) throws Exception {
		plwikt = Login.createSession("pl.wiktionary.org");
		plwiki = Login.createSession("pl.wikipedia.org");

		// populate namespace cache
		plwikt.getNamespaces();

		PageContainer[] plwiktTransclusions = plwikt.getContentOfTransclusions("Szablon:wikipedia", Wiki.MAIN_NAMESPACE);

		int totalTemplateTransclusions = plwiktTransclusions.length;
		System.out.printf("Total {{wikipedia}} transclusions on plwiktionary: %d%n", totalTemplateTransclusions);

		List<String> errors = new ArrayList<>();
		Map<String, Set<String>> plwiktToPlwiki = buildTargetMap(plwiktTransclusions, errors);

		int targetedTemplateTransclusions = plwiktToPlwiki.size();
		System.out.printf("Targeted {{wikipedia}} transclusions on plwiktionary: %d%n", targetedTemplateTransclusions);

		String[] plwikiTitles = plwiktToPlwiki.values().stream()
				.flatMap(Set::stream)
				.distinct()
				.toArray(String[]::new);

		int targetedArticles = plwikiTitles.length;
		System.out.printf("Targeted articles on plwikipedia: %d%n", targetedArticles);

		Map<String, Object>[] pageInfos = plwiki.getPageInfo(plwikiTitles);

		String[] foundPlwikiTitles = getFoundArticles(plwikiTitles, pageInfos);
		Set<String> missingPlwikiTitles = new HashSet<>(Arrays.asList(plwikiTitles));
		missingPlwikiTitles.removeAll(Arrays.asList(foundPlwikiTitles));

		int foundArticles = foundPlwikiTitles.length;
		System.out.printf("Targeted articles on plwikipedia (non-missing): %d%n", foundArticles);

		String[] resolvedRedirs = plwiki.resolveRedirects(foundPlwikiTitles);
		Map<String, String> titleToRedir = translateRedirs(foundPlwikiTitles, resolvedRedirs);

		int foundRedirects = titleToRedir.size();
		System.out.printf("Targeted redirects on plwikipedia: %d%n", foundRedirects);

		PageContainer[] plwikiContents = plwiki.getContentOfPages(resolvedRedirs);
		Map<String, Set<String>> plwikiToPlwikt = retrievePlwiktBacklinks(plwikiContents);
		removeFoundOccurrences(plwiktToPlwiki, plwikiToPlwikt, titleToRedir);

		int filteredTitles = plwiktToPlwiki.size();
		System.out.printf("Filtered plwiktionary-to-plwikipedia list: %d%n", filteredTitles);

		File fHashMain = new File(LOCATION + "hash_main.ser");
		File fHashErrors = new File(LOCATION + "hash_errors.ser");

		plwikt.setMarkBot(false);

		if (!fHashMain.exists() || (int)Misc.deserialize(fHashMain) != plwiktToPlwiki.hashCode()) {
			Misc.serialize(plwiktToPlwiki.hashCode(), fHashMain);
			String[] arr = plwiktToPlwiki.keySet().toArray(new String[plwiktToPlwiki.size()]);
			Misc.serialize(arr, LOCATION + "stored_titles.ser");

			System.out.printf("%d titles stored.%n", arr.length);

			String out = String.format(TARGET_PAGE_INTRO,
					Misc.makePluralPL(totalTemplateTransclusions),
					Misc.makePluralPL(targetedTemplateTransclusions),
					Misc.makePluralPL(targetedArticles),
					Misc.makePluralPL(foundArticles),
					Misc.makePluralPL(foundRedirects),
					Misc.makePluralPL(filteredTitles),
					ERRORS_SUBPAGE
					);
	
			out += makeOutput(plwiktToPlwiki, plwikiToPlwikt, missingPlwikiTitles, titleToRedir);
			plwikt.edit(TARGET_PAGE, out, "aktualizacja");
		} else {
			System.out.println("No changes detected in main list.");
		}

		System.out.printf("Errors found: %d%n", errors.size());

		if (!fHashErrors.exists() || (int)Misc.deserialize(fHashErrors) != errors.hashCode()) {
			Misc.serialize(errors.hashCode(), fHashErrors);
			String out = ERRORS_SUBPAGE_INTRO + String.join("\n", errors);
			plwikt.edit(TARGET_PAGE + ERRORS_SUBPAGE, out, "aktualizacja");
		} else {
			System.out.println("No changes detected in error list.");
		}
	}

	private static Map<String, Set<String>> buildTargetMap(PageContainer[] pages, List<String> errors) {
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

			List<String> list = new ArrayList<>();

			for (String template : ParseUtils.getTemplates("wikipedia", s.toString())) {
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				String param = params.getOrDefault("ParamWithoutName1", "");

				if (!param.isEmpty()) {
					try {
						list.add(normalizeTitle(param));
					} catch (IllegalArgumentException e) {
						errors.add(String.format("#[[%s]]: <nowiki>%s</nowiki>", page.getTitle(), template));
						continue;
					}
				} else {
					list.add(page.getTitle());
				}
			}

			if (!list.isEmpty()) {
				Set<String> targets = list.stream()
					.map(StringUtils::capitalize)
					.collect(Collectors.toCollection(TreeSet::new));

				map.put(page.getTitle(), targets);
			}
		}

		return map;
	}

	private static String normalizeTitle(String title) {
		title = plwikt.normalize(title.trim()); // throws IllegalArgumentException

		try {
			// TODO: Wiki.normalize should take care of this.
			// Also, I'd like to see fragments in wikilinks in the final output list.
			return title.substring(0, title.indexOf("#"));
		} catch (StringIndexOutOfBoundsException e) {
			return title;
		}
	}

	private static String[] getFoundArticles(String[] titles, Map<String, Object>[] pageInfos) {
		List<String> list = new ArrayList<>();

		for (int i = 0; i < pageInfos.length; i++) {
			Map<String, Object> pageInfo = pageInfos[i];

			if (pageInfo != null && Boolean.TRUE.equals(pageInfo.get("exists"))) {
				list.add(titles[i]);
			}
		}

		return list.toArray(new String[list.size()]);
	}

	private static Map<String, String> translateRedirs(String[] titles, String[] redirs) {
		Map<String, String> titleToRedir = new HashMap<>();

		for (int i = 0; i < titles.length; i++) {
			if (!redirs[i].equals(titles[i])) {
				titleToRedir.put(titles[i], redirs[i]);
			}
		}

		return titleToRedir;
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

		for (Map.Entry<String, List<String>> templateEntry : TARGET_TEMPLATES.entrySet()) {
			String templateName = templateEntry.getKey();
			List<String> params = templateEntry.getValue();
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
					for (Map.Entry<String, String> paramEntry : paramMap.entrySet()) {
						String paramName = paramEntry.getKey();

						if (paramName.matches("^ParamWithoutName\\d+$")) {
							String value = paramEntry.getValue();

							if (!value.isEmpty()) {
								set.add(value);
							} else {
								set.add(title);
							}
						}
					}
				}
			}
		}

		return set;
	}

	private static void removeFoundOccurrences(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Map<String, String> titleToRedir) {
		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();
			Iterator<String> it = e.getValue().iterator();

			while (it.hasNext()) {
				String plwikiTitle = it.next();
				plwikiTitle = titleToRedir.getOrDefault(plwikiTitle, plwikiTitle);
				Set<String> backlinks = plwikiToPlwikt.get(plwikiTitle);

				if (backlinks != null && backlinks.contains(plwiktTitle)) {
					it.remove();
				}
			}
		}

		plwiktToPlwiki.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	private static String sanitizeBacklinkEntries(String entry) {
		if (plwikt.namespace(entry) == Wiki.CATEGORY_NAMESPACE) {
			entry = ":" + entry;
		}

		return entry;
	}

	private static String makeOutput(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Set<String> missingPlwikiTitles, Map<String, String> titleToRedir) {
		List<String> out = new ArrayList<>(plwiktToPlwiki.size());

		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();

			for (String articleOnPlwiki : e.getValue()) {
				String s = String.format("#[[%s]] ↔ [[w:%s]]", plwiktTitle, articleOnPlwiki);

				if (titleToRedir.containsKey(articleOnPlwiki)) {
					articleOnPlwiki = titleToRedir.get(articleOnPlwiki);
					s += String.format(" ↔ [[w:%s]]", articleOnPlwiki);
				}

				if (missingPlwikiTitles.contains(articleOnPlwiki)) {
					s += " (artykuł nie istnieje)";
					out.add(s);
				} else {
					Set<String> entriesOnPlwikt = plwikiToPlwikt.get(articleOnPlwiki);

					if (!entriesOnPlwikt.isEmpty()) {
						String links = entriesOnPlwikt.stream()
								.map(MissingRefsOnPlwiki::sanitizeBacklinkEntries)
								.map(entry -> String.format("[[%s]]", entry))
								.collect(Collectors.joining(", "));

						s += String.format(" (linkuje do: %s)", links);
						out.add(s);
					} else {
						out.add(s);
					}
				}
			}
		}

		return String.join("\n", out);
	}
}
