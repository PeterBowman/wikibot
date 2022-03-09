package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;
import com.thoughtworks.xstream.XStream;

public final class UnsourcedUkrainianEntries {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/UnsourcedUkrainianEntries/");
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/nieuźródłowione hasła ukraińskie";
	private static final String SOURCES_CATEGORY = "Szablony źródeł (ukraiński)";
	private static final String IGNORED_DEF_TEMPLATES_CATEGORY = "Szablony nagłówków form fleksyjnych";
	private static final Pattern PATT_NEWLINE = Pattern.compile("\n");
	private static final int RESULT_LIMIT = 1000;
	private static final int BATCH_SIZE = 10;
	
	private static final Plural PLURAL_PL;
	private static final LocalizedNumberFormatter NUMBER_FORMAT_PL;
	private static final String PAGE_INTRO;
	
	private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
	
	static {
		PAGE_INTRO = """
			Hasła ukraińskie, w których przynajmniej jedno znaczenie nie zostało uźródłowione przy użyciu
			dowolnego szablonu z [[:Kategoria:Szablony źródeł (ukraiński)]].
			
			Znaleziono %s. Aktualizacja: ~~~~~.
			__NOEDITSECTION__
			{{TOCright}}
			{{język linków|ukraiński}}
			
			%s
			""";
		
		var polishWords = new WordForms[] {
			new WordForms(new String[] {"hasło", "hasła", "haseł"}),
			new WordForms(new String[] {"wynik", "wyniki", "wyników"})
		};
		
		PLURAL_PL = new Plural(PluralRules.POLISH, polishWords);
		NUMBER_FORMAT_PL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
	}
	
	public static void main(String[] args) throws Exception {
		Login.login(wb);
		
		var sourceTmpls = wb.getCategoryMembers(SOURCES_CATEGORY, Wiki.TEMPLATE_NAMESPACE).stream()
			.map(wb::removeNamespace)
			.toList();
		
		var ignoredHeaderTmpls = wb.getCategoryMembers(IGNORED_DEF_TEMPLATES_CATEGORY, Wiki.TEMPLATE_NAMESPACE).stream()
			.map(wb::removeNamespace)
			.toList();
		
		System.out.printf("%d templates: %s%n", sourceTmpls.size(), sourceTmpls);
		
		var titles =  wb.getContentOfCategorymembers("ukraiński (indeks)", Wiki.MAIN_NAMESPACE).stream()
			.map(Page::wrap)
			.flatMap(p -> p.getSection("ukraiński", true).stream())
			.filter(s -> !hasAllDefinitionsSourced(s, sourceTmpls, ignoredHeaderTmpls))
			.map(f -> f.getContainingPage().get().getTitle())
			.sorted(Collator.getInstance(new Locale("uk")))
			.toList();
		
		var sublist = titles.subList(0, RESULT_LIMIT);
		
		if (!checkAndUpdateStoredData(titles, sublist)) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		wb.setMarkBot(false);
		wb.edit(TARGET_PAGE, makePageText(sublist, titles.size()), "aktualizacja");
	}
	
	private static boolean hasAllDefinitionsSourced(Section section, List<String> sourceTmpls, List<String> ignoredHeaderTmpls) {
		var namedRefs = Jsoup.parseBodyFragment(section.toString()).select("ref[name]").stream()
			.filter(el -> elementHasAnyTemplate(el, sourceTmpls))
			.map(el -> el.attr("name"))
			.filter(attr -> !attr.isBlank())
			.collect(Collectors.toSet());
		
		var defsContent = section.getField(FieldTypes.DEFINITIONS).get().getContent();
		var stripped = stripIgnoredDefinitionLines(defsContent, ignoredHeaderTmpls);
		
		return PATT_NEWLINE.splitAsStream(stripped)
			.allMatch(line -> !Optional.of(Jsoup.parseBodyFragment(line))
				.map(doc -> doc.getElementsByTag("ref"))
				.filter(els -> els.stream().anyMatch(el -> elementHasAnyTemplateOrNamedGroup(el, sourceTmpls, namedRefs)))
				.isEmpty()
			);
	}
	
	private static String stripIgnoredDefinitionLines(String text, List<String> ignoredHeaderTmpls) {
		var lines = new ArrayList<String>();
		boolean ignoring = false;
		
		for (var line : PATT_NEWLINE.split(text)) {
			if (line.startsWith(":")) {
				if (!ignoring) {
					lines.add(line);
				}
			} else if (ignoredHeaderTmpls.stream().anyMatch(tmpl -> !ParseUtils.getTemplates(tmpl, line).isEmpty())) {
				ignoring = true;
			} else {
				ignoring = false;
			}
		}
		
		return String.join("\n", lines);
	}
	
	private static boolean elementHasAnyTemplateOrNamedGroup(Element el, List<String> templates, Set<String> namedRefs) {
		var name = el.attr("name").trim();
		
		// <ref name=aa/> was being expanded to <ref name="aa/"></ref> instead of <ref name="aa"></ref>
		name = name.replaceFirst("/$", "");
		
		if (!name.isEmpty()) {
			return namedRefs.contains(name);
		} else {
			return elementHasAnyTemplate(el, templates);
		}
	}
	
	private static boolean elementHasAnyTemplate(Element el, List<String> templates) {
		return templates.stream().anyMatch(template -> !ParseUtils.getTemplates(template, el.text()).isEmpty());
	}
	
	private static boolean checkAndUpdateStoredData(List<String> list, List<String> sublist) throws IOException {
		int newHashCode = sublist.hashCode();
		int storedHashCode;
		
		Path fHash = LOCATION.resolve("hash.txt");
		Path fList = LOCATION.resolve("list.xml");
		
		try {
			storedHashCode = Integer.parseInt(Files.readString(fHash));
		} catch (IOException | NumberFormatException e) {
			e.printStackTrace();
			storedHashCode = 0;
		}
		
		XStream xstream = new XStream();
		
		if (storedHashCode != newHashCode) {
			Files.writeString(fHash, Integer.toString(newHashCode));
			Files.writeString(fList, xstream.toXML(list));
			return true;
		} else {
			return false;
		}
	}
	
	private static String makePageText(List<String> titles, int originalSize) {
		String resultsComment = String.format("%s %s", NUMBER_FORMAT_PL.format(originalSize), PLURAL_PL.pl(originalSize, "hasło"));
		
		if (originalSize > RESULT_LIMIT) {
			resultsComment += String.format(" (wyświetlono pierwsze %s %s)",
				NUMBER_FORMAT_PL.format(RESULT_LIMIT),
				PLURAL_PL.pl(RESULT_LIMIT, "wynik"));
		}
		
		// https://stackoverflow.com/a/43057913
		var results = IntStream.range(0, (titles.size() + BATCH_SIZE - 1) / BATCH_SIZE)
			.mapToObj(i -> titles.subList(i * BATCH_SIZE, Math.min(BATCH_SIZE * (i + 1), titles.size())))
			.map(batch -> String.format("===%s/%s===\n%s\n", batch.get(0), batch.get(batch.size() - 1), batch.stream()
				.map(item -> String.format("#[[%s]]", item))
				.collect(Collectors.joining("\n"))
			))
			.collect(Collectors.joining("\n"));
		
		return String.format(PAGE_INTRO, resultsComment, results);
	}
}
