package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class Replace {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/scripts/replace/");
    private static final Path TITLES = LOCATION.resolve("titles.txt");
    private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
    private static final Path TARGET = LOCATION.resolve("target.txt");
    private static final Path REPLACEMENT = LOCATION.resolve("replacement.txt");
    private static final Path INFO = LOCATION.resolve("info.xml");
    private static final String SUMMARY_FORMAT = "'%s' → '%s'";

    private static void selector(char op) throws Exception {
        switch (op) {
            case 'd':
                Login.login(wb);
                getDiffs();
                break;
            case 'e':
                Login.login(wb);
                edit();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    private static void getDiffs() throws IOException {
        String target = "prettytable";
        String replacement = "wikitable";
        List<String> titles = Files.readAllLines(TITLES);

        System.out.printf("Título: %s%n", target);
        System.out.printf("Sustitución por: %s%n", replacement);
        System.out.printf("Tamaño de la lista: %d%n", titles.size());

        if (titles.isEmpty()) {
            return;
        }

        List<PageContainer> pages = wb.getContentOfPages(titles);

        Map<String, String> map = pages.stream()
            .filter(page -> page.text().contains(target))
            .collect(Collectors.toMap(
                PageContainer::title,
                page -> replace(page.text(), target, replacement)
            ));

        Files.write(WORKLIST, List.of(Misc.makeList(map)));

        System.out.printf("Tamaño final: %d%n", map.size());

        if (map.size() != pages.size()) {
            List<String> all = pages.stream().map(PageContainer::title).collect(Collectors.toCollection(ArrayList::new));
            Set<String> found = map.keySet();
            all.removeAll(found);

            System.out.printf("No se ha encontrado la secuencia deseada en %d entradas: %s%n", found.size(), found.toString());
        }

        Files.writeString(TARGET, target);
        Files.writeString(REPLACEMENT, replacement);

        Map<String, OffsetDateTime> timestamps = pages.stream()
            .collect(Collectors.toMap(
                PageContainer::title,
                PageContainer::timestamp
            ));

        Files.writeString(INFO, new XStream().toXML(timestamps));
    }

    private static void edit() throws IOException, LoginException {
        String target = Files.readString(TARGET);
        String replacement = Files.readString(REPLACEMENT);
        Map<String, String> map = Misc.readList(Files.readString(WORKLIST));
        @SuppressWarnings("unchecked")
        var timestamps = (Map<String, OffsetDateTime>) new XStream().fromXML(INFO.toFile());

        System.out.printf("Título: %s%n", target);
        System.out.printf("Sustitución por: %s%n", replacement);
        System.out.printf("Tamaño de la lista: %d%n", map.size());

        wb.setThrottle(3000);
        List<String> errors = new ArrayList<>();

        String summary = String.format(SUMMARY_FORMAT, target, replacement);
        //String summary = "usunięcie znaków soft hyphen";

        for (Entry<String, String> entry : map.entrySet()) {
            String title = entry.getKey();
            String text = entry.getValue();
            OffsetDateTime timestamp = timestamps.get(title);

            try {
                wb.edit(title, text, summary, true, true, -2, timestamp);
            } catch (Exception e) {
                System.out.printf("Error en: %s%n", title);
                errors.add(title);
            }
        }

        if (!errors.isEmpty()) {
            System.out.printf("%d errores en: %s%n", errors.size(), errors.toString());
        }

        Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String replace(String s, String oldstring, String newstring) {
        return Pattern.compile(oldstring, Pattern.LITERAL).matcher(s).replaceAll(newstring);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}
