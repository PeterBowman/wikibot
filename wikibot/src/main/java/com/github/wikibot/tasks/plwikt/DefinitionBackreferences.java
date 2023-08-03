package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public final class DefinitionBackreferences {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/DefinitionBackreferences/");
    private static final String TARGET_PAGE = "Wikipedysta:PBbot/wzajemne odwołania w znaczeniach";

    private static final Pattern P_REF = Pattern.compile("<ref\\b.*?(?<!/)>.*?</ref>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NEWLINE = Pattern.compile("\n");
    // was: private static final Pattern P_NUM = Pattern.compile("(?<!\\p{Alnum}[ \u00a0]|: ?)\\((?:(?:\\d+|\\d+-\\d+|\\d+\\.\\d+(?:-\\d+)?)(?:, *)?)+\\)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_NUM = Pattern.compile("\\[{2}[^\\|\\]]+?(?:\\|[^\\]]+?)?\\]{2}[a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]*[ \u00a0]+\\((?:(?:\\d+|\\d+[-\u2013]\\d+|\\d+\\.\\d+(?:[-\u2013]\\d+)?)(?:, *)?)+\\)");

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    private static final String PAGE_INTRO = """
        Znaczenia odwołujące się do innych znaczeń w hasłach polskich, odbiegając od zaleceń stylistycznych zapisanych w [[WS:ZTH#Numeracja znaczeń]].

        Dane na podstawie zrzutu %s. Aktualizacja: ~~~~~.{{język linków|polski}}
        ----
        %s
        """;

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
        var titles = analyzeDump(dump);
        var outPath = LOCATION.resolve("out.txt");

        if (Files.exists(outPath) && Files.readAllLines(outPath).equals(titles)) {
            System.out.println("No changes detected, aborting...");
            return;
        }

        Login.login(wb);

        wb.setMarkBot(false);
        wb.edit(TARGET_PAGE, String.format(PAGE_INTRO, dump.getDescriptiveFilename(), String.join("\n", titles)), "aktualizacja");

        Files.write(outPath, titles);
        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws IOException, ParseException {
        var dumpConfig = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES);

        if (args.length != 0) {
            var options = new Options();
            options.addOption("l", "local", false, "use latest local dump");

            var parser = new DefaultParser();
            var line = parser.parse(options, args);

            if (line.hasOption("local")) {
                if (Files.exists(path)) {
                    dumpConfig.after(Files.readString(path).strip());
                }

                dumpConfig.local();
            } else {
                new HelpFormatter().printHelp(BoldedSelflinks.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static String sanitize(String text) {
        text = ParseUtils.removeCommentsAndNoWikiText(text);

        for (var template : ParseUtils.getTemplatesIgnoreCase("wikipedia", text)) {
            text = StringUtils.remove(text, template);
        }

        for (var template : ParseUtils.getTemplates("Gloger", text)) {
            text = StringUtils.remove(text, template);
        }

        return P_REF.matcher(text).replaceAll("");
    }

    private static List<String> analyzeDump(XMLDump dump) {
        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getPolishSection().stream())
                .flatMap(s -> s.getField(FieldTypes.DEFINITIONS).stream())
                .filter(f -> P_NEWLINE.splitAsStream(sanitize(f.getContent()))
                    .map(P_NUM::matcher)
                    .anyMatch(Matcher::find)
                )
                .map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
                .sorted(Collator.getInstance(new Locale("pl")))
                .map(title -> String.format("#[[%s]]", title))
                .toList();
        }
    }
}
