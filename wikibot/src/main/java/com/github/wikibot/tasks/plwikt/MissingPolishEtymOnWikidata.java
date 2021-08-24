package com.github.wikibot.tasks.plwikt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.json.JSONObject;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class MissingPolishEtymOnWikidata {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishEtymOnWikidata/");
	private static final String TARGET_PAGE = "Wikisłownikarz:PBbot/brak polskiej etymologii na Wikidanych";

	// https://www.wikidata.org/wiki/Q809
	private static final String POLISH_LANGUAGE_ITEM = "Q809";

	// https://www.wikidata.org/wiki/Property:P5191
	// https://www.wikidata.org/wiki/Property:P5238
	private static final List<String> TARGET_PROPERTIES = List.of("P5191", "P5238");

	private static final String TARGET_PAGE_INTRO;

	static {
		TARGET_PAGE_INTRO = "Zawartość pola etymologii haseł polskich z brakującą etymologią w Wikidanych. " +
			"Wygenerowano ~~~~~.\n" +
			"----\n" +
			"%1$s" +
			"\n\n{{przypisy}}";
	}

	public static void main(String[] args) throws Exception {
		Wikibot wd = Login.createSession("www.wikidata.org");
		Map<String, Integer> namespaceIds = wd.getNamespaces();
		List<PageContainer> wdPages = wd.getContentOfBacklinks(POLISH_LANGUAGE_ITEM, namespaceIds.get("Lexeme"));

		Map<String, Set<String>> wiktToLexemes = wdPages.stream()
			.map(PageContainer::getText)
			.map(JSONObject::new)
			.filter(json -> Optional.ofNullable(json.optJSONObject("claims"))
				.filter(obj -> !TARGET_PROPERTIES.stream().anyMatch(obj::has))
				.isPresent()
			)
			.filter(json -> json.getJSONObject("lemmas").has("pl")) // e.g. L33443: "polština"
			.collect(Collectors.groupingBy(
				json -> json.getJSONObject("lemmas")
					.getJSONObject("pl")
					.getString("value"),
				Collectors.mapping(
					json -> json.getString("id"),
					Collectors.toCollection(TreeSet::new)
				)
			));

		Wikibot plwikt = Login.createSession("pl.wiktionary.org");
		List<String> wiktTitles = new ArrayList<>(wiktToLexemes.keySet());
		List<PageContainer> wiktContent = plwikt.getContentOfPages(wiktTitles);

		Map<String, String> map = wiktContent.stream()
			.map(Page::wrap)
			.map(Page::getPolishSection)
			.flatMap(Optional::stream)
			.flatMap(s -> s.getField(FieldTypes.ETYMOLOGY).stream())
			.filter(f -> !f.isEmpty())
			.collect(Collectors.toMap(
				f -> f.getContainingSection().get().getContainingPage().get().getTitle(),
				Field::getContent,
				(a, b) -> a,
				() -> new TreeMap<>(Collator.getInstance(new Locale("pl", "PL")))));

		Path hash = LOCATION.resolve("hash.ser");

		if (Files.exists(hash) && (int) Misc.deserialize(hash) == map.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(map.hashCode(), hash);
			System.out.printf("%d results stored.%n", map.size());
		}

		String out = map.entrySet().stream()
			.map(e -> String.format("#[[%s]] (%s)\n%s",
				e.getKey(),
				wiktToLexemes.get(e.getKey()).stream()
					.map(lexeme -> String.format("[[d:Lexeme:%s]]", lexeme))
					.collect(Collectors.joining(", ")),
				Pattern.compile("\n").splitAsStream(e.getValue())
					.map(line -> "#" + (line.startsWith(":") ? "" : ":") + line)
					.collect(Collectors.joining("\n"))))
			.collect(Collectors.joining("\n"));

		plwikt.setMarkBot(false);
		plwikt.edit(TARGET_PAGE, String.format(TARGET_PAGE_INTRO, out), "aktualizacja");
	}
}
