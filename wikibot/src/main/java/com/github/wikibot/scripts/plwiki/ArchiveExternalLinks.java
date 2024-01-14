package com.github.wikibot.scripts.plwiki;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.WebArchiveLookup;

public final class ArchiveExternalLinks {
    private static final List<String> CITE_TEMPLATES = List.of("Cytuj", "Cytuj pismo", "Cytuj stronę");

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");
    private static WebArchiveLookup webArchive;

    public static void main(String[] args) throws Exception {
        CommandLine line = parseArguments(args);

        final var url = line.getOptionValue("link");
        final var protocol = line.getOptionValue("protocol", "http");

        Login.login(wb);
        webArchive = new WebArchiveLookup();

        var list = wb.linksearch(url, protocol, Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.CATEGORY_NAMESPACE);
        var titles = list.stream().map(item -> item[0]).distinct().collect(Collectors.toCollection(ArrayList::new));

        // retain user sandboxes
        titles.removeIf(title -> wb.namespace(title) == Wiki.USER_NAMESPACE && wb.getRootPage(title).equals(title));

        var urls = list.stream().map(item -> item[1]).collect(Collectors.toSet());
        var pages = wb.getContentOfPages(titles);
        var storage = new HashMap<String, String>();

        System.out.printf("Found %d occurrences of %d target links on %d pages.%n", list.size(), urls.size(), titles.size());

        final var summary = String.format("archiwizacja „%s”", url);
        var edited = new ArrayList<String>();
        var errors = new ArrayList<String>();

        for (var page : pages) {
            var links = extractLinks(page.text());
            links.retainAll(urls);
            links.removeAll(storage.keySet());
            updateStorage(links, storage);

            var newText = page.text();

            for (var entry : storage.entrySet()) {
                var ignoredCiteTemplateRanges = getIgnoredCiteTemplateRanges(newText);
                var ignoredRanges = Utils.getCombinedRanges(List.of(ignoredCiteTemplateRanges, Utils.getStandardIgnoredRanges(newText)));
                newText = replaceOccurrences(newText, entry.getKey(), entry.getValue(), ignoredRanges);
            }

            if (!newText.equals(page.text())) {
                try {
                    wb.edit(page.title(), newText, summary, page.timestamp());
                    edited.add(page.title());
                } catch (Throwable t) {
                    t.printStackTrace();
                    errors.add(page.title());
                }
            }
        }

        System.out.printf("%d edited pages: %s%n", edited.size(), edited);
        System.out.printf("%d errors: %s%n", errors.size(), errors);
    }

    private static CommandLine parseArguments(String[] args) throws ParseException {
        Options options = new Options();
        options.addRequiredOption("l", "link", true, "link URL");
        options.addOption("p", "protocol", true, "protocol (defaults to 'http')");

        if (args.length == 0) {
            System.out.print("Options: ");
            args = Misc.readArgs();
        }

        CommandLineParser parser = new DefaultParser();
        return parser.parse(options, args);
    }

    private static String normalizeLink(String link) {
        final var specialChars = new char[]{ '#', '|' };

        if (StringUtils.containsAny(link, specialChars)) {
            link = link.substring(0, StringUtils.indexOfAny(link, specialChars));
        }

        return link;
    }

    private static Set<String> extractLinks(String text) {
        var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL, LinkType.WWW)).build();
        var iterator = linkExtractor.extractLinks(text).iterator();
        var links = new HashSet<String>();

        while (iterator.hasNext()) {
            var linkSpan = iterator.next();
            var link = text.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
            links.add(normalizeLink(link));
        }

        return links;
    }

    private static void updateStorage(Set<String> links, Map<String, String> storage) throws IOException, InterruptedException {
        for (var link : links) {
            var item = webArchive.queryUrl(link, null);

            if (item.isAvailable()) {
                storage.put(item.getRequestUrl().toExternalForm(), item.getArchiveUrl().toExternalForm());
            }
        }
    }

    private static List<Range<Integer>> getIgnoredCiteTemplateRanges(String text) {
        var ignoredTemplates = CITE_TEMPLATES.stream()
            .flatMap(templateName -> ParseUtils.getTemplatesIgnoreCase(templateName, text).stream())
            .filter(template -> !ParseUtils.getTemplateParametersWithValue(template).getOrDefault("archiwum", "").isBlank())
            .distinct()
            .toList();

        var ranges = new ArrayList<Range<Integer>>();

        for (var template : ignoredTemplates) {
            var index = 0;

            while ((index = text.indexOf(template, index)) != -1) {
                var range = Range.of(index, index + template.length() - 1); // inclusive-inclusive
                ranges.add(range);
                index += template.length();
            }
        }

        return ranges;
    }

    private static String replaceOccurrences(String text, String target, String replacement, List<Range<Integer>> ignoredRanges) {
        var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
        var iterator = linkExtractor.extractLinks(text).iterator();
        var sb = new StringBuilder(text.length());
        var index = 0;

        while (iterator.hasNext()) {
            var linkSpan = iterator.next();

            if (Utils.containedInRanges(ignoredRanges, linkSpan.getBeginIndex())) {
                continue;
            }

            var link = text.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
            link = normalizeLink(link);

            sb.append(text.substring(index, linkSpan.getBeginIndex()));

            if (link.equals(target)) {
                sb.append(replacement);
            } else {
                sb.append(link);
            }

            index = linkSpan.getBeginIndex() + link.length();
        }

        sb.append(text.substring(index));
        return sb.toString();
    }
}
