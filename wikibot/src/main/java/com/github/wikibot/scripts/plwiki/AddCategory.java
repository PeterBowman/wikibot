package com.github.wikibot.scripts.plwiki;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class AddCategory {
    private static final Path LOCATION = Paths.get("./data/scripts.plwiki/AddCategory/");
    private static final Path TITLES = LOCATION.resolve("titles.txt");

    private static final List<String> DEFAULSORT_ALIASES = List.of(
        "SORTUJ", "DEFAULTSORT", "DEFAULTSORTKEY", "DEFAULTCATEGORYSORT"
    );

    private static final Pattern PATT_CATEGORIES;

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    static {
        var aliases = String.join("|", DEFAULSORT_ALIASES);
        PATT_CATEGORIES = Pattern.compile(".+\n\\{{2}(?:" + aliases + "):[^\\}\n]+?\\}{2}(?: *\n+\\[{2}Kategoria:[^\\]\n]+?\\]{2})+$", Pattern.DOTALL);
    }

    public static void main(String[] args) throws Exception {
        String category;

        if (args.length == 0) {
            System.out.print("Target category: ");
            category = String.join(" ", Misc.readArgs());
        } else {
            category = args[1];
        }

        Login.login(wb);
        category = wb.removeNamespace(category, Wiki.CATEGORY_NAMESPACE);

        var titles = Files.readAllLines(TITLES);
        var catMembers = wb.getCategoryMembers(category, Wiki.MAIN_NAMESPACE);

        if (titles.removeAll(catMembers)) {
            System.out.printf("New list size: %d.%n", titles.size());

            if (titles.isEmpty()) {
                return;
            }
        }

        final var summary = String.format("dodanie [[Kategoria:%s]]", category);
        var edited = new ArrayList<String>();
        var errors = new ArrayList<String>();

        for (var page : wb.getContentOfPages(titles)) {
            String text = page.getText();

            if (!PATT_CATEGORIES.matcher(text).matches()) {
                errors.add(page.getTitle());
                continue;
            }

            text += String.format("\n[[Kategoria:%s]]", category);

            try {
                wb.edit(page.getTitle(), text, summary, page.getTimestamp());
                edited.add(page.getTitle());
            } catch (Throwable t) {
                t.printStackTrace();
                errors.add(page.getTitle());
            }
        }

        System.out.printf("%d edited pages: %s%n", edited.size(), edited);
        System.out.printf("%d errors: %s%n", errors.size(), errors);
    }
}
