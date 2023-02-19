package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public class LatinInfinitiveBacklinks {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/LatinInfinitiveBacklinks/");
    private static final String TARGET_PAGE = "Wikisłownikarz:PBbot/łacińskie bezokoliczniki";
    private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]");

    private static final String[] LATIN_SHORTS = {"łac", "łaciński", "łacińskie", "łacina", "stłac"};
    private static final String[] LATIN_DESINENCES = {"are", "āre", "ere", "ēre", "ĕre", "ire", "īre"};

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    private static final String INTRO;

    static {
        INTRO = """
            Prawdopodobne wystąpienia łacińskich bezokoliczników ([[Specjalna:Permalink/8077565#Łacińskie bezokoliczniki|dyskusja]], [[/mapowania]]).
            __TOC__
            Dane na podstawie %s.
            ----
            === Szablony etymologii ===
            %s

            === Tłumaczenia w hasłach polskich ===
            %s

            === Wikilinki w hasłach łacińskich ===
            %s
            """;
    }

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();

        Login.login(wb);

        var etymResults = doEtymology(dump);
        System.out.println("Etymology results: " + etymResults.size());

        var translationResults = doTranslations(dump);
        System.out.println("Translation results: " + translationResults.size());

        var wikilinkResults = doWikilinks(dump);
        System.out.println("Wikilink results: " + wikilinkResults.size());

        var hashPath = LOCATION.resolve("hash.txt");
        var computedHash = Objects.hash(etymResults, translationResults, wikilinkResults);

        if (!Files.exists(hashPath) || Integer.parseInt(Files.readString(hashPath)) != computedHash) {
            Files.write(LOCATION.resolve("etymology.txt"), etymResults);
            Files.write(LOCATION.resolve("translations.txt"), translationResults);
            Files.write(LOCATION.resolve("wikilinks.txt"), wikilinkResults);

            var text = String.format(INTRO, dump.getDescriptiveFilename(),
                                     String.join("\n", etymResults),
                                     String.join("\n", translationResults),
                                     String.join("\n", wikilinkResults));

            wb.edit(TARGET_PAGE, text, "aktualizacja", false, false, -2, null);

            Files.writeString(hashPath, Integer.toString(computedHash));
        } else {
            System.out.println("No changes detected.");
        }

        Files.writeString(datePath, dump.getDirectoryName());
    }

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws ParseException, IOException {
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
                new HelpFormatter().printHelp(LatinInfinitiveBacklinks.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static List<String> doEtymology(XMLDump dump) throws IOException {
        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .<Item>mapMulti((rev, cons) -> {
                    var infinitives = Stream.of(
                            ParseUtils.getTemplates("etym", rev.getText()).stream(),
                            ParseUtils.getTemplates("etymn", rev.getText()).stream(),
                            ParseUtils.getTemplates("etym2", rev.getText()).stream(),
                            ParseUtils.getTemplates("etymn2", rev.getText()).stream()
                        )
                        .flatMap(Function.identity())
                        .map(ParseUtils::getTemplateParametersWithValue)
                        .filter(params -> StringUtils.equalsAny(params.getOrDefault("ParamWithoutName1", ""), LATIN_SHORTS))
                        .filter(params -> params.getOrDefault("ParamWithoutName2", "").length() > 3)
                        .map(params -> params.get("ParamWithoutName2"))
                        .filter(param -> !param.contains("-"))
                        .filter(param -> StringUtils.endsWithAny(param, LATIN_DESINENCES))
                        .sorted()
                        .distinct()
                        .toList();

                    if (!infinitives.isEmpty()) {
                        cons.accept(new Item(rev.getTitle(), infinitives));
                    }
                })
                .sorted(Comparator.comparing(Item::title))
                .map(i -> String.format("#[[%s]]: %s", i.title(), i.infinitives().stream().collect(Collectors.joining(", "))))
                .toList();
        }
    }

    private static List<String> doTranslations(XMLDump dump) {
        var patt = Pattern.compile("^\\* *łaciński:.+", Pattern.MULTILINE);

        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getPolishSection().stream())
                .flatMap(s -> s.getField(FieldTypes.TRANSLATIONS).stream())
                .filter(f -> f.getContent().contains("łaciński"))
                .<Item>mapMulti((f, cons) -> {
                    var infinitives = patt.matcher(f.getContent()).results()
                        .map(MatchResult::group)
                        .flatMap(line -> P_LINK.matcher(line).results())
                        .map(mr -> mr.group(1))
                        .map(String::strip)
                        .filter(title -> StringUtils.endsWithAny(title, LATIN_DESINENCES))
                        .sorted()
                        .distinct()
                        .toList();

                    if (!infinitives.isEmpty()) {
                        var title = f.getContainingSection().get().getContainingPage().get().getTitle();
                        cons.accept(new Item(title, infinitives));
                    }
                })
                .sorted(Comparator.comparing(Item::title, Collator.getInstance(new Locale("pl"))))
                .map(i -> String.format("#[[%s]]: %s", i.title(), String.join(", ", i.infinitives())))
                .toList();
        }
    }

    private static List<String> doWikilinks(XMLDump dump) throws IOException {
        var patt = Pattern.compile("→[^•]+•?");

        try (var stream = dump.stream()) {
            return stream
                .filter(XMLRevision::isMainNamespace)
                .filter(XMLRevision::nonRedirect)
                .map(Page::wrap)
                .flatMap(p -> p.getSection("łaciński", true).stream())
                .<Item>mapMulti((s, cons) -> {
                    var infinitives = s.getAllFields().stream()
                        .filter(f -> f.getFieldType() != FieldTypes.INFLECTION)
                        .filter(f -> !f.isEmpty())
                        .map(Field::getContent)
                        .map(content -> patt.matcher(content).replaceAll(""))
                        .map(content -> s.getHeader() + content) // also analyze links in header
                        .flatMap(content -> P_LINK.matcher(content).results())
                        .map(mr -> mr.group(1))
                        .map(String::strip)
                        .filter(title -> StringUtils.endsWithAny(title, LATIN_DESINENCES))
                        .sorted()
                        .distinct()
                        .toList();

                    if (!infinitives.isEmpty()) {
                        var title = s.getContainingPage().get().getTitle();
                        cons.accept(new Item(title, infinitives));
                    }
                })
                .sorted(Comparator.comparing(Item::title))
                .map(i -> String.format("#[[%s]]: %s", i.title(), String.join(", ", i.infinitives())))
                .toList();
        }
    }

    record Item(String title, List<String> infinitives) {}
}
