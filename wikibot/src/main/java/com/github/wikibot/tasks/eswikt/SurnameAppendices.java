package com.github.wikibot.tasks.eswikt;

import java.io.IOException;
import java.text.Collator;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class SurnameAppendices {
    private static final String TARGET_PARENT_PAGE = "Apéndice:Antropónimos/Apellidos/";
    private static final String SURNAME_TEMPLATE = "Plantilla:apellido";
    private static final char SPECIAL_LETTER = '#';
    private static final Locale locale = new Locale("es");
    private static final Collator collator = Collator.getInstance(locale);
    private static final Map<Character, Character> stressedVowels = Map.of('Á', 'A', 'É', 'E', 'Í', 'I', 'Ó', 'O', 'Ú', 'U');
    private static final Wikibot wb = Wikibot.newSession("es.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var subPages = getSubPages();
        var surnames = getSurnames();

        var letters = filterTargetLetters(subPages);
        var groupedSurnames = groupSurnames(surnames, letters);

        for (var entry : groupedSurnames.entrySet()) {
            var firstLetter = entry.getKey();
            var surnameList = entry.getValue();

            if (firstLetter == SPECIAL_LETTER) {
                continue;
            }

            var targetPage = TARGET_PARENT_PAGE + firstLetter;
            var header = firstLetter.toString();

            var links = getLinksOnPage(targetPage, header);

            if (!links.containsAll(surnameList)) {
                var mergedList = mergeLists(links, surnameList);
                var pageText = prepareOutput(header.toUpperCase(locale), header.toLowerCase(locale), mergedList);

                wb.edit(targetPage, pageText, "actualización");
            }
        }
    }

    private static List<String> getSubPages() throws IOException {
        var ns = wb.namespace(TARGET_PARENT_PAGE);
        var prefix = wb.removeNamespace(TARGET_PARENT_PAGE, ns);
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
            var firstLetter = surname.charAt(0);
            firstLetter = stressedVowels.getOrDefault(firstLetter, firstLetter);
            return letters.contains(firstLetter) ? firstLetter : SPECIAL_LETTER;
        };
    }

    private static List<String> getLinksOnPage(String page, String header) throws IOException {
        return wb.getLinksOnPage(page).stream()
            .filter(link -> wb.namespace(link) == Wiki.MAIN_NAMESPACE)
            .filter(link -> !link.equals(header) && !link.equals("apellido"))
            .toList();
    }

    private static List<String> mergeLists(List<String> listA, List<String> listB) {
        return Stream.of(listA, listB)
            .flatMap(Collection::stream)
            .distinct()
            .sorted(collator)
            .toList();
    }

    private static String prepareOutput(String headerUpper, String headerLower, List<String> surnames) {
        var sb = new StringBuilder(surnames.size() * 20);

        sb.append("{{Abecedario|").append(TARGET_PARENT_PAGE.replaceFirst("/$", "")).append("}}");
        sb.append("\n\n");
        sb.append(String.format("<strong>Lista de {{l|es|apellido|apellidos}} que comienzan por la letra {{l|es|%s}}</strong>:", headerUpper));
        sb.append("\n\n");

        surnames.stream()
            .map(surname -> String.format("* {{l|es|%s}}\n", surname))
            .forEach(sb::append);

        sb.append("\n\n");
        sb.append(String.format("[[Categoría:ES:Apellidos| %s]]", headerLower)).append("\n");
        sb.append(String.format("[[Categoría:Wikcionario:Apéndices|Apellidos %s]]", headerLower));

        return sb.toString();
    }
}
