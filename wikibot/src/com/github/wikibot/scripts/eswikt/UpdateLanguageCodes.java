package com.github.wikibot.scripts.eswikt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;
import com.univocity.parsers.tsv.TsvWriter;
import com.univocity.parsers.tsv.TsvWriterSettings;

public final class UpdateLanguageCodes {
	private static final String LOCATION = "./data/scripts.eswikt/UpdateLanguageCodes/";
	private static final String F_LANGS = LOCATION + "eswikt.langs.txt";
	private static final String F_INTRO = LOCATION + "intro.txt";
	private static final String CATEGORY = "Categoría:Plantillas de idiomas";
	private static final String TARGET_PAGE = "Apéndice:Códigos de idioma";

	public static void main(String[] args) throws IOException, LoginException {
		Map<String, String> storedLangs = extractStoredLangs();
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.USER2);
		PageContainer[] pages = wb.getContentOfCategorymembers(CATEGORY, Wiki.TEMPLATE_NAMESPACE);
		Map<String, String> fetchedLangs = extractFetchedLangs(pages);
		
		List<String> addedCodes = updateAddedLangs(storedLangs, fetchedLangs);
		List<String> removedCodes = updateRemovedLangs(storedLangs, fetchedLangs);
		List<String> modifiedCodes = updateModifiedLangs(storedLangs, fetchedLangs);
		
		if (addedCodes.isEmpty() && removedCodes.isEmpty() && modifiedCodes.isEmpty()) {
			return;
		}
		
		System.out.printf(
			"Added: %d, removed: %d, modified: %d%n",
			addedCodes.size(), removedCodes.size(), modifiedCodes.size()
		);
		
		String text = wb.getPageText(TARGET_PAGE);
		text = makePage(text, storedLangs);
		String summary = makeSummary(addedCodes, removedCodes, modifiedCodes);
		
		wb.edit(TARGET_PAGE, text, summary, false, false, -2, null);
		wb.logout();
		
