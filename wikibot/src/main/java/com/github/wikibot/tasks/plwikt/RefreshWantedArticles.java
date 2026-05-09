package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import com.github.plural4j.Plural;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;

public final class RefreshWantedArticles {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/RefreshWantedArticles/");
    private static final String TARGET_PAGE = "Szablon:Potrzebne";
    private static final String REFILL_PAGE = "Wikisłownikarz:Tsca/Najpotrzebniejsze polskie hasła na podstawie istniejących łączy";

    private static final int MAX_LENGHT = 115;
    private static final int REFILL_SIZE = 10;
    private static final int REFILL_THRESHOLD = 30;

    private static final Pattern P_LINK;
    private static final Pattern P_OCCURRENCES_TARGET;
    private static final Pattern P_OCCURRENCES_REFILL;

    private static final Plural PLURAL_PL;
    private static final LocalizedNumberFormatter NUMBER_FORMAT_PL;

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    static {
        P_LINK = Pattern.compile("\\[\\[([^\\]\n]+?)\\]\\]");
        P_OCCURRENCES_TARGET = Pattern.compile("((?: *• *)?" + P_LINK.pattern() + ")+");
        P_OCCURRENCES_REFILL = Pattern.compile("^\\* '{3}" + P_LINK.pattern() + "'{3}$", Pattern.MULTILINE);
        PLURAL_PL = new Plural(PluralRules.POLISH, "utworzone,utworzone,utworzonych");
        NUMBER_FORMAT_PL = NumberFormatter.withLocale(Locale.forLanguageTag("pl-PL")).grouping(GroupingStrategy.MIN2);
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var content = wb.getPageText(List.of(TARGET_PAGE)).get(0);

        var doc = Jsoup.parse(content, "", Parser.xmlParser());
        var targetElement = selectTargetElement(doc);

        var visibleTitles = extractTitles(targetElement.previousSibling());
        var hiddenTitles = extractTitles(targetElement);

        var storeSet = manageStoredTitles(visibleTitles, hiddenTitles);

        var doneVisible = filterDoneArticles(visibleTitles);
        var doneHidden = filterDoneArticles(hiddenTitles);

        if (
            doneVisible.isEmpty() && doneHidden.size() < REFILL_SIZE &&
            hiddenTitles.size() - doneHidden.size() > REFILL_THRESHOLD
        ) {
            System.out.println("No edit action triggered, aborting.");
            return;
        }

        processElement(targetElement, visibleTitles, doneVisible, hiddenTitles, doneHidden, storeSet);

        var text = doc.html();
        var totalDone = doneVisible.size() + doneHidden.size();
        var counter = String.format("%s %s", NUMBER_FORMAT_PL.format(totalDone), PLURAL_PL.pl(totalDone, "utworzone"));
        var summary = String.format("odświeżenie listy (%s)", counter);

        wb.edit(TARGET_PAGE, text, summary);
        wb.purge(true, TARGET_PAGE);
    }

    private static Element selectTargetElement(Element body) {
        var els = body.getElementsByTag("noinclude");

        if (els.isEmpty()) {
            throw new RuntimeException("No <noinclude> tags found.");
        }

        var parent = els.get(0).parent();

        if (els.stream().anyMatch(el -> el.parent() != parent)) {
            throw new RuntimeException("Multiple <noinclude> tags with no common parent.");
        }

        var targetElement = els.stream()
            .filter(el -> P_OCCURRENCES_TARGET.matcher(el.html().trim()).matches())
            .findFirst()
            .orElseThrow(() -> new RuntimeException("No matching <noinclude> target element found."));

        if (!targetElement.children().isEmpty()) {
            throw new RuntimeException("Target <noinclude> node has inner elements.");
        }

        var previousSibling = targetElement.previousSibling();

        if (previousSibling == null) {
            throw new RuntimeException("Target <noinclude> node has no previous sibling.");
        }

        if (!(previousSibling instanceof TextNode)) {
            throw new RuntimeException("Target <noinclude> node has no previous TextNode sibling.");
        }

        if (!P_OCCURRENCES_TARGET.matcher(previousSibling.toString().trim()).matches()) {
            throw new RuntimeException("TextNode sibling does not match the expected pattern.");
        }

        return targetElement;
    }

