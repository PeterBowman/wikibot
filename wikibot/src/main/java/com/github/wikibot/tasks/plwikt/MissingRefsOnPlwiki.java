package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingRefsOnPlwiki/");
	private static final Map<String, List<String>> TARGET_TEMPLATES = new LinkedHashMap<>();

	private static final Wikibot plwikt = Wikibot.newSession("pl.wiktionary.org");
	private static final Wikibot plwiki = Wikibot.newSession("pl.wikipedia.org");

	static {
		TARGET_TEMPLATES.put("Wikisłownik", null);
		TARGET_TEMPLATES.put("Siostrzane projekty", Arrays.asList("słownik"));
		TARGET_TEMPLATES.put("Artefakt legendarny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Białko infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Biogram infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Cieśnina infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Element elektroniczny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Grafem infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Gwiazda infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Imię infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Jednostka administracyjna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Jezioro infobox", Arrays.asList("wikisłownik"));
		//_sorted_map.put("Język infobox", Arrays.asList("wikisłownik")); // links back to categories only
		TARGET_TEMPLATES.put("Klucz infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Kraina historyczna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Miasto infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Miejscowość infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Minerał infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Narzędzie infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Państwo infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Pasmo górskie infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Pierwiastek infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Pismo infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Polska miejscowość infobox", Arrays.asList("wikisłownik"));
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
		TARGET_TEMPLATES.put("Wirus infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Wojna infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Złącze infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Związek chemiczny infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Zwierzę infobox", Arrays.asList("wikisłownik"));
		TARGET_TEMPLATES.put("Żywność infobox", Arrays.asList("wikisłownik"));
	}

	public static void main(String[] args) throws Exception {
		Login.login(plwikt);
		Login.login(plwiki);

		var stats = new HashMap<String, Integer>();
		var plwiktTransclusions = plwikt.getContentOfTransclusions("Szablon:wikipedia", Wiki.MAIN_NAMESPACE);

		stats.put("totalTemplateTransclusions", plwiktTransclusions.size());
		System.out.printf("Total {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("totalTemplateTransclusions"));

		var plwiktToParsedPlwiki = buildTargetMap(plwiktTransclusions);

		stats.put("targetedTemplateTransclusions", plwiktToParsedPlwiki.size());
		System.out.printf("Targeted {{wikipedia}} transclusions on plwiktionary: %d%n", stats.get("targetedTemplateTransclusions"));

		var parsedPlwikiTitles = plwiktToParsedPlwiki.values().stream()
			.flatMap(Set::stream)
			.distinct()
			.toList();

		stats.put("targetedArticles", parsedPlwikiTitles.size());
		System.out.printf("Targeted articles on plwikipedia: %d%n", stats.get("targetedArticles"));

		var plwikiPageProps = plwiki.getPageProperties(parsedPlwikiTitles);
		var plwikiResolvedRedirs = plwiki.resolveRedirects(parsedPlwikiTitles);

		var plwikiRedirToTarget = new HashMap<String, String>();
		var plwikiDisambigs = new HashSet<String>();
		var plwikiMissing = new HashSet<String>();

		var plwikiTargetArticles = analyzePlwikiPageProps(parsedPlwikiTitles, plwikiPageProps, plwikiResolvedRedirs,
				plwikiRedirToTarget, plwikiDisambigs, plwikiMissing);

		stats.put("foundArticles", plwikiTargetArticles.size());
		System.out.printf("Targeted articles on plwikipedia (non-missing): %d%n", stats.get("foundArticles"));

		stats.put("foundRedirects", plwikiRedirToTarget.size());
		System.out.printf("Targeted redirects on plwikipedia: %d%n", stats.get("foundRedirects"));

		stats.put("foundDisambigs", plwikiDisambigs.size());
		System.out.printf("Targeted disambigs on plwikipedia: %d%n", stats.get("foundDisambigs"));

		var plwikiContents = plwiki.getContentOfPages(plwikiTargetArticles);
		var plwikiToPlwiktBacklinks = retrievePlwiktBacklinks(plwikiContents);

		stats.put("totalPlwiktBacklinks", plwikiToPlwiktBacklinks.size());
		System.out.printf("Total plwiktionary backlinks: %d%n", stats.get("totalPlwiktBacklinks"));

		var plwiktBacklinks = plwikiToPlwiktBacklinks.values().stream()
			.flatMap(Set::stream)
			.distinct()
			.filter(backlink -> !plwiktToParsedPlwiki.containsKey(backlink))
			.toList();

		removeFoundOccurrences(plwiktToParsedPlwiki, plwikiToPlwiktBacklinks, plwikiRedirToTarget);

		stats.put("filteredTitles", plwiktToParsedPlwiki.size());
		System.out.printf("Filtered plwiktionary-to-plwikipedia list: %d%n", stats.get("filteredTitles"));

		var plwiktMissing = getMissingPlwiktPages(plwiktBacklinks);

		stats.put("missingPlwiktBacklinks", plwikiMissing.size());
		System.out.printf("Missing plwiktionary backlinks: %d%n", stats.get("missingPlwiktBacklinks"));

		var entries = makeEntryList(plwiktToParsedPlwiki, plwikiToPlwiktBacklinks, plwiktMissing, plwikiMissing, plwikiRedirToTarget, plwikiDisambigs);
		storeData(entries, stats);
	}

	private static Map<String, Set<String>> buildTargetMap(List<PageContainer> pages) {
		var coll = Collator.getInstance(new Locale("pl", "PL"));
		var map = new TreeMap<String, Set<String>>(coll);

		for (var page : pages) {
			var p = Page.wrap(page);
			Section s;

			try {
				s = p.getPolishSection().get();
			} catch (NoSuchElementException e) {
				continue;
			}

			var set = new TreeSet<String>();

			for (var template : ParseUtils.getTemplates("wikipedia", s.toString())) {
				var params = ParseUtils.getTemplateParametersWithValue(template);
				var param = params.getOrDefault("ParamWithoutName1", "");

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

	private static List<String> analyzePlwikiPageProps(List<String> parsedPlwikiTitles, List<Map<String, String>> pageProps,
			List<String> plwikiResolvedRedirs, Map<String, String> redirToTarget, Set<String> disambigs, Set<String> missing) {
		var list = new ArrayList<String>(pageProps.size());

		for (var i = 0; i < pageProps.size(); i++) {
			var props = pageProps.get(i);
			var inputpagename = parsedPlwikiTitles.get(i);
			var resolvedRedir = plwikiResolvedRedirs.get(i);
			
			if (props == null) {
				missing.add(inputpagename);
				continue;
			}
			
			try {
				var pagename = plwiki.normalize(inputpagename);
				
				if (!pagename.equals(resolvedRedir)) {
					redirToTarget.put(inputpagename, resolvedRedir);
				}
				
				if (props.containsKey("disambiguation")) {
					disambigs.add(pagename);
				}
				
				list.add(pagename);
			} catch (IllegalArgumentException e) {
				continue;
			}
		}

		return list;
	}

	private static Map<String, Set<String>> retrievePlwiktBacklinks(List<PageContainer> pages) {
		return pages.stream().collect(Collectors.toMap(
			PageContainer::getTitle,
			pc -> getPlwiktBacklinks(pc.getTitle(), pc.getText()))
		);
	}

	private static Set<String> getPlwiktBacklinks(String title, String text) {
		var set = new HashSet<String>();

		for (var templateEntry : TARGET_TEMPLATES.entrySet()) {
			var templateName = templateEntry.getKey();
			var params = templateEntry.getValue();
			var templates = ParseUtils.getTemplatesIgnoreCase(templateName, text);

			for (var template : templates) {
				var paramMap = ParseUtils.getTemplateParametersWithValue(template);

				if (params != null) {
					for (var param : params) {
						var value = paramMap.getOrDefault(param, "");

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
					for (var paramEntry : paramMap.entrySet()) {
						var paramName = paramEntry.getKey();

						if (paramName.matches("^ParamWithoutName\\d+$")) {
							var value = paramEntry.getValue();

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
		for (var e : plwiktToPlwiki.entrySet()) {
			var plwiktTitle = e.getKey();
			var it = e.getValue().iterator();

			while (it.hasNext()) {
				var plwikiTitle = it.next();
				plwikiTitle = redirToTarget.getOrDefault(plwikiTitle, plwikiTitle);
				var backlinks = plwikiToPlwikt.get(plwikiTitle);

				if (backlinks != null && backlinks.contains(plwiktTitle)) {
					it.remove();
				}
			}
		}

		plwiktToPlwiki.entrySet().removeIf(e -> e.getValue().isEmpty());
	}

	private static Set<String> getMissingPlwiktPages(List<String> titles) throws IOException {
		var set = new HashSet<String>();
		var exist = plwikt.exists(titles);

		for (var i = 0; i < titles.size(); i++) {
			var title = titles.get(i);

			if (!exist[i]) {
				set.add(title);
			}
		}

		return set;
	}

	private static List<Entry> makeEntryList(Map<String, Set<String>> plwiktToPlwiki, Map<String, Set<String>> plwikiToPlwikt,
			Set<String> plwiktMissing, Set<String> plwikiMissing, Map<String, String> redirToTarget, Set<String> plwikiDisambigs) {
		List<Entry> entries = new ArrayList<>(plwiktToPlwiki.size());

		for (var e : plwiktToPlwiki.entrySet()) {
			var plwiktTitle = e.getKey();

			for (var articleOnPlwiki : e.getValue()) {
				var entry = new Entry();
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
								TreeMap::new)
							);
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
		var xstream = new XStream(new StaxDriver());
		xstream.processAnnotations(Entry.class);

		try (var bos = new BufferedOutputStream(Files.newOutputStream(LOCATION.resolve("entries.xml")))) {
			xstream.toXML(entries, bos);
		}

		Files.write(LOCATION.resolve("stats.xml"), List.of(xstream.toXML(stats)));
		Files.write(LOCATION.resolve("templates.xml"), List.of(xstream.toXML(TARGET_TEMPLATES)));
		Files.write(LOCATION.resolve("timestamp.xml"), List.of(xstream.toXML(OffsetDateTime.now())));

		Files.deleteIfExists(LOCATION.resolve("UPDATED"));
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
