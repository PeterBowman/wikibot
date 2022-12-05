package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.Collator;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;

public final class MissingUkrainianAudio {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingUkrainianAudio/");

    public static void main(String[] args) throws Exception {
        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();

        try (var stream = dump.stream()) {
            var titles = stream
                .filter(XMLRevision::nonRedirect)
                .filter(XMLRevision::isMainNamespace)
                .map(Page::wrap)
                .flatMap(p -> p.getSection("ukraiński", true).stream())
                .flatMap(s -> s.getField(FieldTypes.PRONUNCIATION).stream())
                .filter(f -> f.isEmpty() || ParseUtils.getTemplates("audio", f.getContent()).isEmpty())
                .map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
                .sorted(Collator.getInstance(new Locale("uk")))
                .toList();

            var titlesPath = LOCATION.resolve("titles.txt");

            Files.writeString(titlesPath, String.format("Generated from %s.%n%s%n", dump.getDescriptiveFilename(), "-".repeat(60)));
            Files.write(titlesPath, titles, StandardOpenOption.APPEND);
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
                    dumpConfig.after(Files.readString(path));
                }

                dumpConfig.local();
            } else {
                new HelpFormatter().printHelp(MissingUkrainianAudio.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }
}