		storeLangs(storedLangs);
	}

	private static Map<String, String> extractStoredLangs() throws FileNotFoundException, IOException {
		File f_langs = new File(F_LANGS);
		List<String[]> list;
		TsvParserSettings settings = new TsvParserSettings();
		TsvParser parser = new TsvParser(settings);
		
		if (f_langs.exists()) {
			try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f_langs), "UTF-8"))) {
				list = parser.parseAll(reader);
			}
			
			System.out.printf("%d language codes extracted%n", list.size());
		} else {
			return new TreeMap<>();
		}
		
		return list.stream()
			.collect(Collectors.toMap(
				arr -> arr[0],
				arr -> arr[1],
				(arr1, arr2) -> arr1,
				TreeMap::new
			));
	}
	
	private static Map<String, String> extractFetchedLangs(PageContainer[] pages) {
		return Stream.of(pages)
			.filter(page -> !ParseUtils.getTemplates("base idioma", page.getText()).isEmpty())
			.collect(Collectors.toMap(
				page -> page.getTitle().substring(page.getTitle().indexOf(":") + 1),
				UpdateLanguageCodes::getLanguageName
			));
	}
	
	private static String getLanguageName(PageContainer page) {
		List<String> templates = ParseUtils.getTemplates("base idioma", page.getText());
		String template = templates.get(0);
		HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
		return params.get("ParamWithoutName1");
	}
	
	private static List<String> updateAddedLangs(Map<String, String> origMap, Map<String, String> newMap) {
		newMap = new HashMap<>(newMap);
		Set<String> keySet = newMap.keySet();
		keySet.removeAll(origMap.keySet());
		origMap.putAll(newMap);
		List<String> codes = new ArrayList<>(keySet);
		Collections.sort(codes);
		return codes;
	}
	
	private static List<String> updateRemovedLangs(Map<String, String> origMap, Map<String, String> newMap) {
		Set<String> keySet = new HashSet<>(origMap.keySet());
		keySet.removeAll(newMap.keySet());
		keySet.stream().forEach(origMap::remove);
		List<String> codes = new ArrayList<>(keySet);
		Collections.sort(codes);
		return codes;
	}
	
	private static List<String> updateModifiedLangs(Map<String, String> origMap, Map<String, String> newMap) {
		Map<String, String> modifiedMap = origMap.keySet().stream()
			.filter(key -> !origMap.get(key).equals(newMap.get(key)))
			.collect(Collectors.toMap(key -> key, newMap::get));
		
		if (modifiedMap.isEmpty()) {
			return Collections.emptyList();
		}
		
		origMap.putAll(modifiedMap);
		List<String> codes = new ArrayList<>(modifiedMap.keySet());
		Collections.sort(codes);
		return codes;
	}
	
	private static String buildTable(Map<String, String> map) {
		StringBuilder sb = new StringBuilder();
		
		sb.append("{| class=\"wikitable sortable\"\n");
		sb.append("! Código !! Idioma\n");
		sb.append("|-\n");
		
		List<String> rows = map.keySet().stream()
			.map(key -> String.format("| %s || %s\n", key, map.get(key)))
			.collect(Collectors.toList());
		
		sb.append(String.join("|-\n", rows));
		sb.append("|}");
		
		return sb.toString();
	}
	
	private static String makePage(String pageText, Map<String, String> storedLangs) throws FileNotFoundException {
		String table = buildTable(storedLangs);
		int index = pageText.indexOf("<!--");
		
		if (index == -1) {
			pageText = String.join("\n", IOUtils.loadFromFile(F_INTRO, "", "UTF8"));
			return pageText + "\n" + table;
		}
		
		index = pageText.indexOf("\n", index);
		index = (index != -1) ? index : pageText.length();
		
		String intro = pageText.substring(0, index);
		Matcher m = Pattern.compile("<span class=\"update-timestamp\">(.+?)</span>").matcher(intro);
		
		if (!m.find()) {
			pageText = String.join("\n", IOUtils.loadFromFile(F_INTRO, "", "UTF8"));
			return pageText + "\n" + table;
		}
		
		String timestamp = String.format("~~~~~ (%d idiomas)", storedLangs.size());
		intro = intro.substring(0, m.start(1)) + timestamp + intro.substring(m.end(1));
		
		return intro + "\n" + table;
	}
	
	private static String makeSummary(List<String> addedCodes, List<String> removedCodes, List<String> modifiedCodes) {
		StringBuilder sb = new StringBuilder("actualización");
		
		if (addedCodes.isEmpty() && removedCodes.isEmpty() && modifiedCodes.isEmpty()) {
			return sb.toString();
		} else {
			sb.append(" (");
		}
		
		List<String> details = new ArrayList<>();
		
		if (!addedCodes.isEmpty()) {
			details.add(String.format("añadidos: %s", String.join(", ", addedCodes)));
		}
		
		if (!removedCodes.isEmpty()) {
			details.add(String.format("borrados: %s", String.join(", ", removedCodes)));
		}
		
		if (!modifiedCodes.isEmpty()) {
			details.add(String.format("modificados: %s", String.join(", ", modifiedCodes)));
		}
		
		sb.append(String.join("; ", details));
		sb.append(")");
		
		return sb.toString();
	}
	
	private static void storeLangs(Map<String, String> map) throws FileNotFoundException, UnsupportedEncodingException {
		TsvWriterSettings settings = new TsvWriterSettings();
		Writer outputWriter = new OutputStreamWriter(new FileOutputStream(new File(F_LANGS)), "UTF8");
		TsvWriter writer = new TsvWriter(outputWriter, settings);
		String[][] rows = map.keySet().stream().map(key -> new String[]{key, map.get(key)}).toArray(String[][]::new);
		writer.writeRowsAndClose(rows);
	}
}
