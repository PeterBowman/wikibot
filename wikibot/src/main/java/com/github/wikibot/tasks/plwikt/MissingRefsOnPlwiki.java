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
import java.util.Locale;
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
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
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
		//_sorted_map.put("Język infobox", Arrays.asList("wikisłownik")); // links back to categories only
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
		TARGET_TEMPLATES.put("Wielkość fizyczna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wielokąt infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wieś infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wojna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Złącze infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Związek chemiczny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Zwierzę infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Żywność infobox", Arrays.asList("wikisłownik"));
	}

	public static void main(String[] args) throws Exception {
		plwikt = Login.createSession("pl.wiktionary.org");
		plwiki = Login.createSession("pl.wikipedia.org");

		plwiki.setResolveRedirects(true);

		Map<String, Integer> stats = new HashMap<>();
		PageContainer[] plwiktTransclusions = plwikt.getContentOfTransclusions("Szablon:wikipedia", Wiki.MAIN_NAMESPACE);

		stats.put("totalTemplateTransclusions", plwiktTransclusions.length);
		System.out.printf("Total {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("totalTemplateTransclusions"));

		Map<String, Set<String>> plwiktToParsedPlwiki = buildTargetMap(plwiktTransclusions);

		stats.put("targetedTemplateTransclusions", plwiktToParsedPlwiki.size());
		System.out.printf("Targeted {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("targetedTemplateTransclusions"));

		String[] parsedPlwikiTitles = plwiktToParsedPlwiki.values().stream()
				.flatMap(Set::stream)
				.distinct()
				.toArray(String[]::new);

		stats.put("targetedArticles", parsedPlwikiTitles.length);
		System.out.printf("Targeted articles on plwikipedia: %d%n", stats.get("targetedArticles"));

		Map<String, String>[] plwikiPageProps = plwiki.getPageProps(parsedPlwikiTitles);

		Map<String, String> plwikiRedirToTarget = new HashMap<>();
		Set<String> plwikiDisambigs = new HashSet<>();
		Set<String> plwikiMissing = new HashSet<>();

		String[] plwikiTargetArticles = analyzePlwikiPageProps(plwikiPageProps, plwikiRedirToTarget, plwikiDisambigs, plwikiMissing);

		stats.put("foundArticles", plwikiTargetArticles.length);
		System.out.printf("Targeted articles on plwikipedia (non-missing): %d%n", stats.get("foundArticles"));

		stats.put("foundRedirects", plwikiRedirToTarget.size());
		System.out.printf("Targeted redirects on plwikipedia: %d%n", stats.get("foundRedirects"));

		stats.put("foundDisambigs", plwikiDisambigs.size());
		System.out.printf("Targeted disambigs on plwikipedia: %d%n", stats.get("foundDisambigs"));

		PageContainer[] plwikiContents = plwiki.getContentOfPages(plwikiTargetArticles);
		Map<String, Set<String>> plwikiToPlwiktBacklinks = retrievePlwiktBacklinks(plwikiContents);

		stats.put("totalPlwiktBacklinks", plwikiToPlwiktBacklinks.size());
		System.out.printf("Total plwiktionary backlinks: %d%n", stats.get("totalPlwiktBacklinks"));

		List<String> plwiktBacklinks = plwikiToPlwiktBacklinks.values().stream()
				.flatMap(Set::stream)
				.distinct()
				.filter(backlink -> !plwiktToParsedPlwiki.containsKey(backlink))
				.collect(Collectors.toList());

		removeFoundOccurrences(plwiktToParsedPlwiki, plwikiToPlwiktBacklinks, plwikiRedirToTarget);

		stats.put("filteredTitles", plwiktToParsedPlwiki.size());
		System.out.printf("Filtered plwiktionary-to-plwikipedia list: %d%n", stats.get("filteredTitles"));

		Set<String> plwiktMissing = getMissingPlwiktPages(plwiktBacklinks);

		stats.put("missingPlwiktBacklinks", plwikiMissing.size());
		System.out.printf("Missing plwiktionary backlinks: %d%n", stats.get("missingPlwiktBacklinks"));

		List<Entry> entries = makeEntryList(plwiktToParsedPlwiki, plwikiToPlwiktBacklinks, plwiktMissing, plwikiMissing,
				plwikiRedirToTarget, plwikiDisambigs);

		storeData(entries, stats);
	}

	private static Map<String, Set<String>> buildTargetMap(PageContainer[] pages) {
		Collator coll = Collator.getInstance(new Locale("pl", "PL"));
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

	private static String[] analyzePlwikiPageProps(Map<String, String>[] pageProps, Map<String, String> redirToTarget,
			Set<String> disambigs, Set<String> missing) {
		List<String> list = new ArrayList<>(pageProps.length);

		for (int i = 0; i < pageProps.length; i++) {
			Map<String, String> props = pageProps[i];

			if (props == null) {
				continue;
			}

			String pagename = props.get("pagename");
			String inputpagename = props.get("inputpagename");

			if (props.containsKey("missing")) {
				missing.add(pagename);
				continue;
			}

			if (!inputpagename.equals(pagename)) {
				redirToTarget.put(inputpagename, pagename);
			}

			if (props.containsKey("disambiguation")) {
				disambigs.add(pagename);
			}
			
			list.add(pagename);
		}

		return list.toArray(new String[list.size()]);
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
			Map<String, String> redirToTarget) {
		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();
			Iterator<String> it = e.getValue().iterator();

			while (it.hasNext()) {
				String plwikiTitle = it.next();
				plwikiTitle = redirToTarget.getOrDefault(plwikiTitle, plwikiTitle);
				Set<String> backlinks = plwikiToPlwikt.get(plwikiTitle);

				if (backlinks != null && backlinks.contains(plwiktTitle)) {
					it.remove();
				}
			}
		}

		plwiktToPlwiki.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	private static Set<String> getMissingPlwiktPages(List<String> titles) throws IOException {
		Set<String> set = new HashSet<>();
		boolean[] exist = plwikt.exists(titles);

		for (int i = 0; i < titles.size(); i++) {
			String title = titles.get(i);

			if (!exist[i]) {
				set.add(title);
			}
		}

		return set;
	}

	private static List<Entry> makeEntryList(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Set<String> plwiktMissing, Set<String> plwikiMissing, Map<String, String> redirToTarget, Set<String> plwikiDisambigs) {
		List<Entry> entries = new ArrayList<>(plwiktToPlwiki.size());

		for (Map.Entry<String, Set<String>> e : plwiktToPlwiki.entrySet()) {
			String plwiktTitle = e.getKey();

			for (String articleOnPlwiki : e.getValue()) {
				Entry entry = new Entry();
				entry.plwiktTitle = plwiktTitle;

				if (redirToTarget.containsKey(articleOnPlwiki)) {
					entry.plwikiRedir = articleOnPlwiki;
					articleOnPlwiki = redirToTarget.get(articleOnPlwiki);
				}

				entry.plwikiTitle = articleOnPlwiki;

				if (plwikiMissing.contains(articleOnPlwiki)) {
					entry.plwikiMissing = true;
				} else {
					Set<String> entriesOnPlwikt;
					
					try {
						entriesOnPlwikt = plwikiToPlwikt.get(articleOnPlwiki);
					} catch (NullPointerException ex) {
						System.out.printf("NullPointerException: %s%n", articleOnPlwiki);
						continue;
					}
					
					if (entriesOnPlwikt == null) {
						// e.g. {{wikipedia|Pl:Zair (prowincja)}}
						// https://pl.wiktionary.org/w/index.php?diff=6350864&title=Zair
						System.out.printf("Null entry: %s%n", articleOnPlwiki);
						continue;
					}

					if (!entriesOnPlwikt.isEmpty()) {
						entry.plwiktBacklinks = entriesOnPlwikt.stream()
								.collect(Collectors.toMap(
										entryOnPlwikt -> entryOnPlwikt,
										entryOnPlwikt -> !plwiktMissing.contains(entryOnPlwikt),
										(a, b) -> a,
										TreeMap::new));
					}
				}

				if (plwikiDisambigs.contains(articleOnPlwiki)) {
					entry.plwikiDisambig = true;
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

		Files.write(fStats.toPath(), List.of(xstream.toXML(stats)));
		Files.write(fTemplates.toPath(), List.of(xstream.toXML(TARGET_TEMPLATES)));
		Files.write(fTimestamp.toPath(), List.of(xstream.toXML(OffsetDateTime.now())));

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

		@XStreamAlias("backlinks")
		Map<String, Boolean> plwiktBacklinks;

		@XStreamAlias("missing")
		boolean plwikiMissing;

		@XStreamAlias("disambig")
		boolean plwikiDisambig;
	}
}
