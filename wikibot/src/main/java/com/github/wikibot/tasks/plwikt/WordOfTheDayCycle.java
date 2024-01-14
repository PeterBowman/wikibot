package com.github.wikibot.tasks.plwikt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public class WordOfTheDayCycle {
    private static Path LOCATION = Path.of("./data/tasks.plwikt/WordOfTheDayCycle/");
    private static String TARGET_BASE = "Moduł:słowo dnia/dane/";
    private static Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var pattCurrent = Pattern.compile("aktualne *+= *+\"(.+?)\"");
        var pattPrevious = Pattern.compile("poprzednie *+= *+\"(.+?)\"");

        var targets = Files.readAllLines(LOCATION.resolve("targets.txt")).stream()
            .map(s -> TARGET_BASE + s)
            .toList();

        for (var page : wb.getContentOfPages(targets)) {
            var current = pattCurrent.matcher(page.text()).results()
                .map(mr -> mr.group(1))
                .findAny().get();

            var newText = pattPrevious.matcher(page.text())
                .replaceFirst(mr -> mr.group().replace(mr.group(1), current));

            wb.edit(page.title(), newText, "rotacja słowa dnia", page.timestamp());
        }
    }
}
