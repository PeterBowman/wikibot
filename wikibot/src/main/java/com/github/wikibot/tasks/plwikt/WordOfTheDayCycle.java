package com.github.wikibot.tasks.plwikt;

import java.util.List;
import java.util.regex.Pattern;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public class WordOfTheDayCycle {
    private static String TARGET = "Moduł:słowo dnia/dane/ukraiński";
    private static Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        var page = wb.getContentOfPages(List.of(TARGET)).get(0);

        var current = Pattern.compile("aktualne *+= *+\"(.+?)\"").matcher(page.getText()).results()
            .map(mr -> mr.group(1))
            .findAny().get();

        var newText = Pattern.compile("poprzednie *+= *+\"(.+?)\"").matcher(page.getText())
            .replaceFirst(mr -> mr.group().replace(mr.group(1), current));

        Login.login(wb);
        wb.edit(page.getTitle(), newText, "rotacja słowa dnia", page.getTimestamp());
    }
}
