package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.mutable.MutableInt;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.MorfeuszLookup;

public class MissingPolishEntries {
	private static final String DUMPS_PATH = "./data/dumps/";
	private static final String LOCATION = "./data/tasks.plwikt/MissingPolishEntries/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/brakujące polskie";
	
	private static final String PAGE_INTRO;
	private static final Collator COLLATOR_PL;
	private static final boolean ALLOW_COMPRESSION = false;
	
	private static Stats stats = new Stats();
	
	private static Wikibot wb;
	
	static {
		PAGE_INTRO =
			"{{TOCright}}{{język linków|polski}}\n\n" + 
			"Spis brakujących haseł polskich na podstawie bazy danych Morfeusz SGJP " +
			"([http://sgjp.pl/morfeusz/dopobrania.html <tt>%1$s</tt>]). Poniższe strony istnieją, " +
			"lecz brak sekcji polskiej.\n" + 
			"* stron w sumie (przestrzeń główna wraz z przekierowaniami): %2$s\n" + 
			"* haseł polskich: %3$s (podstawowe), %4$s (formy fleksyjne), %5$s (przekierowania), %6$s (łącznie)\n" + 
			"* hasła w bazie SGJP: %7$s (wraz z formami odmienionymi: %8$s)\n" + 
			"* rozmiar listy: %9$s\n" + 
			"Wygenerowano ~~~~~.";
		
		COLLATOR_PL = Misc.getCollator("pl");
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");
		
		List<String> titles = retrieveNonPolishEntries();
		retainSgjpEntries(titles);
		
		System.out.println(stats);
		Collections.sort(titles, COLLATOR_PL);
		
		int hash = titles.hashCode();
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int) Misc.deserialize(fHash) == hash) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(hash, fHash);
			Misc.serialize(titles, LOCATION + "titles.ser");
			System.out.printf("%d titles stored.%n", titles.size());
		}

		String out = getOutput(titles);

		wb.setMarkBot(false);		
		wb.edit(TARGET_PAGE, out, "aktualizacja");
	}
	
	private static List<String> retrieveNonPolishEntries() throws IOException {
		List<String> titles = new ArrayList<>();
		
		String[] allTitles = wb.listPages("", null, 0, null, null, null); // contains redirs
		String[] polishTitles = wb.getCategoryMembers("polski (indeks)", 0);
		String[] polishInflected = wb.getCategoryMembers("polski (formy fleksyjne)", 0);
		String[] polishRedirs = resolveRedirects(polishTitles);
		
		Set<String> set = new HashSet<>();
		set.addAll(Arrays.asList(allTitles));
		set.removeAll(Arrays.asList(polishTitles));
		set.removeAll(Arrays.asList(polishInflected));
		set.removeAll(Arrays.asList(polishRedirs));
		
		titles.addAll(set);
		
		stats.allEntries = allTitles.length;
		stats.polishLemmas = polishTitles.length;
		stats.polishInflected = polishInflected.length;
		stats.polishRedirs = polishRedirs.length;
		
		return titles;
	}
	
	private static String[] resolveRedirects(String[] polishTitles) throws IOException {
		String[] allRedirs = wb.listPages("", null, 0, null, null, Boolean.TRUE);
		String[] resolvedRedirs = wb.resolveRedirects(allRedirs);
		
		List<String> polishRedirs = new ArrayList<>(2000);
		Set<String> set = new HashSet<>(Arrays.asList(polishTitles));
		
		for (int i = 0; i < allRedirs.length; i++) {
			String redir = allRedirs[i];
			String resolvedRedir = resolvedRedirs[i];
			
			if (resolvedRedir != null && set.contains(resolvedRedir)) {
				polishRedirs.add(redir);
			}
		}
		
		return polishRedirs.toArray(new String[polishRedirs.size()]);
	}
	
	private static void retainSgjpEntries(List<String> titles) throws IOException {
		Set<String> database = new HashSet<>(350000);
		String dumpFile = getLatestDumpFile();
		
		MorfeuszLookup morfeuszLookup = new MorfeuszLookup(DUMPS_PATH + dumpFile);
		morfeuszLookup.setCompression(ALLOW_COMPRESSION);
		
		MutableInt count = new MutableInt();
		
		morfeuszLookup.find(arr -> {
			String entry = (String)arr[1];
			database.add(entry);
			count.increment();
		});
		
		stats.databaseLemmas = database.size();
		stats.databaseOverall = count.intValue();
		
		titles.retainAll(database); // slow, but could be worse
		
		stats.worklistSize = titles.size();
		stats.dumpFile = morfeuszLookup.getFileName();
	}
	
	private static String getLatestDumpFile() throws IOException {
		String regex = "sgjp-\\d{8}\\.tab";
		
		if (ALLOW_COMPRESSION) {
			regex += "\\.gz";
		}
		
		File[] files = Paths.get(DUMPS_PATH).toFile().listFiles(file -> file.isFile() && file.getName().matches(regex));
		
		if (files.length == 0) {
			return null;
		} else if (files.length == 1) {
			return files[0].getName();
		}
		
		Comparator<File> fileComparator = Comparator.comparing(File::getName);
		Arrays.sort(files, fileComparator.reversed());
		
		return files[0].getName();
	}
	
	private static String getOutput(List<String> titles) {
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
						.map(v -> String.format("[[%s]]", v))
						.collect(Collectors.joining(", ")))
				)
				.collect(Collectors.joining("\n\n"));
		
		return String.format(PAGE_INTRO, stats.dumpFile, Misc.makePluralPL(stats.allEntries),
				Misc.makePluralPL(stats.polishLemmas), Misc.makePluralPL(stats.polishInflected),
				Misc.makePluralPL(stats.polishRedirs), Misc.makePluralPL(stats.polishOverall()),
				Misc.makePluralPL(stats.databaseLemmas), Misc.makePluralPL(stats.databaseOverall),
				Misc.makePluralPL(stats.worklistSize)
			) + "\n\n" + out;
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
			return "Stats for the current run:\n"
				+ "* reading from: " + dumpFile + "\n"
				+ "* total worklist size: " + worklistSize + "\n"
				+ "* all entries: " + allEntries + "\n"
				+ "* polish entries (lemmas): " + polishLemmas + "\n"
				+ "* polish entries (inflected): " + polishInflected + "\n"
				+ "* polish entries (redirs): " + polishRedirs + "\n"
				+ "* polish entries (overall): " + polishOverall() + "\n"
				+ "* database lemmas: " + databaseLemmas + "\n"
				+ "* database overall size: " + databaseOverall;
		}
	}
}
