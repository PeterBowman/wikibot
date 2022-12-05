package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.wikipedia.Wiki;

import com.github.wikibot.dumps.XMLDump;
import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class ProcessLanguageLinks {
    private static final Path LOCATION = Paths.get("./data/scripts/ProcessLanguageLinks/");
    private static final Path LANG_LIST = LOCATION.resolve("interwiki.txt");
    private static final Path TODO_LIST = LOCATION.resolve("worklist.txt");

    private static List<String> interwikis;
    private static Wikibot wb;

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("f", "find", false, "find remaining language links");
        options.addOption("r", "remove", false, "remove language links");
        options.addOption("d", "database", false, "database name");
        options.addOption("n", "name", false, "name of date directory of dump file");
        options.addRequiredOption("d", "domain", true, "wiki domain name");

        CommandLineParser parser = new DefaultParser();
        CommandLine line;

        if (args.length != 0) {
            line = parser.parse(options, args);
        } else {
            System.out.print("Select operation: ");
            line = parser.parse(options, Misc.readLine().split(" "));
        }

        String domain = line.getOptionValue("domain");
        interwikis = Files.readAllLines(LANG_LIST);
        wb = Wikibot.newSession(domain);
        Login.login(wb);

        if (line.hasOption("find")) {
            var database = line.getOptionValue("database");
            var dumpConfig = new XMLDumpConfig(database).type(XMLDumpTypes.PAGES_ARTICLES);

            if (line.hasOption("name")) {
                dumpConfig.local().at(line.getOptionValue("name"));
            } else {
                dumpConfig.remote();
            }

            findLanguageLinks(dumpConfig.fetch().get());
        } else if (line.hasOption("remove")) {
            removeLanguageLinks();
        } else {
            new HelpFormatter().printHelp(ProcessLanguageLinks.class.getName(), options);
            throw new IllegalArgumentException();
        }
    }

    private static void findLanguageLinks(XMLDump reader) throws IOException {
        final Pattern patt = Pattern.compile("\\[\\[\\s*(?:" + interwikis.stream().collect(Collectors.joining("|")) + ")\\s*:[^\\]]*?\\]\\]");

        List<String> list;

        try (Stream<XMLRevision> stream = reader.stream()) {
            list = stream
                .filter(rev -> rev.getNamespace() != Wiki.USER_NAMESPACE) // https://www.wikidata.org/wiki/Help:Sitelinks#Namespaces
                .filter(rev -> rev.getNamespace() % 2 == 0) // https://www.mediawiki.org/wiki/Manual:$wgInterwikiMagic
                .filter(rev -> !rev.getText().equals(Utils.replaceWithStandardIgnoredRanges(rev.getText(), patt, "")))
                .sorted(new XMLRevisionComparator())
                .map(XMLRevision::getTitle)
                .toList();
        }

        Files.write(TODO_LIST, list);
    }

    private static void removeLanguageLinks() throws IOException, LoginException {
        List<String> titles = Files.readAllLines(TODO_LIST);
        List<PageContainer> pages = wb.getContentOfPages(titles);

        wb.setMarkBot(true);
        wb.setMarkMinor(true);
        wb.setThrottle(5000);

        for (PageContainer page : pages) {
            String newText = interwikis.stream()
                .map(iw -> String.format("[[%s:%s]]", iw, page.getTitle()))
                .reduce(page.getText(), (text, link) -> text.replace(link, ""))
                .trim();

            wb.edit(page.getTitle(), newText, "-interwiki", page.getTimestamp());
        }
    }

    private static class XMLRevisionComparator implements Comparator<XMLRevision> {
        @Override
        public int compare(XMLRevision rev1, XMLRevision rev2) {
            if (rev1.getNamespace() != rev2.getNamespace()) {
                return Integer.compare(rev1.getNamespace(), rev2.getNamespace());
            }

            return rev1.getTitle().compareTo(rev2.getTitle());
        }
    }
}
