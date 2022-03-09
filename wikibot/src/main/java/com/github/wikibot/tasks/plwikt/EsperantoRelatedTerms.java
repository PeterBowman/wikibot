package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class EsperantoRelatedTerms {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/EsperantoRelatedTerms/");
    private static final String TARGET_PAGE = "Wikipedysta:PBbot/potencjalne błędy w pokrewnych esperanto";
    private static final String TARGET_PAGE_EXCLUDED = "Wikipedysta:PBbot/potencjalne błędy w pokrewnych esperanto/wykluczenia";

    private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]([^\\[]*)"); // from Linker::formatLinksInComment in Linker.php
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        Map<String, List<String>> morphemToTitle = new HashMap<>(10000);
        Map<String, List<String>> titleToMorphem = new HashMap<>(15000);
        Map<String, String> contentMap = new HashMap<>(15000);

        populateMaps(morphemToTitle, titleToMorphem, contentMap);

        Map<String, List<MorphemTitlePair>> items = findItems(morphemToTitle, titleToMorphem, contentMap);

        removeIgnoredItems(items);

        StringBuilder sb = new StringBuilder(30000);

        for (Map.Entry<String, List<MorphemTitlePair>> entry : items.entrySet()) {
            sb.append(String.format("# [[%s]]: ", entry.getKey()));
            sb.append(buildItemList(entry.getValue())).append("\n");
        }

        if (!checkAndUpdateStoredData(items)) {
            System.out.println("No changes detected, aborting.");
            return;
        }

        Files.write(LOCATION.resolve("output.txt"), List.of(sb.toString()));
        editPage(sb.toString());
    }

    private static void populateMaps(Map<String, List<String>> morphemToTitle,
            Map<String, List<String>> titleToMorphem,
            Map<String, String> contentMap)
        throws IOException {
        List<String> cat1 = wb.getCategoryMembers("Esperanto - końcówki gramatyczne", Wiki.MAIN_NAMESPACE);
        List<String> cat2 = wb.getCategoryMembers("Esperanto - morfemy przedrostkowe", Wiki.MAIN_NAMESPACE);
        List<String> cat3 = wb.getCategoryMembers("Esperanto - morfemy przyrostkowe", Wiki.MAIN_NAMESPACE);

        Set<String> excluded = new HashSet<>(cat1.size() + cat2.size() + cat3.size());
        excluded.addAll(cat1);
        excluded.addAll(cat2);
        excluded.addAll(cat3);

        List<PageContainer> morfeoTransclusions = wb.getContentOfTransclusions("Szablon:morfeo", Wiki.MAIN_NAMESPACE);

        for (PageContainer page : morfeoTransclusions) {
            for (String template : ParseUtils.getTemplates("morfeo", page.getText())) {
                Map<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
                params.remove("templateName");
                params.values().removeAll(excluded);

                if (!params.values().isEmpty()) {
                    String morphem = String.join("", params.values());
                    List<String> titles = morphemToTitle.getOrDefault(morphem, new ArrayList<>());

                    if (!titles.contains(page.getTitle())) {
                        titles.add(page.getTitle());
                        morphemToTitle.putIfAbsent(morphem, titles);
                    }

                    List<String> morphems = titleToMorphem.getOrDefault(page.getTitle(), new ArrayList<>());

                    if (!morphems.contains(morphem)) {
                        morphems.add(morphem);
                        titleToMorphem.putIfAbsent(page.getTitle(), morphems);
                        contentMap.put(page.getTitle(), page.getText());
                    }
                }
            }
        }

        System.out.printf("morphemToTitle: %d%n", morphemToTitle.size());
        System.out.printf("titleToMorphem: %d%n", titleToMorphem.size());
    }

    private static Map<String, List<MorphemTitlePair>> findItems(Map<String, List<String>> morphemToTitle,
            Map<String, List<String>> titleToMorphem, Map<String, String> contentMap) throws IOException {
        Collator collator = Collator.getInstance(new Locale("eo"));
        Map<String, List<MorphemTitlePair>> items = new TreeMap<>(collator);
        List<String> allEsperantoTitles = wb.getCategoryMembers("esperanto (indeks)", Wiki.MAIN_NAMESPACE);
        Set<String> esperantoSet = new HashSet<>(allEsperantoTitles);

        for (Map.Entry<String, String> entry : contentMap.entrySet()) {
            String title = entry.getKey();

            Page p = Page.store(title, entry.getValue());
            Section s = p.getSection("esperanto").get();
            Field f = s.getField(FieldTypes.RELATED_TERMS).get();

            List<String> morphems = titleToMorphem.get(title);

            List<String> allTitles = morphemToTitle.entrySet().stream()
                .filter(m2t -> morphems.contains(m2t.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .distinct()
                .toList();

            Set<String> relatedTerms = extractLinks(f.getContent());
            List<MorphemTitlePair> list = new ArrayList<>();

            for (String morphem : morphems) {
                List<String> wrong = new ArrayList<>(relatedTerms);
                wrong.removeAll(allTitles);
                wrong.removeIf(t -> t.toLowerCase().contains(morphem) && !esperantoSet.contains(t));
                wrong.removeIf(t -> t.contains(" "));

                wrong.stream()
                    .map(t -> new MorphemTitlePair(t, morphem))
                    .sorted()
                    .forEach(list::add);
            }

            if (!list.isEmpty()) {
                items.put(title, list);
            }
        }

        System.out.printf("%d items extracted%n", items.size());
        return items;
    }

    private static Set<String> extractLinks(String text) {
        if (text.isEmpty()) {
            return Collections.emptySet();
        }

        Matcher m = P_LINK.matcher(text);
        Set<String> set = new HashSet<>();

        while (m.find()) {
            String target = m.group(1);

            if (target.contains("#")) {
                target = target.substring(0, target.indexOf("#"));
            }

            set.add(target);
        }

        return set;
    }

    private static void removeIgnoredItems(Map<String, List<MorphemTitlePair>> items) throws IOException {
        Pattern patt = Pattern.compile("# *\\[\\[([^\\]]+?)\\]\\]: *\\[\\[([^\\]]+?)\\]\\] *\\(([^\\)]+?)\\)$", Pattern.MULTILINE);
        String pageText = wb.getPageText(List.of(TARGET_PAGE_EXCLUDED)).get(0);
        Matcher m = patt.matcher(pageText);

        while (m.find()) {
            String target = m.group(1).trim();
            String title = m.group(2).trim();
            String morphem = m.group(3).trim();

            List<MorphemTitlePair> list = items.get(target);

            if (list != null) {
                MorphemTitlePair mtp = new MorphemTitlePair(title, morphem);
                list.remove(mtp);

                if (list.isEmpty()) {
                    items.remove(target);
                }
            }
        }

        System.out.printf("%d items after removing ignored from subpage%n", items.size());
    }

    private static boolean checkAndUpdateStoredData(Map<String, List<MorphemTitlePair>> items) throws IOException {
        int newHashCode = items.hashCode();
        int storedHashCode;

        Path hash = LOCATION.resolve("hash.ser");

        try {
            storedHashCode = Integer.parseInt(Files.readString(hash));
        } catch (IOException | NumberFormatException e) {
            storedHashCode = 0;
        }

        if (storedHashCode != newHashCode) {
            Files.writeString(hash, Integer.toString(newHashCode));
            return true;
        } else {
            return false;
        }
    }

    private static String buildItemList(List<MorphemTitlePair> list) {
        Collator collator = Collator.getInstance(new Locale("eo"));

        return list.stream()
            .collect(Collectors.groupingBy(
                i -> i.morphem,
                () -> new TreeMap<>(collator),
                Collectors.mapping(
                    i -> String.format("[[%s]]", i.title),
                    Collectors.joining(", ")
                )
            ))
            .entrySet().stream()
            .collect(Collectors.mapping(
                entry -> String.format("%s (%s)", entry.getValue(), entry.getKey()),
                Collectors.joining(" • ")
            ));
    }

    private static void editPage(String text) throws IOException, LoginException {
        String pageText = wb.getPageText(List.of(TARGET_PAGE)).get(0);

        if (pageText.contains("<!--") && pageText.contains("-->")) {
            pageText = pageText.substring(0, pageText.lastIndexOf("-->") + 3) + "\n";
        } else {
            pageText = "{{język linków|esperanto}}\n";
            pageText += "<!-- nie edytuj poniżej tej linii -->\n";
        }

        pageText += text;

        wb.setMarkBot(false);
        wb.edit(TARGET_PAGE, pageText, "aktualizacja");
    }

    private static class MorphemTitlePair implements Comparable<MorphemTitlePair> {
        String title;
        String morphem;

        MorphemTitlePair(String title, String morphem) {
            this.title = title;
            this.morphem = morphem;
        }

        @Override
        public int compareTo(MorphemTitlePair o) {
            Collator coll = Collator.getInstance(new Locale("eo"));
            return coll.compare(title, o.title);
        }

        @Override
        public int hashCode() {
            return title.hashCode() + morphem.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof MorphemTitlePair mtp) {
                return title.equals(mtp.title) && morphem.equals(mtp.morphem);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("[%s: %s]", title, morphem);
        }
    }
}
