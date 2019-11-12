package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class MissingPolishExamples {
	private static final Pattern P_LINKER = Pattern.compile("\\[\\[\\s*?([^\\]\\|]+)\\s*?(?:\\|\\s*?((?:]?[^\\]\\|])*+))*\\s*?\\]\\]([^\\[]*)", Pattern.DOTALL);
	private static final String LOCATION = "./data/tasks.plwikt/MissingPolishExamples/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/brakujące polskie przykłady";
	private static final String PAGE_INTRO;
	
	private static Wikibot wb;

	static {
		PAGE_INTRO =
			"{{język linków|polski}}\n" +
			"Spis haseł polskich, które:\n" +
			"* nie mają żadnych przykładów w polu '''przykłady'''\n" +
			"* do strony linkują inne hasła z pola '''przykłady''' (włączając w to inne języki)\n" +
			"Treść przykładów można wygodnie wyszukać i wstawić w haśle docelowym za pomocą gadżetu „{{int:gadget-edit-form-launcher}}”.\n\n" +
			"Wygenerowano ~~~~~ na podstawie zrzutu z bazy danych z dnia %s.\n" +
			"----\n";
	}
	
	public static void main(String[] args) throws Exception {
		final String domain = "pl.wiktionary.org";
		wb = Login.createSession(domain);

		XMLDumpReader reader = new XMLDumpReader(domain);
		int stats = wb.getSiteStatistics().get("pages");
		
		final Set<String> titles;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			titles = stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getPolishSection().stream())
				.flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
				.filter(Field::isEmpty)
				.map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
				.collect(Collectors.toSet());
		}
		
		System.out.printf("%d titles retrieved\n", titles.size());
		
		ConcurrentMap<String, Set<String>> titlesToBacklinks = new ConcurrentSkipListMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
				.filter(f -> !f.isEmpty())
				.forEach(f -> P_LINKER.matcher(f.getContent()).results()
					.map(m -> m.group(1))
					.filter(titles::contains)
					.forEach(target -> titlesToBacklinks.computeIfAbsent(target, k -> new ConcurrentSkipListSet<>())
						.add(String.format("%s#%s",
							f.getContainingSection().get().getContainingPage().get().getTitle(),
							f.getContainingSection().get().getLang()))
					)
				);
		}
		
		System.out.printf("%d titles mapped to backlinks\n", titlesToBacklinks.size());
		
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int)Misc.deserialize(fHash) == titlesToBacklinks.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		Misc.serialize(titlesToBacklinks.hashCode(), fHash);
		
		String out = String.format(PAGE_INTRO, extractTimestamp(reader.getFile())) + titlesToBacklinks.entrySet().stream()
			.map(e -> String.format("# [[%s]]: %s", e.getKey(), e.getValue().stream()
				.map(v -> String.format("[[%s]]", v)).collect(Collectors.joining(", "))
			))
			.collect(Collectors.joining("\n"));
		
		wb.setMarkBot(true);
		wb.setMarkMinor(false);
		
		wb.edit(TARGET_PAGE, out, "aktualizacja");
	}
	
	private static String extractTimestamp(File f) {
		String fileName = f.getName();
		Pattern patt = Pattern.compile("^[a-z]+-(\\d+)-.+");
		String errorString = String.format("(błąd odczytu sygnatury czasowej, plik ''%s'')", fileName);
		
		Matcher m = patt.matcher(fileName);
		
		if (!m.matches()) {
			return errorString;
		}
		
		String canonicalTimestamp = m.group(1);
		
		try {
			SimpleDateFormat originalDateFormat = new SimpleDateFormat("yyyyMMdd");
			Date date = originalDateFormat.parse(canonicalTimestamp);
			SimpleDateFormat desiredDateFormat = new SimpleDateFormat("dd/MM/yyyy");
			return desiredDateFormat.format(date);
		} catch (java.text.ParseException e) {
			return errorString;
		}
	}
}
