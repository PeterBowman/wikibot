package com.github.wikibot.tasks.plwikisource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jsoup.Jsoup;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class UnpairedSectionTags {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikisource/UnpairedSectionTags/");
    private static final String WIKIPAGE = "Wikiskryba:PBbot/niesparowane znaczniki sekcji";
    private static final Wikibot wb = Wikibot.newSession("pl.wikisource.org");

    private static final String INTRO_FMT = """
        Strony, w których występuje znacznik <code>&lt;section begin="nazwa"&gt;</code> bez pary <code>&lt;section end="nazwa"&gt;</code> lub odwrotnie.
        Algorytm może nie być w pełni dokładny, jeżeli w wikikodzie występują niesparowane znaczniki HTML.

        Wygenerowano na podstawie zrzutu %s. Aktualizacja: ~~~~~.
        ----
        """;

	public static void main(String[] args) throws Exception {
        Login.login(wb);

        var datePath = LOCATION.resolve("last_date.txt");
        var optDump = getXMLDump(args, datePath);

        if (!optDump.isPresent()) {
            System.out.println("No dump file found.");
            return;
        }

        var dump = optDump.get();
		var titles = analyzeDump(dump);

        record Item(String title, String tag) {}

		var out = wb.getContentOfPages(titles).stream()
			.<Item>mapMulti((pc, consumer) -> {
				var optUnpairedTag = getFirstUnpairedTag(pc.text());

				if (optUnpairedTag.isPresent()) {
					consumer.accept(new Item(pc.title(), optUnpairedTag.get()));
				}
			})
			.sorted(Comparator.comparing(Item::title))
			.map(i -> String.format("#[[%s]]: %s", i.title(), i.tag()))
			.collect(Collectors.joining("\n"));

		wb.edit(WIKIPAGE, INTRO_FMT.formatted(dump.getDescriptiveFilename()) + out, "aktualizacja: " + dump.getDescriptiveFilename());
        Files.writeString(datePath, dump.getDirectoryName());
	}

    private static Optional<XMLDump> getXMLDump(String[] args, Path path) throws ParseException, IOException {
        var dumpConfig = new XMLDumpConfig("plwikisource").type(XMLDumpTypes.PAGES_ARTICLES);

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
                new HelpFormatter().printHelp(UnpairedSectionTags.class.getName(), options);
                throw new IllegalArgumentException();
            }
        } else {
            dumpConfig.remote();
        }

        return dumpConfig.fetch();
    }

    private static List<String> analyzeDump(XMLDump dump) {
        try (var stream = dump.stream()) {
			return stream
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> rev.getNamespace() == 100)
				.filter(rev -> getFirstUnpairedTag(rev.getText()).isPresent())
				.map(XMLRevision::getTitle)
				.toList();
		}
    }

	private static Optional<String> getFirstUnpairedTag(String text) {
		var doc = Jsoup.parseBodyFragment(text);
		var sections = doc.getElementsByTag("section");

		if (sections.isEmpty()) {
			return Optional.empty(); // no <section> elements present
		}

		var it = sections.iterator();

		outer:
		while (it.hasNext()) {
			var thisItem = it.next();

			if (!thisItem.hasAttr("begin")) {
				if (thisItem.hasAttr("end")) {
					return Optional.of(thisItem.attr("end")); // missing <section begin=""> pair
				} else {
					return Optional.of("brak begin/end"); // bad format
				}
			}

			it.remove();

			var begin = thisItem.attr("begin");

			while (it.hasNext()) { // find the matching pair
				var otherItem = it.next();

				if (otherItem.hasAttr("end") && otherItem.attr("end").equals(begin)) {
					it.remove();
					it = sections.iterator(); // start anew
					continue outer;
				}
			}

			return Optional.of(begin); // missing <section end=""> pair
		}

		return Optional.empty(); // no unpaired <section> elements
	}
}
