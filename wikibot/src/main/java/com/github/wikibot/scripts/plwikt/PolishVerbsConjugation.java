package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class PolishVerbsConjugation {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/scripts.plwikt/PolishVerbsConjugation/");
    private static final Path SERIALIZED = LOCATION.resolve("targets.xml");
    private static final Path WORKLIST = LOCATION.resolve("worklist.txt");

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                getLists();
                break;
            case 'e':
                Login.login(wb);
                edit();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void getLists() throws IOException {
        List<PageContainer> pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", Wiki.MAIN_NAMESPACE);
        List<PageContainer> targets = new ArrayList<>();
        Map<String, String> map = new HashMap<>(1000);

        outer:
        for (PageContainer page : pages) {
            String content = Optional.of(Page.wrap(page))
                .flatMap(Page::getPolishSection)
                .flatMap(s -> s.getField(FieldTypes.INFLECTION))
                .map(Field::getContent)
                .orElse("");

            List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", content);

            for (String template : templates) {
                String parameter = ParseUtils.getTemplateParam(template, "koniugacja", true);

                if (parameter == null || parameter.isEmpty()) {
                    targets.add(page);
                    map.put(page.getTitle(), content);
                    continue outer;
                }
            }
        }

        System.out.printf("Encontrados: %d%n", targets.size());
        Files.writeString(SERIALIZED, new XStream().toXML(targets));
        Files.write(WORKLIST, List.of(Misc.makeList(map)));
    }

    public static void edit() throws IOException, LoginException {
        @SuppressWarnings("unchecked")
        var pages = (List<PageContainer>) new XStream().fromXML(SERIALIZED.toFile());
        Map<String, String> map = Misc.readList(Files.readString(WORKLIST));
        List<String> errors = new ArrayList<>();

        System.out.printf("Tamaño de la lista: %d%n", map.size());
        wb.setThrottle(2000);

        for (Entry<String, String> entry : map.entrySet()) {
            String title = entry.getKey();
            String content = entry.getValue();

            PageContainer page = pages.stream().filter(p -> p.getTitle().equals(title)).findAny().orElse(null);

            if (page == null) {
                System.out.printf("Error en \"%s\"%n", title);
                continue;
            }

            Page p = Page.wrap(page);

            Optional.of(p)
                .flatMap(Page::getPolishSection)
                .flatMap(s -> s.getField(FieldTypes.INFLECTION))
                .ifPresent(f -> f.editContent(content));

            String summary = "wstawienie modelu koniugacji; wer.: [[User:Peter Bowman]]";

            try {
                wb.edit(title, p.toString(), summary, false, true, -2, page.getTimestamp());
            } catch (Exception e) {
                errors.add(title);
            }
        }

        System.out.printf("Errores: %d - %s%n", errors.size(), errors.toString());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}
