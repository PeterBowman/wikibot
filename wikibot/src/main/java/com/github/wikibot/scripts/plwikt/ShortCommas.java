package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.wikipedia.Wiki;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class ShortCommas {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/scripts.plwikt/ShortCommas/");
    private static final Path LOCATION_SER = LOCATION.resolve("ser/");
    private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
    private static final Path SHORTS = LOCATION.resolve("shorts.txt");
    private static final Path INFO = LOCATION_SER.resolve("info.xml");
    private static Pattern patt;

    static {
        List<String> shorts = List.of(
            "starop", "akadfranc", "algierarab", "arabhiszp", "belgfranc", "belghol", "brazport", "egiparab", "eurport",
            "francmetr", "hiszpam", "kanadang", "kanadfranc", "korpłd", "korpłn", "lewantarab", "libijarab",
            "marokarab", "nidhol", "niemdial", "niemrfn", "surinhol", "szkocang", "szwajcfranc", "szwajcniem",
            "szwajcwł", "tunezarab", "nłac", "płac", "stłac",
            // templates
            "skrócenie od", "odczasownikowy od", "zob", "hiszp-pis", "niem-pis", "dokonany od", "niedokonany od"
        );

        try {
            Set<String> tempSet = new HashSet<>(shorts);
            tempSet.addAll(Files.readAllLines(SHORTS));
            shorts = new ArrayList<>(tempSet);
        } catch (IOException e) {
            System.out.printf("No se ha encontrado el archivo shorts.txt");
        }

        System.out.printf("Tamaño de la lista de abreviaturas: %d%n", shorts.size());
        String shortlist = String.join("|", shorts);
        patt = Pattern.compile(".*?\\{\\{ *?(?:" + shortlist + ") *?\\}\\}(?:(;) \\{\\{ *?zob *?\\||(,) (?:\\{\\{ *?(?:" + shortlist + ") *?(?:\\}|\\|)|'')).*", Pattern.DOTALL);
    }

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                getList();
                break;
            case '2':
                stripCommas();
                break;
            case 's':
                Login.login(wb);
                getShorts();
                break;
            case 'e':
                Login.login(wb);
                edit();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void getShorts() throws IOException {
        List<String> templates = wb.getCategoryMembers("Szablony skrótów", Wiki.TEMPLATE_NAMESPACE).stream()
            .map(template -> template.replace("Szablon:", ""))
            .toList();

        Files.write(SHORTS, templates);
    }

    public static void getList() throws IOException {
        Set<String> wlh = new HashSet<>(wb.whatTranscludesHere(List.of("Szablon:skrót"), Wiki.MAIN_NAMESPACE).get(0));
        List<PageContainer> pages = Collections.synchronizedList(new ArrayList<>(250));
        XMLDump dump = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES).remote().fetch().get();

        try (Stream<XMLRevision> stream = dump.stream()) {
            stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .filter(rev -> wlh.contains(rev.getTitle()))
                .filter(rev -> patt.matcher(rev.getText()).matches())
                .map(XMLRevision::toPageContainer)
                .forEach(pages::add);
        }

        System.out.printf("Tamaño de la lista: %d%n", pages.size());
        Files.writeString(INFO, new XStream().toXML(pages));
    }

    public static void stripCommas() throws IOException {
        @SuppressWarnings("unchecked")
        var pages = (List<PageContainer>) new XStream().fromXML(INFO.toFile());

        System.out.printf("Tamaño de la lista: %d%n", pages.size());

        if (pages.isEmpty()) {
            return;
        }

        Map<String, Collection<String>> map = new HashMap<>(pages.size());
        ListIterator<PageContainer> lt = pages.listIterator();

        while (lt.hasNext()) {
            PageContainer page = lt.next();
            String text = page.text();
            String[] lines = text.split("\n");
            List<String> newLines = new ArrayList<>();
            List<String> targets = new ArrayList<>();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher m = patt.matcher(line);

                if (m.matches()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("#%d%n", i + 1));
                    sb.append(line);

                    m.reset();

                    while (m.find()) {
                        int group = m.group(1) == null ? 2 : 1;
                        line = line.substring(0, m.start(group)) + line.substring(m.end(group));
                        m = patt.matcher(line);
                    }

                    sb.append("\n");
                    sb.append(line);
                    targets.add(sb.toString());
                }

                newLines.add(line);
            }

            if (!targets.isEmpty()) {
                map.put(page.title(), targets);
                lt.set(new PageContainer(page.title(), String.join("\n", newLines), page.revid(), page.timestamp()));
            }
        }

        if (map.size() != pages.size()) {
            String[] errors = pages.stream()
                .filter(page -> !map.containsKey(page.title()))
                .map(PageContainer::title)
                .toArray(String[]::new);

            System.out.printf("%d errores: %s%n", errors.length, errors.toString());
        }

        Files.write(WORKLIST, List.of(Misc.makeMultiList(map, "\n\n")));
        Files.writeString(INFO, new XStream().toXML(pages));
    }

    public static void edit() throws IOException {
        @SuppressWarnings("unchecked")
        var pages = (List<PageContainer>) new XStream().fromXML(INFO.toFile());
        Map<String, String[]> map = Misc.readMultiList(Files.readString(WORKLIST), "\n\n");
        List<String> errors = new ArrayList<>();

        System.out.printf("Tamaño de la lista: %d%n", map.size());
        wb.setThrottle(3500);

        for (Entry<String, String[]> entry : map.entrySet()) {
            String title = entry.getKey();
            PageContainer page = pages.stream().filter(p -> p.title().equals(title)).findAny().orElse(null);

            if (page == null) {
                System.out.printf("Error en \"%s\"%n", title);
                errors.add(title);
                continue;
            }

            try {
                String summary = "usunięcie znaku oddzielającego między kwalifikatorami";
                wb.edit(title, page.text(), summary, true, true, -2, page.timestamp());
            } catch (Exception e) {
                System.out.printf("Error en \"%s\"%n", title);
                errors.add(title);
            }
        }

        if (!errors.isEmpty()) {
            System.out.printf("%d errores en: \"%s\"%n", errors.toString());
        }

        Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}
