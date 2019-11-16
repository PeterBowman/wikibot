package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public final class MissingPolishAudio {
	private static final String LOCATION = "./data/tasks.plwikt/MissingPolishAudio/";
	private static final List<String> REG_CATEGORIES = List.of("Regionalizmy polskie", "Dialektyzmy polskie");
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		final String domain = "pl.wiktionary.org";
		wb = Login.createSession(domain);
		
		int stats = wb.getSiteStatistics().get("pages");
		XMLDumpReader reader = new XMLDumpReader(domain);
		
		Map<String, List<String>> regMap = categorizeRegWords();
		ConcurrentMap<String, Set<String>> targetMap = new ConcurrentHashMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getPolishSection().stream())
				.flatMap(s -> s.getField(FieldTypes.PRONUNCIATION).stream())
				.filter(f -> f.isEmpty() || ParseUtils.getTemplates("audio", f.getContent()).isEmpty())
				.map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
				.forEach(title -> categorizeTargets(title, regMap, targetMap));
		}
		
		writeLists(targetMap, extractTimestamp(reader.getFile()));
	}
	
	private static String normalize(String category) {
		return wb.removeNamespace(category).toLowerCase().replaceAll("[ -]+", "-");
	}
	
	private static Map<String, List<String>> categorizeRegWords() throws IOException {
		Map<String, List<String>> map = new HashMap<>();
		
		for (String mainCat : REG_CATEGORIES) {
			for (String subCat : wb.getCategoryMembers(mainCat, Wiki.CATEGORY_NAMESPACE)) {
				for (String title : wb.getCategoryMembers(subCat, Wiki.MAIN_NAMESPACE)) {
					map.computeIfAbsent(title, k -> new ArrayList<>()).add(normalize(subCat));
				}
			}
			
			for (String title : wb.getCategoryMembers(mainCat, Wiki.MAIN_NAMESPACE)) {
				map.computeIfAbsent(title, k -> new ArrayList<>()).add(normalize(mainCat));
			}
		}
		
		return map;
	}
	
	private static void categorizeTargets(String title, Map<String, List<String>> regMap, ConcurrentMap<String, Set<String>> targetMap) {
		if (!regMap.containsKey(title)) {
			targetMap.computeIfAbsent("ogólnopolskie", k -> new ConcurrentSkipListSet<>()).add(title);
		} else {
			for (String category : regMap.get(title)) {
				targetMap.computeIfAbsent(category, k -> new ConcurrentSkipListSet<>()).add(title);
			}
		}
	}
	
	private static String extractTimestamp(File f) {
		String fileName = f.getName();
		Pattern patt = Pattern.compile("^[a-z]+-(\\d+)-.+");
		String errorString = "brak-daty";

		Matcher m = patt.matcher(fileName);

		if (!m.matches()) {
			return errorString;
		}

		String canonicalTimestamp = m.group(1);

		try {
			SimpleDateFormat originalDateFormat = new SimpleDateFormat("yyyyMMdd");
			Date date = originalDateFormat.parse(canonicalTimestamp);
			SimpleDateFormat desiredDateFormat = new SimpleDateFormat("dd-MM-yyyy");
			return desiredDateFormat.format(date);
		} catch (java.text.ParseException e) {
			return errorString;
		}
	}
	
	private static void writeLists(Map<String, Set<String>> map, String timestamp) throws IOException {
		for (var entry : map.entrySet()) {
			String filename = String.format("%s-%s.txt", entry.getKey(), timestamp);
			String output = entry.getValue().stream().map(s -> String.format("#%s", s)).collect(Collectors.joining(" "));
			Files.write(Paths.get(LOCATION + filename), Arrays.asList(output));
		}
	}
}
