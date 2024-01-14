package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class ReviewPolishGerunds {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final Path LOCATION = Paths.get("./data/scripts.plwikt/ReviewPolishGerunds/");
    private static final Path PAGES = LOCATION.resolve("pages.txt");
    private static final Path INFO = LOCATION.resolve("info.xml");
    private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
    private static final Pattern P_RELATED_TERMS = Pattern.compile(": \\{\\{czas\\}\\} \\[\\[.+?\\]\\] \\{\\{n?dk\\}\\}");

    private static void selector(char op) throws Exception {
        switch (op) {
            case '1':
                Login.login(wb);
                getLists();
                break;
            case 'r':
                Login.login(wb);
                review();
                break;
            default:
                System.out.print("Número de operación incorrecto.");
        }
    }

    public static void getLists() throws IOException {
        List<String> titles = Files.readAllLines(PAGES);
        List<PageContainer> pages = wb.getContentOfPages(titles);
        Map<String, String> worklist = new LinkedHashMap<>();

        System.out.printf("Tamaño de la lista: %d%n", titles.size());

        for (PageContainer page : pages) {
            String title = page.title();
            Page p = Page.wrap(page);
            Section section = p.getPolishSection().get();

            String definition = section.getField(FieldTypes.DEFINITIONS).get().getContent();
            List<String> templates = ParseUtils.getTemplates("odczasownikowy od", definition);

            if (templates.isEmpty()) {
                continue;
            }

            String template = templates.get(0);
            String verb = ParseUtils.getTemplateParam(template, 1);

            Field relatedTerms = section.getField(FieldTypes.RELATED_TERMS).get();
            String relatedTermsText = relatedTerms.getContent();
            String patternString = String.format(": (1.1) {{etymn|pol|%s|-anie}}", verb);
            Pattern p_etymology = Pattern.compile(patternString, Pattern.LITERAL);
            Field etymology = section.getField(FieldTypes.ETYMOLOGY).get();
            String etymologyText = etymology.getContent();

            if (
                !P_RELATED_TERMS.matcher(relatedTermsText).matches() ||
                !p_etymology.matcher(etymologyText).matches()
            ) {
                continue;
            }

            String newContent = String.format("{{czas}} [[%s]]", verb);
            relatedTerms.editContent(newContent, true);
            etymology.editContent("");

            String modelText = MissingPolishGerunds.makePage(verb, title, false).replace("\r", "").trim();
            String original = p.toString().trim();

            if (!modelText.equals(original)) {
                continue;
            }

            worklist.put(title, relatedTermsText);
        }

        System.out.printf("Tamaño de la lista: %d%n", worklist.size());

        Files.writeString(INFO, new XStream().toXML(pages));
        Files.write(WORKLIST, List.of(Misc.makeList(worklist)));
    }

    public static void review() throws IOException, LoginException {
        @SuppressWarnings("unchecked")
        var pages = (List<PageContainer>) new XStream().fromXML(INFO.toFile());
        Map<String, String> worklist = Misc.readList(Files.readString(WORKLIST));
        Set<String> titles = worklist.keySet();
        List<String> errors = new ArrayList<>();

        for (String title : titles) {
            PageContainer page = pages.stream().filter(p -> p.title().equals(title)).findAny().orElse(null);

            if (page == null) {
                System.out.printf("Error en \"%s\"%n", title);
                continue;
            }

            Revision rev = wb.getTopRevision(title);

            if (!rev.getTimestamp().equals(page.timestamp())) {
                System.out.printf("Conflicto en \"%s\"%n", title);
                errors.add(title);
                continue;
            }

            wb.review(rev, "");
        }

        System.out.printf("Detectados %d conflictos en: %s%n", errors.size(), errors.toString());
        Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Option: ");
        var op = (char) System.in.read();
        selector(op);
    }
}
