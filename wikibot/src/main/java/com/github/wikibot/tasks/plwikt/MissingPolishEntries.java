package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.MorfeuszLookup;
import com.github.wikibot.utils.MorfeuszRecord;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public class MissingPolishEntries {
	private static final Path DUMPS_PATH = Paths.get("./data/dumps/");
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishEntries/");
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/brakujące polskie";
	private static final Pattern P_DUMP_FILE = Pattern.compile("^sgjp-\\d{8}\\.tab\\.gz$");
	
	private static final String PAGE_INTRO;
	private static final Collator COLLATOR_PL;
	private static final LocalizedNumberFormatter NUMBER_FORMAT_PL;
	
	private static Stats stats = new Stats();
	
	private static Wikibot wb;
	
	static {
		PAGE_INTRO = """
			{{TOCright}}{{język linków|polski}}
			
			Spis brakujących haseł polskich na podstawie bazy danych Morfeusz SGJP ([http://morfeusz.sgjp.pl/download/ <tt>%1$s</tt>]).
			Poniższe strony istnieją, lecz brak sekcji polskiej. 
			* stron w sumie (przestrzeń główna wraz z przekierowaniami): %2$s
			* haseł polskich: %3$s (podstawowe), %4$s (formy fleksyjne), %5$s (przekierowania), %6$s (łącznie) 
			* haseł w bazie SGJP: %7$s (wraz z formami odmienionymi: %8$s) 
			* rozmiar listy: %9$s
			Wygenerowano ~~~~~.
			""";
		
		COLLATOR_PL = Collator.getInstance(new Locale("pl", "PL"));
		COLLATOR_PL.setStrength(Collator.SECONDARY);
		
		NUMBER_FORMAT_PL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");
		
		var titles = retrieveNonPolishEntries();
		retainSgjpEntries(titles);
		
		System.out.println(stats);
		
		int hash = titles.hashCode();
		Path fHash = LOCATION.resolve("hash.ser");
		
		if (Files.exists(fHash) && (int) Misc.deserialize(fHash) == hash) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(hash, fHash);
			Misc.serialize(titles, LOCATION.resolve("titles.ser"));
			System.out.printf("%d titles stored.%n", titles.size());
		}

		String out = getOutput(titles);

		wb.setMarkBot(false);		
		wb.edit(TARGET_PAGE, out, "aktualizacja");
	}
	
	private static Set<String> retrieveNonPolishEntries() throws IOException {
		var allTitles = wb.listPages("", null, 0, null, null, null); // contains redirs
		var polishTitles = wb.getCategoryMembers("polski (indeks)", 0);
		var polishInflected = wb.getCategoryMembers("polski (formy fleksyjne)", 0);
		var polishRedirs = findRedirects(polishTitles);
		
		var filtered = new HashSet<>(allTitles);
		filtered.removeAll(polishTitles);
		filtered.removeAll(polishInflected);
		filtered.removeAll(polishRedirs);
		
		stats.allEntries = allTitles.size();
		stats.polishLemmas = polishTitles.size();
		stats.polishInflected = polishInflected.size();
		stats.polishRedirs = polishRedirs.size();
		
		return filtered;
	}
	
	private static List<String> findRedirects(List<String> polishTitles) throws IOException {
		List<String> allRedirs = wb.listPages("", null, 0, null, null, Boolean.TRUE);
		List<String> resolvedRedirs = wb.resolveRedirects(allRedirs);
		
		List<String> polishRedirs = new ArrayList<>(2000);
		Set<String> set = new HashSet<>(polishTitles);
		
		for (int i = 0; i < allRedirs.size(); i++) {
			String redir = allRedirs.get(i);
			String resolvedRedir = resolvedRedirs.get(i);
			
			if (set.contains(resolvedRedir)) {
				polishRedirs.add(redir);
			}
		}
		
		return polishRedirs;
	}
	
	private static void retainSgjpEntries(Set<String> titles) throws IOException {
		Path dumpFile = getLatestDumpFile();
		MorfeuszLookup morfeuszLookup = new MorfeuszLookup(dumpFile);
		
		final Set<String> database;
		
		try (var stream = morfeuszLookup.stream()) {
			database = stream.map(MorfeuszRecord::getLemma).collect(Collectors.toSet());
		}
		
		titles.retainAll(database);
		
		stats.databaseLemmas = database.size();
		stats.worklistSize = titles.size();
		stats.dumpFile = morfeuszLookup.getPath().getFileName().toString();
		
		// cheap enough
		try (var stream = morfeuszLookup.stream()) {
			stats.databaseOverall = (int)stream.count();
		}
	}
	
	private static Path getLatestDumpFile() throws IOException {
		try (var stream = Files.list(DUMPS_PATH)) {
			return stream
				.sorted(Comparator.reverseOrder())
				.filter(path -> P_DUMP_FILE.matcher(path.getFileName().toString()).matches())
				.findFirst()
				.orElseThrow();
		}
	}
	
	private static String getOutput(Set<String> titles) {
		Map<String, List<String>> map = titles.stream()
			.collect(Collectors.groupingBy(
				title -> Character.toString(title.charAt(0)).toLowerCase(),
				() -> new TreeMap<>(COLLATOR_PL),
				Collectors.toList()
			));
		
		String out = map.entrySet().stream()
			.map(e -> String.format(
					"== %s ==\n%s",
					e.getKey(),
					e.getValue().stream()
						.sorted(COLLATOR_PL)
						.map(v -> String.format("[[%s]]", v))
						.collect(Collectors.joining(", ")))
				)
				.collect(Collectors.joining("\n\n"));
		
		return String.format(PAGE_INTRO,
				stats.dumpFile,
				NUMBER_FORMAT_PL.format(stats.allEntries),
				NUMBER_FORMAT_PL.format(stats.polishLemmas),
				NUMBER_FORMAT_PL.format(stats.polishInflected),
				NUMBER_FORMAT_PL.format(stats.polishRedirs),
				NUMBER_FORMAT_PL.format(stats.polishOverall()),
				NUMBER_FORMAT_PL.format(stats.databaseLemmas),
				NUMBER_FORMAT_PL.format(stats.databaseOverall),
				NUMBER_FORMAT_PL.format(stats.worklistSize)
			) + "\n" + out;
	}
	
	private static class Stats {
		int allEntries;
		int polishLemmas;
		int polishInflected;
		int polishRedirs;
		int databaseLemmas;
		int databaseOverall;
		int worklistSize;
		
		String dumpFile;
		
		public int polishOverall() {
			return polishLemmas + polishInflected + polishRedirs;
		}
		
		@Override
		public String toString() {
			return String.format("""
				Stats for the current run:
				* reading from: %s
				* total worklist size: %d
				* all entries: %d
				* polish entries (lemmas): %d
				* polish entries (inflected): %d
				* polish entries (redirs): %d
				* polish entries (overall): %d
				* database lemmas: %d
				* database overall size: $d
				""",
				dumpFile,
				worklistSize, allEntries, polishLemmas, polishInflected, polishRedirs,
				polishOverall(), databaseLemmas, databaseOverall
			).stripTrailing();
		}
	}
}
