package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikipedia.WikitextUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class MissingPolishGerunds {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    public static final Path LOCATION = Paths.get("./data/scripts.plwikt/MissingPolishGerunds/");
    private static final Path LIST = LOCATION.resolve("lista.txt");
    private static final Path ERRORS = LOCATION.resolve("errores.txt");
    private static final Path REFL = LOCATION.resolve("reflexivos.txt");
    private static final Path MISSING_AFF = LOCATION.resolve("missing aff.txt");
    private static final Path MISSING_NEG = LOCATION.resolve("missing neg.txt");
    private static final Path LIST_SER = LOCATION.resolve("list.xml");
    private static final Path MISSING_AFF_SER = LOCATION.resolve("aff_worklist.xml");
    private static final Path MISSING_NEG_SER = LOCATION.resolve("neg_worklist.xml");

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                checkGerunds();
                break;
            case '2':
                Login.login(wb);
                getMissing();
                break;
            case '3':
                makeArrayLists();
                break;
            case '8':
                Login.login(wb);
                writeAff();
                break;
            case '9':
                Login.login(wb);
                writeNeg();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void checkGerunds() throws IOException, LoginException {
        List<PageContainer> pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", Wiki.MAIN_NAMESPACE);

        List<String> errors = new ArrayList<>(100);
        List<String> refl = new ArrayList<>(100);
        List<String> gerunds = new ArrayList<>(2000);
        Map<String, String> list = new HashMap<>(2000);

        for (PageContainer page : pages) {
            String title = page.title();

            String inflection = Optional.of(Page.wrap(page))
                .flatMap(Page::getPolishSection)
                .flatMap(s -> s.getField(FieldTypes.INFLECTION))
                .map(Field::getContent)
                .orElse("");

            List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", inflection);
            List<String> temp = new ArrayList<>();

            for (String template : templates) {
                Map<String, String> map = ParseUtils.getTemplateParametersWithValue(template);

                String gerund = map.keySet().stream()
                    .filter(item -> item.trim().matches("z?robienie2?"))
                    .map(key -> map.get(key).trim())
                    .findFirst()
                    .orElse(null);

                if (gerund == null) {
                    errors.add(title + " - brak parametru");
                } else if (gerund.isEmpty()) {
                    errors.add(title + " - niewypełniony parametr");
                } else if (!gerund.endsWith("ie")) {
                    errors.add(title + " - " + WikitextUtils.recode(gerund));
                } else if (title.endsWith(" się") || title.endsWith(" sobie")) {
                    refl.add(title + " - " + gerund);
                } else if (!temp.contains(gerund)) {
                    temp.add(gerund);
                    gerunds.add(title + " - " + gerund);

                    if (list.containsKey(gerund)) {
                        String formatString = String.format(
                            "%s - duplikat ([[%s]], [[%s]])",
                            title, list.get(gerund), gerund
                        );
                        errors.add(formatString);
                    } else {
                        list.put(gerund, title);
                    }
                }
            }
        }

        Files.write(ERRORS, errors);
        Files.write(REFL, refl);
        Files.write(LIST, gerunds);

        Files.writeString(LIST_SER, new XStream().toXML(list));

        System.out.printf("Verbos escaneados: %d\n", pages.size());
        System.out.printf("Encontrados: %d\n", gerunds.size());
        System.out.printf("Reflexivos: %d\n", refl.size());
        System.out.printf("Errores: %d\n", errors.size());
    }

    public static void getMissing() throws IOException, LoginException {
        List<String> aff = new ArrayList<>(500);
        List<String> neg = new ArrayList<>(500);

        @SuppressWarnings("unchecked")
        var list = (Map<String, String>) new XStream().fromXML(LIST_SER.toFile());

        Set<String> set_aff = list.keySet();
        Set<String> set_neg = set_aff.stream()
            .map(gerund -> "nie" + gerund)
            .collect(Collectors.toSet());

        List<Map<String, Object>> infos_aff = wb.getPageInfo(new ArrayList<>(set_aff));
        List<Map<String, Object>> infos_neg = wb.getPageInfo(new ArrayList<>(set_neg));

        for (Map<String, Object> info : infos_aff) {
            if (info != null && !(boolean)info.get("exists")) {
                String gerund = (String)info.get("displaytitle");
                String verb = list.get(gerund);
                aff.add(verb + " - " + gerund);
            }
        }

        for (Map<String, Object> info : infos_neg) {
            if (info != null && !(boolean)info.get("exists")) {
                String gerund = (String)info.get("displaytitle");
                String verb = list.get(gerund.substring(3));
                neg.add(verb + " - " + gerund);
            }
        }

        Collator coll = Collator.getInstance(new Locale("pl"));
        coll.setStrength(Collator.SECONDARY);

        Collections.sort(aff, coll);
        Collections.sort(neg, coll);

        Files.write(MISSING_AFF, aff);
        Files.write(MISSING_NEG, neg);

        System.out.printf("Sustantivos faltantes: afirmativos - %d, negativos - %d%n", aff.size(), neg.size());
    }

    public static void makeArrayLists() throws IOException {
        List<String[]> list_aff = Files.readAllLines(MISSING_AFF).stream().map(line -> new String[]{
            line.substring(0, line.indexOf(" - ")),
            line.substring(line.indexOf(" - ") + 3)
        }).toList();

        List<String[]> list_neg = Files.readAllLines(MISSING_NEG).stream().map(line -> new String[]{
            line.substring(0, line.indexOf(" - ")),
            line.substring(line.indexOf(" - ") + 3)
        }).toList();

        Files.writeString(MISSING_AFF_SER, new XStream().toXML(list_aff));
        Files.writeString(MISSING_NEG_SER, new XStream().toXML(list_neg));

        System.out.printf("Formas extraídas: %d (aff), %d (neg)\n", list_aff.size(), list_neg.size());
    }

    public static void writeAff() throws LoginException, IOException {
        @SuppressWarnings("unchecked")
        var list = (List<String[]>) new XStream().fromXML(MISSING_AFF_SER.toFile());

        wb.setThrottle(2500);

        for (String[] entry : list) {
            String content = makePage(entry[0], entry[1], false);
            String summary = String.format("odczasownikowy od [[%s]]", entry[0]);
            wb.edit(entry[1], content, summary, false, true, -2, null);
        }

        Files.delete(MISSING_AFF_SER);
    }

    public static void writeNeg() throws LoginException, IOException {
        @SuppressWarnings("unchecked")
        var list = (List<String[]>) new XStream().fromXML(MISSING_NEG_SER.toFile());

        wb.setThrottle(2500);

        for (String[] entry : list) {
            String content = makePage(entry[0], entry[1], true);
            String summary = String.format("odczasownikowy zaprzeczony od [[%s]]", entry[0]);
            wb.edit(entry[1], content, summary, false, true, -2, null);
        }

        Files.delete(MISSING_NEG_SER);
    }

    public static String makePage(String verb, String gerund, boolean isNegate) {
        String aff = (isNegate ? gerund.substring(3) : gerund);
        String header = isNegate ? String.format("[[nie-|nie]][[%s]]", aff) : aff;
        String definition = String.format(
            "''rzeczownik, rodzaj nijaki''%n: (1.1) {{odczasownikowy od|%s%s}}",
            (isNegate ? "nie|" : ""), verb
        );
        String inflection = makeTemplate(gerund);
        String antonym = String.format("(1.1) [[%s%s]]", isNegate ? "" : "nie", aff);
        String relatedTerms = isNegate ? "" : String.format("{{czas}} [[%s]]", verb);

        Page page = Page.create(gerund, "język polski");
        Section section = page.getPolishSection().get();

        section.setHeaderTitle(header);
        section.getField(FieldTypes.DEFINITIONS).get().editContent(definition, true);
        section.getField(FieldTypes.INFLECTION).get().editContent(inflection, true);
        section.getField(FieldTypes.ANTONYMS).get().editContent(antonym, true);
        section.getField(FieldTypes.RELATED_TERMS).get().editContent(relatedTerms, true);

        return page.toString();
    }

    private static String makeTemplate(String gerund) {
        String stem = gerund.substring(0, gerund.length() - 1);
        StringBuilder sb = new StringBuilder();

        sb.append("(1.1) {{blm}}, {{odmiana-rzeczownik-polski\n");
        sb.append("|Mianownik lp = " + gerund + "\n");
        sb.append("|Dopełniacz lp = " + stem + "a\n");
        sb.append("|Celownik lp = " + stem + "u\n");
        sb.append("|Biernik lp = " + gerund + "\n");
        sb.append("|Narzędnik lp = " + gerund + "m\n");
        sb.append("|Miejscownik lp = " + stem + "u\n");
        sb.append("|Wołacz lp = " + gerund + "\n");
        sb.append("}}");

        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}
