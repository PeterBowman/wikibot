package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.Collator;
import java.time.OffsetDateTime;
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

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.io.xml.StaxDriver;

public class MissingRefsOnPlwiki {
	private static final String LOCATION = "./data/tasks.plwikt/MissingRefsOnPlwiki/";
	private static final Map<String, List<String>> TARGET_TEMPLATES = new LinkedHashMap<String, List<String>>();

	private static Wikibot plwikt;
	private static Wikibot plwiki;

	static {
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
	}

	public static void main(String[] args) throws Exception {
		plwikt = Login.createSession("pl.wiktionary.org");
		plwiki = Login.createSession("pl.wikipedia.org");
		
		Map<String, Integer> stats = new HashMap<>();
		PageContainer[] plwiktTransclusions = plwikt.getContentOfTransclusions("Szablon:wikipedia", Wiki.MAIN_NAMESPACE);

		stats.put("totalTemplateTransclusions", plwiktTransclusions.length);
		System.out.printf("Total {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("totalTemplateTransclusions"));

		Map<String, Set<String>> plwiktToPlwiki = buildTargetMap(plwiktTransclusions);

		stats.put("targetedTemplateTransclusions", plwiktToPlwiki.size());
		System.out.printf("Targeted {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("targetedTemplateTransclusions"));

		String[] plwikiTitles = plwiktToPlwiki.values().stream()
				.flatMap(Set::stream)
				.distinct()
				.toArray(String[]::new);

		stats.put("targetedArticles", plwikiTitles.length);
		System.out.printf("Targeted articles on plwikipedia: %d%n", stats.get("targetedArticles"));

		Map<String, Object>[] pageInfos = plwiki.getPageInfo(plwikiTitles);

		String[] foundPlwikiTitles = getFoundArticles(plwikiTitles, pageInfos);
		Set<String> missingPlwikiTitles = new HashSet<>(Arrays.asList(plwikiTitles));
		missingPlwikiTitles.removeAll(Arrays.asList(foundPlwikiTitles));

		stats.put("foundArticles", foundPlwikiTitles.length);
		System.out.printf("Targeted articles on plwikipedia (non-missing): %d%n", stats.get("foundArticles"));

		String[] resolvedRedirs = plwiki.resolveRedirects(foundPlwikiTitles);
		Map<String, String> titleToRedir = translateRedirs(foundPlwikiTitles, resolvedRedirs);

		stats.put("foundRedirects", titleToRedir.size());
		System.out.printf("Targeted redirects on plwikipedia: %d%n", stats.get("foundRedirects"));

		PageContainer[] plwikiContents = plwiki.getContentOfPages(resolvedRedirs);
		Map<String, Set<String>> plwikiToPlwikt = retrievePlwiktBacklinks(plwikiContents);
		removeFoundOccurrences(plwiktToPlwiki, plwikiToPlwikt, titleToRedir);

		stats.put("filteredTitles", plwiktToPlwiki.size());
		System.out.printf("Filtered plwiktionary-to-plwikipedia list: %d%n", stats.get("filteredTitles"));
		
		List<Entry> entries = makeEntryList(plwiktToPlwiki, plwikiToPlwikt, missingPlwikiTitles, titleToRedir);
		storeData(entries, stats);
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

			Set<String> set = new TreeSet<>();

			for (String template : ParseUtils.getTemplates("wikipedia", s.toString())) {
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				String param = params.getOrDefault("ParamWithoutName1", "");

				if (!param.isEmpty()) {
					try {
						set.add(plwiki.normalize(param));
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
						continue;
					}
				} else {
					set.add(plwiki.normalize(page.getTitle()));
				}
			}

			if (!set.isEmpty()) {
				map.put(page.getTitle(), set);
			}
		}

		return map;
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
							try {
								set.add(plwikt.normalize(value));
							} catch (IllegalArgumentException e) {
								e.printStackTrace();
								continue;
							}
						}
					}
				} else {
					for (Map.Entry<String, String> paramEntry : paramMap.entrySet()) {
						String paramName = paramEntry.getKey();

						if (paramName.matches("^ParamWithoutName\\d+$")) {
							String value = paramEntry.getValue();

							if (!value.isEmpty()) {
								try {
									set.add(plwikt.normalize(value));
								} catch (IllegalArgumentException e) {
									e.printStackTrace();
									continue;
								}
							} else {
								set.add(plwikt.normalize(title));
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

	private static List<Entry> makeEntryList(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Set<String> missingPlwikiTitles, Map<String, String> titleToRedir) {
		List<Entry> entries = new ArrayList<>(plwiktToPlwiki.size());

		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();

			for (String articleOnPlwiki : e.getValue()) {
				Entry entry = new Entry();
				entry.plwiktTitle = plwiktTitle;

				if (titleToRedir.containsKey(articleOnPlwiki)) {
					entry.plwikiRedir = articleOnPlwiki;
					articleOnPlwiki = titleToRedir.get(articleOnPlwiki);
				}

				entry.plwikiTitle = articleOnPlwiki;

				if (missingPlwikiTitles.contains(articleOnPlwiki)) {
					entry.missingPlwikiArticle = true;
				} else {
					Set<String> entriesOnPlwikt = plwikiToPlwikt.get(articleOnPlwiki);

					if (!entriesOnPlwikt.isEmpty()) {
						entry.plwiktBacklinks = new ArrayList<>(entriesOnPlwikt);
					}
				}

				entries.add(entry);
			}
		}

		return entries;
	}

	private static void storeData(List<Entry> entries, Map<String, Integer> stats) throws IOException {
		File fEntries = new File(LOCATION + "entries.xml");
		File fStats = new File(LOCATION + "stats.xml");
		File fTemplates = new File(LOCATION + "templates.xml");
		File fTimestamp = new File(LOCATION + "timestamp.xml");
		File fCtrl = new File(LOCATION + "UPDATED");

		XStream xstream = new XStream(new StaxDriver());
		xstream.processAnnotations(Entry.class);

		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(fEntries))) {
			xstream.toXML(entries, bos);
		}

		Files.write(fStats.toPath(), Arrays.asList(xstream.toXML(stats)));
		Files.write(fTemplates.toPath(), Arrays.asList(xstream.toXML(TARGET_TEMPLATES)));
		Files.write(fTimestamp.toPath(), Arrays.asList(xstream.toXML(OffsetDateTime.now())));

		fCtrl.delete();
	}

	// keep in sync with com.github.wikibot.webapp.MissingPlwiktRefsOnPlwiki
	@XStreamAlias("entry")
	static class Entry {
		@XStreamAlias("plwikt")
		String plwiktTitle;

		@XStreamAlias("plwiki")
		String plwikiTitle;

		@XStreamAlias("redir")
		String plwikiRedir;

		@XStreamImplicit(itemFieldName="linksTo")
		List<String> plwiktBacklinks;

		@XStreamAlias("missing")
		boolean missingPlwikiArticle;
	}
}