    private static List<String> extractTitles(Node node) {
        final String text;

        if (node instanceof Element n) {
            text = n.html().trim();
        } else {
            text = node.toString().trim();
        }

        var list = new ArrayList<String>();
        var m = P_LINK.matcher(text);

        while (m.find()) {
            list.add(m.group(1).trim());
        }

        if (list.isEmpty()) {
            throw new RuntimeException("No titles have been extracted from " + node.getClass() + ".");
        }

        return list;
    }

    private static Set<String> manageStoredTitles(List<String> visible, List<String> hidden) throws IOException {
        var path = LOCATION.resolve("store.txt");
        var storeSet = Files.exists(path) ? new HashSet<>(Files.readAllLines(path)) : new HashSet<String>();

        storeSet.addAll(visible);
        storeSet.addAll(hidden);

        Files.write(path, storeSet);
        return storeSet;
    }

    private static List<String> filterDoneArticles(List<String> titles) throws IOException {
        var pages = wb.getContentOfPages(titles);

        // Missing pages have been already filtered out
        var nonMissingTitles = pages.stream()
            .map(PageContainer::title)
            .toList();

        var redirects = wb.resolveRedirects(nonMissingTitles);

        for (int i = 0; i < pages.size(); i++) {
            var redirect = redirects.get(i);

            if (!redirect.equals(nonMissingTitles.get(i))) {
                var old = pages.get(i);

                try {
                    var redirectText = wb.getPageText(List.of(redirect)).get(0);
                    pages.set(i, new PageContainer(old.title(), redirectText, old.revid(), old.timestamp()));
                } catch (FileNotFoundException | NullPointerException e) {
                    System.out.printf("Title \"%s\" redirects to missing page \"%s\"%n", old.title(), redirect);
                    pages.set(i, null);
                    continue;
                }
            }
        }

        return pages.stream()
            .filter(Objects::nonNull)
            .map(Page::wrap)
            .filter(page -> page.getPolishSection().isPresent())
            .map(Page::getTitle)
            .toList();
    }

    private static void processElement(Element el, List<String> visibleTitles, List<String> doneVisible,
            List<String> hiddenTitles, List<String> doneHidden, Set<String> storeSet) {
        var visibleList = new ArrayList<>(visibleTitles);
        visibleList.removeAll(doneVisible);

        var hiddenList = new ArrayList<>(hiddenTitles);
        hiddenList.removeAll(doneHidden);

        if (!doneVisible.isEmpty()) {
            while (!checkLength(visibleList)) {
                if (hiddenList.size() < REFILL_THRESHOLD) {
                    try {
                        fetchMoreArticles(hiddenList, storeSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }
                }

                if (hiddenList.isEmpty()) {
                    break;
                }

                visibleList.add(hiddenList.remove(0));
            }
        }

        if (hiddenList.size() < REFILL_THRESHOLD) {
            try {
                fetchMoreArticles(hiddenList, storeSet);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final var fmt = "[[%s]]";
        var visibleText = buildString(visibleList, fmt);
        var hiddenText = " • " + buildString(hiddenList, fmt);

        ((TextNode) el.previousSibling()).text(visibleText);
        el.text(hiddenText);
    }

    private static boolean checkLength(List<String> list) {
        return buildString(list, "%s").length() > MAX_LENGHT;
    }

    private static String buildString(List<String> list, String fmt) {
        return list.stream()
            .map(s -> String.format(fmt, s))
            .collect(Collectors.joining(" • "));
    }

    private static void fetchMoreArticles(List<String> list, Set<String> storeSet) throws IOException {
        var text = wb.getPageText(List.of(REFILL_PAGE)).get(0);
        var m = P_OCCURRENCES_REFILL.matcher(text);
        var titles = new ArrayList<String>(500);

        while (m.find()) {
            var title = m.group(1).trim();

            if (!storeSet.contains(title)) {
                titles.add(title);
            }
        }

        var doneSet = new HashSet<>(filterDoneArticles(titles));

        titles.stream()
            .filter(title -> !doneSet.contains(title))
            .limit(Math.max(REFILL_SIZE, REFILL_THRESHOLD - list.size()))
            .forEach(list::add);

        storeSet.addAll(list);
    }
}
