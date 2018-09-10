package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class MissingPolishEtymOnWikidata {
	private static final String LOCATION = "./data/tasks.plwikt/MissingPolishEtymOnWikidata/";
	private static final String TARGET_PAGE = "Wikisłownikarz:PBbot/brak polskiej etymologii na Wikidanych";

	// https://www.wikidata.org/wiki/Q809
	private static final String POLISH_LANGUAGE_ITEM = "Q809";

	// https://www.wikidata.org/wiki/Property:P5191
	// https://www.wikidata.org/wiki/Property:P5238
	private static final List<String> TARGET_PROPERTIES = Arrays.asList("P5191", "P5238");

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
		PageContainer[] wdPages = wd.getContentOfBacklinks(POLISH_LANGUAGE_ITEM, namespaceIds.get("Lexeme"));

		Map<String, Set<String>> wiktToLexemes = Stream.of(wdPages)
			.map(PageContainer::getText)
			.map(JSONObject::new)
			.filter(json -> {
				JSONObject obj = json.getJSONObject("claims");
				return !TARGET_PROPERTIES.stream().anyMatch(obj::has);
			})
			.collect(Collectors.groupingBy(
				json -> (String)json.getJSONObject("lemmas")
					.getJSONObject("pl")
					.get("value"),
				Collectors.mapping(
					json -> (String)json.get("id"),
					Collectors.toCollection(TreeSet::new)
				)
			));

		Wikibot plwikt = Login.createSession("pl.wiktionary.org");
		String[] wiktTitles = wiktToLexemes.keySet().toArray(new String[wiktToLexemes.size()]);
		PageContainer[] wiktContent = plwikt.getContentOfPages(wiktTitles);

		Map<String, String> map = Stream.of(wiktContent)
			.map(Page::wrap)
			.flatMap(p -> Utils.streamOpt(p.getPolishSection()))
			.flatMap(s -> Utils.streamOpt(s.getField(FieldTypes.ETYMOLOGY)))
			.filter(f -> !f.isEmpty())
			.collect(Collectors.toMap(
				f -> f.getContainingSection().get().getContainingPage().get().getTitle(),
				Field::getContent,
				(a, b) -> a,
				() -> new TreeMap<>(Misc.getCollator("pl"))));

		File fHash = new File(LOCATION + "hash.ser");

		if (fHash.exists() && (int)Misc.deserialize(fHash) == map.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(map.hashCode(), fHash);
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
