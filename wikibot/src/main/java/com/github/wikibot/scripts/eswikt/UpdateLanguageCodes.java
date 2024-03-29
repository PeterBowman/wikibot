package com.github.wikibot.scripts.eswikt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;
import com.univocity.parsers.tsv.TsvWriter;
import com.univocity.parsers.tsv.TsvWriterSettings;

public final class UpdateLanguageCodes {
    private static final Path LOCATION = Paths.get("./data/scripts.eswikt/UpdateLanguageCodes/");
    private static final Path LANGS = LOCATION.resolve("eswikt.langs.txt");
    private static final Path INTRO = LOCATION.resolve("intro.txt");
    private static final String CATEGORY = "Categoría:Plantillas de idiomas";
    private static final String TARGET_PAGE = "Apéndice:Códigos de idioma";

    public static void main(String[] args) throws IOException, LoginException {
        Map<String, String> storedLangs = extractStoredLangs();

        Wikibot wb = Wikibot.newSession("es.wiktionary.org");
        Login.login(wb);

        List<PageContainer> pages = wb.getContentOfCategorymembers(CATEGORY, Wiki.TEMPLATE_NAMESPACE);
        Map<String, String> fetchedLangs = extractFetchedLangs(pages);

        List<String> addedCodes = updateAddedLangs(storedLangs, fetchedLangs);
        List<String> removedCodes = updateRemovedLangs(storedLangs, fetchedLangs);
        List<String> modifiedCodes = updateModifiedLangs(storedLangs, fetchedLangs);

        if (addedCodes.isEmpty() && removedCodes.isEmpty() && modifiedCodes.isEmpty()) {
            return;
        }

        System.out.printf(
            "Added: %d, removed: %d, modified: %d.%n",
            addedCodes.size(), removedCodes.size(), modifiedCodes.size()
        );

        String fetchedText = wb.getPageText(List.of(TARGET_PAGE)).get(0);
        String generatedTable = generateTable(storedLangs);

        if (fetchedText.contains(generatedTable)) {
            System.out.println("Target page is already up to date. Storing data and aborting...");
            storeLangs(storedLangs);
            return;
        }

        String newText = makePage(fetchedText, generatedTable, storedLangs.size());
        String summary = makeSummary(addedCodes, removedCodes, modifiedCodes);

        wb.edit(TARGET_PAGE, newText, summary, false, false, -2, null);
        wb.logout();

        storeLangs(storedLangs);
    }

    private static Map<String, String> extractStoredLangs() throws IOException {
        List<String[]> list;
        TsvParserSettings settings = new TsvParserSettings();
        TsvParser parser = new TsvParser(settings);

        if (Files.exists(LANGS)) {
            try (Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(LANGS), StandardCharsets.UTF_8))) {
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

    private static Map<String, String> extractFetchedLangs(List<PageContainer> pages) {
        return pages.stream()
            .filter(page -> !ParseUtils.getTemplates("base idioma", page.text()).isEmpty())
            .collect(Collectors.toMap(
                page -> page.title().substring(page.title().indexOf(":") + 1),
                UpdateLanguageCodes::getLanguageName
            ));
    }

    private static String getLanguageName(PageContainer page) {
        List<String> templates = ParseUtils.getTemplates("base idioma", page.text());
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

    private static String generateTable(Map<String, String> map) {
        StringBuilder sb = new StringBuilder(25000);

        sb.append("{| class=\"wikitable sortable\"\n");
        sb.append("! Código !! Idioma\n");
        sb.append("|-\n");

        List<String> rows = map.keySet().stream()
            .map(key -> String.format("| %s || %s\n", key, map.get(key)))
            .toList();

        sb.append(String.join("|-\n", rows));
        sb.append("|}");

        return sb.toString();
    }

    private static String makePage(String text, String table, int size) throws IOException {
        int index = text.indexOf("<!--");

        if (index == -1) {
            return Files.readString(INTRO) + "\n" + table;
        }

        index = text.indexOf("\n", index);
        index = (index != -1) ? index : text.length();

        String intro = text.substring(0, index);
        Matcher m = Pattern.compile("<span class=\"update-timestamp\">(.+?)</span>").matcher(intro);

        if (!m.find()) {
            return Files.readString(INTRO) + "\n" + table;
        }

        String timestamp = String.format("~~~~~ (%d idiomas)", size);
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

        return sb.append(String.join("; ", details)).append(")").toString();
    }

    private static void storeLangs(Map<String, String> map) throws IOException {
        TsvWriterSettings settings = new TsvWriterSettings();
        Writer outputWriter = new OutputStreamWriter(Files.newOutputStream(LANGS), StandardCharsets.UTF_8);
        TsvWriter writer = new TsvWriter(outputWriter, settings);
        String[][] rows = map.keySet().stream().map(key -> new String[]{key, map.get(key)}).toArray(String[][]::new);
        writer.writeRowsAndClose(rows);
    }
}
