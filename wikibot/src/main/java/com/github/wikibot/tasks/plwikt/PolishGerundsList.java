package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikipedia.Wiki.Revision;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.scripts.plwikt.MissingPolishGerunds;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.MorfeuszLookup;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;
import com.thoughtworks.xstream.XStream;

public class PolishGerundsList {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Plural pluralPL;
    private static final LocalizedNumberFormatter numberFormatPL;
    private static final Path location = Paths.get("./data/tasks.plwikt/PolishGerundsList/");
    private static final Path locationser = location.resolve("ser/");
    private static final Path location_old = MissingPolishGerunds.LOCATION;
    private static final String wikipage = "Wikipedysta:PBbot/rzeczowniki odczasownikowe";

    static {
        WordForms[] polishWords = new WordForms[] {
            new WordForms(new String[] {"czasownik", "czasowniki", "czasowników"}),
            new WordForms(new String[] {"rzeczownik odczasownikowy", "rzeczowniki odczasownikowe", "rzeczowników odczasownikowych"})
        };

        pluralPL = new Plural(PluralRules.POLISH, polishWords);

        numberFormatPL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
    }

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                foreignGerunds();
                break;
            case '2':
                Login.login(wb);
                //incorrectFormat();
                break;
            case '3':
                Login.login(wb);
                makeLists();
                break;
            case 'm':
                getMorfeuszList();
                break;
            case 'f':
                Login.login(wb);
                //writeFormat();
                break;
            case 'e':
                Login.login(wb);
                writeLists();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void foreignGerunds() throws IOException {
        List<String> all_gerunds = wb.whatTranscludesHere(List.of("Szablon:odczasownikowy od"), Wiki.MAIN_NAMESPACE).get(0);

        List<String> polish_gerunds = new ArrayList<>(Arrays.asList(org.wikipedia.ArrayUtils.intersection(
            wb.getCategoryMembers("polski (indeks)", Wiki.MAIN_NAMESPACE).toArray(String[]::new),
            all_gerunds.toArray(String[]::new)
        )));

        int all_gerunds_size = all_gerunds.size();
        all_gerunds.removeAll(polish_gerunds);

        System.out.println("Total de gerundios: " + all_gerunds_size + ", en polaco: " + polish_gerunds.size() + ", otros: " + all_gerunds.size());

        int c = 0;
        for (String gerund : all_gerunds) {
            System.out.println(++c + ". " + gerund);
        }
    }

