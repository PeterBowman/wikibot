package com.github.wikibot.tasks.eswikt;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class SurnameAppendices {
    private static final String TARGET_PARENT_PAGE = "Apéndice:Personas/Apellidos/";
    private static final String OTHER_SURNAMES_PAGE = "Apéndice:Personas/Apellidos/otros";
    private static final String SURNAME_TEMPLATE = "Plantilla:apellido";

    private static final char SPECIAL_LETTER = '#';

    private static final int COLUMNS = 5;

    private static final Collator collator = Collator.getInstance(new Locale("es"));

    private static final Map<Character, Character> stressedVowels = Map.of('Á', 'A', 'É', 'E', 'Í', 'I', 'Ó', 'O', 'Ú', 'U');

    private static final Wikibot wb = Wikibot.newSession("es.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        List<String> subPages = getSubPages();
        List<String> surnames = getSurnames();

        Set<Character> letters = filterTargetLetters(subPages);
        Map<Character, List<String>> groupedSurnames = groupSurnames(surnames, letters);

        wb.setThrottle(5000);
        wb.setMarkBot(true);
        wb.setMarkMinor(false);

        for (Map.Entry<Character, List<String>> entry : groupedSurnames.entrySet()) {
            Character firstLetter = entry.getKey();
            List<String> surnameList = entry.getValue();

            final String targetPage;
            final String header;

            if (firstLetter == SPECIAL_LETTER) {
                targetPage = OTHER_SURNAMES_PAGE;
                header = "otros";
            } else {
                targetPage = TARGET_PARENT_PAGE + firstLetter;
                header = firstLetter.toString();
            }

            List<String> links = getLinksOnPage(targetPage);

            if (!links.containsAll(surnameList)) {
                List<String> mergedList = mergeLists(links, surnameList);
                String pageText = prepareOutput(header, mergedList);

                wb.edit(targetPage, pageText, "actualización");
            }
        }
    }

    private static List<String> getSubPages() throws IOException {
        int ns = wb.namespace(TARGET_PARENT_PAGE);
        String prefix = wb.removeNamespace(TARGET_PARENT_PAGE, ns);
        return wb.listPages(prefix, null, ns);
    }

    private static List<String> getSurnames() throws IOException {
        return wb.whatTranscludesHere(List.of(SURNAME_TEMPLATE), Wiki.MAIN_NAMESPACE).get(0);
    }

    private static Set<Character> filterTargetLetters(List<String> subPages) {
        return subPages.stream()
            .map(subPage -> subPage.substring(subPage.lastIndexOf("/") + 1))
            .filter(suffix -> suffix.length() == 1)
            .filter(StringUtils::isAllUpperCase)
            .map(s -> s.charAt(0))
            .collect(Collectors.toSet());
    }

    private static Map<Character, List<String>> groupSurnames(List<String> surnames, Set<Character> letters) {
        return surnames.stream()
            .sorted(collator)
            .collect(Collectors.groupingBy(getClassifier(letters)));
    }

    private static Function<? super String, ? extends Character> getClassifier(Set<Character> letters) {
        return surname -> {
            Character firstLetter = surname.charAt(0);
            firstLetter = Optional.ofNullable(stressedVowels.get(firstLetter)).orElse(firstLetter);
            return letters.contains(firstLetter) ? firstLetter : SPECIAL_LETTER;
        };
    }

    private static List<String> getLinksOnPage(String page) throws IOException {
        return wb.getLinksOnPage(page).stream()
            .filter(link -> wb.namespace(link) == Wiki.MAIN_NAMESPACE)
            .toList();
    }

    private static List<String> mergeLists(List<String> listA, List<String> listB) {
        return Stream.of(listA, listB)
            .flatMap(Collection::stream)
            .distinct()
            .sorted(collator)
            .toList();
    }

    private static String prepareOutput(String header, List<String> surnames) {
        StringBuilder sb = new StringBuilder(surnames.size() * 20);

        sb.append("{{Abecedario|").append(TARGET_PARENT_PAGE.replaceFirst("/$", "")).append("}}");
        sb.append("\n\n==").append(header).append("==\n\n");

        sb.append("{| border=0  width=100%").append("\n");
        sb.append("|-").append("\n");

        List<List<String>> splitList = splitList(surnames, COLUMNS);
        final int width = (int) Math.floor(100 / COLUMNS);

        for (List<String> chunk : splitList) {
            sb.append("|valign=top width=").append(width).append("%|").append("\n");
            sb.append("{|").append("\n");

            chunk.stream()
                .map(link -> String.format("*[[%s]]", link))
                .forEach(item -> sb.append(item).append("\n"));

            sb.append("|}").append("\n");
        }

        sb.append("|}");

        return sb.toString();
    }

    private static List<List<String>> splitList(List<String> original, int chunks) {
        int chunkSize = (int) Math.ceil((double) original.size() / (double) chunks);
        List<List<String>> list = new ArrayList<>(chunks);
        int cursor = 0;

        for (int i = 0; i < chunks; i++) {
            List<String> subList = original.subList(cursor, Math.min(original.size(), cursor + chunkSize));
            list.add(subList);
            cursor += chunkSize;
        }

        return list;
    }
}
