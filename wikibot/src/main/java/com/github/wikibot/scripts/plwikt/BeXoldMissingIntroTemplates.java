package com.github.wikibot.scripts.plwikt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractSection;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class BeXoldMissingIntroTemplates {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/scripts.plwikt/BeXoldMissingIntroTemplates/");
    private static final Path LOCATION_SER = LOCATION.resolve("ser/");

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                getList();
                break;
            case '2':
                makePreview();
                break;
            case 'e':
                Login.login(wb);
                edit();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void getList() throws IOException {
        List<PageContainer> pages = wb.getContentOfCategorymembers("białoruski (taraszkiewica) (indeks)", Wiki.MAIN_NAMESPACE);
        Map<String, OffsetDateTime> info = pages.stream()
            .collect(Collectors.toMap(
                PageContainer::getTitle,
                PageContainer::getTimestamp
            ));
        PrintWriter pw = new PrintWriter(LOCATION.resolve("worklist.txt").toFile());

        for (PageContainer page : pages) {
            String content = Optional.of(Page.wrap(page))
                .flatMap(p -> p.getSection("język białoruski (taraszkiewica)"))
                .map(AbstractSection::toString)
                .orElse("");

            int a = content.indexOf("\n") + 1;
            int b = content.indexOf("\n{{wymowa}}", a);

            String intro = content.substring(a, b);

            if (!intro.startsWith("{{ortografieBE") && !intro.contains("\n{{ortografieBE"))
                continue;

            pw.println(page.getTitle());
            pw.println(intro);
            pw.println("");
        }

        pw.close();
        Files.writeString(LOCATION_SER.resolve("info.xml"), new XStream().toXML(info));
    }

    public static void makePreview() throws IOException {
        BufferedReader br = Files.newBufferedReader(LOCATION.resolve("worklist.txt"));
        Map<String, String> pages = new LinkedHashMap<>();
        String line = null;
        String title = null;
        StringBuilder sb = new StringBuilder(500);

        while ((line = br.readLine()) != null) {
            if (line.equals("")) {
                pages.put(title, sb.toString().trim());
                title = null;
                sb = new StringBuilder(500);
                continue;
            }

            if (title == null) {
                title = line;
            } else {
                sb.append(line + "\n");
            }
        }

        br.close();
        System.out.println("Tamaño de la lista: " + pages.size());

        if (pages.size() == 0)
            return;

        Files.writeString(LOCATION_SER.resolve("preview.xml"), new XStream().toXML(pages));
    }

    public static void edit() throws IOException, LoginException {
        Path f1 = LOCATION_SER.resolve("preview.xml");
        Path f2 = LOCATION_SER.resolve("info.xml");

        @SuppressWarnings("unchecked")
        var pages = (Map<String, String>) new XStream().fromXML(f1.toFile());
        @SuppressWarnings("unchecked")
        var info = (Map<String, OffsetDateTime>) new XStream().fromXML(f2.toFile());

        int listsize = pages.size();
        System.out.println("Tamaño de la lista: " + listsize);

        if (listsize == 0)
            return;

        wb.setThrottle(5000);
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> conflicts = new ArrayList<>();
        ArrayList<String> edited = new ArrayList<>();

        //String summary = "uzupełnienie sekcji początkowych";
        //String summary = "konwersja zapisu ręcznego na wywoł//anie szablonu {{ortografieBE}}";
        String summary = "sekcje początkowe";
        //String summary = "{{ortografieBE|obcy=tak}}";

        for (Entry<String, String> entry : pages.entrySet()) {
            String page = entry.getKey();
            String data = entry.getValue();

            Map<String, String> lhm = wb.getSectionMap(page);
            String header = page + " (<span>język białoruski (taraszkiewica)</span>)";

            if (!lhm.containsValue(header)) {
                System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
                errors.add(page);
                continue;
            }

            int section = 0;

            for (Entry<String, String> entryh : lhm.entrySet()) {
                if (entryh.getValue().equals(header)) {
                    section = Integer.parseInt(entryh.getKey());
                    break;
                }
            }

            if (section == 0) {
                System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
                errors.add(page);
                continue;
            }

            String content = wb.getSectionText(page, section);

            int a = content.indexOf(" ==\n") + 4;
            int b = content.indexOf("\n{{wymowa}}", a);

            content = content.substring(0, a) + data + content.substring(b);

            OffsetDateTime timestamp = info.get(page);

            try {
                wb.edit(page, content, summary, true, true, section, timestamp);
                edited.add(page);
            } catch (ConcurrentModificationException e) {
                conflicts.add(page);
                System.out.println("Error, abortando edición...");
                continue;
            }
        }

        Files.deleteIfExists(f1);
        Files.deleteIfExists(f2);

        if (conflicts.size() != 0) {
            System.out.println("Conflictos en: " + conflicts.toString());
        }

        System.out.println("Editados: " + edited.size() + ", errores: " + errors);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}