    /*public static void incorrectFormat() throws IOException {
        String[] titles = wb.whatLinksHere("Kategoria:Język polski - rzeczowniki rodzaju nijakiego");
        String[] all_gerunds = wb.whatTranscludesHere("Szablon:odczasownikowy od", 0);

        System.out.println("Sustantivos neutros: " + titles.length + ", gerundios: " + all_gerunds.length);

        List<String> list = new ArrayList<>(Arrays.asList(titles));
        list.removeAll(new ArrayList<>(Arrays.asList(all_gerunds)));

        System.out.println("Tamaño tras excluir gerundios con plantilla: " + list.size());

        List<String> nongerund_list = new ArrayList<>(list.size());

        for (String page : list) {
            if (!page.endsWith("ie")) {
                nongerund_list.add(page);
            }
        }

        list.removeAll(nongerund_list);

        System.out.println("Tamaño tras excluir sustantivos no acabados en -ie: " + list.size());

        Map<String, String> contentmap = wb.getContentOfPageList(list, "język polski", 400);
        PageContainer[] pages = wb.getContentOfPages(list.toArray(new String[list.size()]), 400);
        List<String[]> work_list = new ArrayList<>(pages.length);
        MyArrayList txt = new MyArrayList(contentmap.size());
        String patt = ".*?\\(\\d\\.\\d\\)\\s\\{\\{rzecz.*";

        contentmap.forEach((title, content) -> {
            String defs = EntryParser.getField(content, FieldTypes.DEFINITIONS);
            defs = defs.replace("\n", "_N_");
            defs = defs.substring(defs.indexOf("rzeczownik") + 1);

            if (defs.matches(patt)) {
                int rzecz = defs.indexOf("rzecz");
                //rzecz = defs.indexOf("rzecz", rzecz + 1);

                //if (rzecz == -1) break;

                int start = defs.substring(0, rzecz).lastIndexOf("_N_") + 3;
                int end = defs.indexOf("_N_", start);

                String def = defs.substring(start, end);

                String verb = def.substring(def.indexOf("[[") + 2, def.indexOf("]]"));

                txt.add(title + " - " + def + " || " + verb);
                work_list.add(new String[]{title, verb});
            }
        });

        IOUtils.writeToFile(txt.join("\n"), location + "sin formato.txt");
        System.out.println("Errores encontrados: " + work_list.size());

        try {
            Misc.serialize(work_list, locationser + "sin formato.ser");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/

    public static void getMorfeuszList() throws IOException {
        Set<String> gers = new HashSet<>(300000);
        Set<String> substs = new HashSet<>(150000);

        MorfeuszLookup morfeuszLookup = MorfeuszLookup.fromPath(Paths.get(""));

        try (var stream = morfeuszLookup.stream()) {
            stream.forEach(record -> {
                String firstTag = record.tagsAsList().get(0);

                if (firstTag.equals("ger")) {
                    gers.add(record.form());
                } else if (firstTag.equals("subst") && !substs.contains(record.lemma())) {
                    substs.add(record.lemma());
                }
            });
        }

        System.out.printf("Gerundios: %d, sustantivos: %d%n", gers.size(), substs.size());
        Files.write(locationser.resolve("gers.txt"), gers);
        Files.write(locationser.resolve("substs.txt"), substs);
    }

    public static void writeFormat() throws IOException, LoginException {
        @SuppressWarnings("unchecked")
        var list = (List<String[]>) new XStream().fromXML(locationser.resolve("sin formato.xml").toFile());

        wb.setThrottle(10000);
        System.out.println("Tamaño de la lista: " + list.size());

        for (String[] entry : list) {
            String page = entry[0];
            Revision rev = wb.getTopRevision(page);
            OffsetDateTime timestamp = rev.getTimestamp();

            String content = wb.getPageText(List.of(page)).get(0);

            if (content == null) {
                throw new FileNotFoundException("Page not found: " + page);
            }

            int a = content.indexOf("{{znaczenia}}");
            a = content.indexOf(") {{rzecz}} ");
            int b = content.indexOf("\n", a);

            String newcontent = content.substring(0, a);
            newcontent += ") {{odczasownikowy od|" + entry[1] + "}}";
            newcontent += content.substring(b);

            wb.edit(page, newcontent, "{{odczasownikowy od}}", true, true, -2, timestamp);
        }
    }

    public static void makeLists() throws IOException, InterruptedException, ExecutionException {
        String[] intersection = org.wikipedia.ArrayUtils.intersection(
            wb.getCategoryMembers("Język polski - rzeczowniki rodzaju nijakiego", Wiki.MAIN_NAMESPACE).toArray(String[]::new),
            wb.whatTranscludesHere(List.of("Szablon:odczasownikowy od"), Wiki.MAIN_NAMESPACE).get(0).toArray(String[]::new)
        );

        System.out.printf("Gerundios detectados por transclusión: %d\n", intersection.length);

        @SuppressWarnings("unchecked")
        var list = (Map<String, String>) new XStream().fromXML(location_old.resolve("list.xml").toFile());

        System.out.printf("Gerundios extraídos de plantillas de conjugación: %d\n", list.size());

        var gers = Files.readAllLines(locationser.resolve("gers.txt"));
        var substs = Files.readAllLines(locationser.resolve("substs.txt"));

        Set<String> gerunds = new HashSet<>(list.keySet());
        Collections.addAll(gerunds, intersection);

        System.out.printf("Gerundios en total: %d\n", gerunds.size());

        List<String> listOnlyDefinitions = new ArrayList<>(200);
        List<String> listOnlyTemplates = new ArrayList<>(500);
        List<String> listErrors = new ArrayList<>(200);
        List<String> listNoDictEntry = new ArrayList<>(500);

        List<PageContainer> pages = wb.getContentOfPages(gerunds);

        for (PageContainer page : pages) {
            String title = page.getTitle();
            Page p = Page.wrap(page);
            Section s = p.getPolishSection().get();
            String definitionsText = null;

            try {
                definitionsText = s.getField(FieldTypes.DEFINITIONS).get().getContent();
            } catch (NullPointerException | NoSuchElementException e) {
                System.out.printf("NullPointerException en %s%n", title);
                continue;
            }

            if (!definitionsText.contains("{{odczasownikowy od|")) {
                listOnlyDefinitions.add(title);
                continue;
            }

            if (!definitionsText.contains(", rodzaj nijaki''")) {
                listErrors.add(String.format("%s - prawdopodobnie nieprawidłowy rodzaj", title));
                continue;
            }

            int start = definitionsText.indexOf("\n", definitionsText.indexOf(", rodzaj nijaki''")) + 1;
            String[] lines = definitionsText.substring(start).trim().split("\n");
            boolean hasDef = false;

            for (String line : lines) {
                if (!line.startsWith(":")) {
                    break;
                }

                if (!line.contains("{{odczasownikowy od|")) {
                    hasDef = true;
                    break;
                }
            }

            if (substs.contains(title)) {
                if (!hasDef) {
                    listOnlyTemplates.add(title);
                }
            } else if (!gers.contains(title)) {
                listNoDictEntry.add(title);
            }
        }

        System.out.printf(
            "Faltan: %d. Solo definición: %d, solo plantilla: %d, errores: %d, sin entrada: %d\n",
            gerunds.size() - pages.size(), listOnlyDefinitions.size(), listOnlyTemplates.size(), listErrors.size(), listNoDictEntry.size()
        );

        Files.write(locationser.resolve("sin plantilla.txt"), listOnlyDefinitions);
        Files.write(locationser.resolve("sin definición.txt"), listOnlyTemplates);
        Files.write(locationser.resolve("sin entrada.txt"), listNoDictEntry);
        Files.write(location.resolve("errores.txt"), listErrors);

        List<String> listOnlyDefinitions2 = listOnlyDefinitions.stream()
            .map(gerund -> String.format("%s (%s)", gerund, list.getOrDefault(gerund, "---")))
            .toList();

        Files.write(location.resolve("sin plantilla2.txt"), listOnlyDefinitions2);

        Files.writeString(locationser.resolve("stats.txt"), String.format(
            "Analizowano %s %s z tabelką odmiany oraz %s %s.",
            numberFormatPL.format(list.size()), pluralPL.pl(list.size(), "czasownik"),
            numberFormatPL.format(gerunds.size()), pluralPL.pl(gerunds.size(), "rzeczownik odczasownikowy")
        ));
    }

    public static void writeLists() throws IOException, LoginException {
        @SuppressWarnings("unchecked")
        var list = (Map<String, String>) new XStream().fromXML(location_old.resolve("list.xml").toFile());
        var listOnlyDefinitions = Files.readAllLines(locationser.resolve("sin plantilla.txt"));
        var listOnlyTemplates = Files.readAllLines(locationser.resolve("sin definición.txt"));
        var listNoDictEntry = Files.readAllLines(locationser.resolve("sin entrada.txt"));

        // Page creation

        com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(wikipage);
        String stats = Files.readString(locationser.resolve("stats.txt"));
        page.setIntro(stats + " Aktualizacja: ~~~~~.");

        // Only definition

        com.github.wikibot.parsing.Section onlyDefinitionSection = com.github.wikibot.parsing.Section.create("bez szablonu", 3);
        List<String> tempList = new ArrayList<>(listOnlyDefinitions.size());

        for (String entry : listOnlyDefinitions) {
            String formatted = String.format("# [[%s]] ([[%s]])", entry, list.getOrDefault(entry, ""));
            tempList.add(formatted);
        }

        Collator coll = Collator.getInstance(new Locale("pl"));
        coll.setStrength(Collator.SECONDARY);

        Collections.sort(tempList, coll);
        onlyDefinitionSection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");

        // Only template

        com.github.wikibot.parsing.Section onlyTemplateSection = com.github.wikibot.parsing.Section.create("brakujące definicje", 3);
        tempList = new ArrayList<>(listOnlyTemplates.size());

        for (String entry : listOnlyTemplates) {
            String formatted = String.format("# [[%s]] ([http://sjp.pwn.pl/szukaj/%s SJP])", entry, entry);
            tempList.add(formatted);
        }

        Collections.sort(tempList, coll);
        onlyTemplateSection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");

        // No dictionary entry

        com.github.wikibot.parsing.Section noDictEntrySection = com.github.wikibot.parsing.Section.create("niewystępujące w słowniku", 3);
        tempList = listNoDictEntry.stream()
            .map(entry -> String.format("# [[%s]]", entry))
            .collect(Collectors.toCollection(ArrayList::new));

        Collections.sort(tempList, coll);
        noDictEntrySection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");

        // Possible errors

        com.github.wikibot.parsing.Section possibleErrors = com.github.wikibot.parsing.Section.create("możliwe błędy", 3);

        tempList = Stream.of(
                Files.readAllLines(location_old.resolve("errores.txt")),
                Files.readAllLines(location.resolve("errores.txt"))
            )
            .flatMap(Collection::stream)
            .map(line -> String.format("# [[%s]] – %s", (Object[])line.split(" - ")))
            .collect(Collectors.toCollection(ArrayList::new));

        Collections.sort(tempList, coll);
        possibleErrors.setIntro(String.join("\n", tempList));

        // Reflexive verbs

        com.github.wikibot.parsing.Section reflexiveVerbs = com.github.wikibot.parsing.Section.create("czasowniki zwrotne", 3);

        tempList = Files.readAllLines(location_old.resolve("reflexivos.txt")).stream()
            .map(line -> String.format("[[%s]]", line.substring(0, line.indexOf(" - "))))
            .collect(Collectors.toCollection(ArrayList::new));

        Collections.sort(tempList, coll);
        reflexiveVerbs.setIntro(String.join(", ", tempList));

        // Append all sections

        page.appendSections(List.of(onlyDefinitionSection, onlyTemplateSection, noDictEntrySection, possibleErrors, reflexiveVerbs));

        String pageContent = wb.getPageText(List.of(wikipage)).get(0);
        pageContent = pageContent.substring(0, pageContent.indexOf("-->") + 3);
        page.setIntro(pageContent + "\n" + page.getIntro());

        wb.edit(wikipage, page.toString(), "aktualizacja", false, false, -2, null);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}