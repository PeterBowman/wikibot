package com.github.wikibot.scripts.plwiki;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.WebArchiveLookup;

public final class ArchiveExternalLinks {
	private static Wikibot wb;
	private static WebArchiveLookup webArchive;
	
	public static void main(String[] args) throws Exception {
		Options options = new Options();
		options.addRequiredOption("l", "link", true, "link URL");
		options.addOption("p", "protocol", true, "protocol (defaults to 'http')");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		CommandLineParser parser = new DefaultParser();
		CommandLine line = parser.parse(options, args);
		
		var url = line.getOptionValue("link");
		var protocol = line.getOptionValue("protocol", "http");
		
		wb = Login.createSession("pl.wikipedia.org");
		webArchive = new WebArchiveLookup();
		
		var list = wb.linksearch(url, protocol, Wiki.MAIN_NAMESPACE);
		var titles = list.stream().map(item -> item[0]).distinct().collect(Collectors.toList());
		var urls = list.stream().map(item -> item[1]).collect(Collectors.toSet());
		var pages = wb.getContentOfPages(titles);
		var storage = new HashMap<String, String>();
		
		System.out.printf("Found %d occurrences of %d target links on %d pages.%n", list.size(), urls.size(), titles.size());
		
		final var summary = String.format("archiwizacja „%s”", url);
		var edited = new ArrayList<String>();
		var errors = new ArrayList<>();
		
		for (var page : pages) {
			var links = extractLinks(page.getText());
			links.retainAll(urls);
			links.removeAll(storage.keySet());
			updateStorage(links, storage);
			
			var newText = page.getText();
			
			for (var entry : storage.entrySet()) {
				newText = replaceOccurrences(newText, entry.getKey(), entry.getValue());
			}
			
			if (!newText.equals(page.getText())) {
				try {
					wb.edit(page.getTitle(), newText, summary, page.getTimestamp());
					edited.add(page.getTitle());
				} catch (Throwable t) {
					t.printStackTrace();
					errors.add(page.getTitle());
				}
			}
		}
		
		System.out.printf("%d edited pages: %s%n", edited.size(), edited);
		System.out.printf("%d errors: %s%n", errors.size(), errors);
	}
	
	private static Set<String> extractLinks(String text) {
		var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
		var iterator = linkExtractor.extractLinks(text).iterator();
		var links = new HashSet<String>();
		
		while (iterator.hasNext()) {
			var linkSpan = iterator.next();
			var link = text.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
			links.add(link);
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
	
	private static String replaceOccurrences(String text, String target, String replacement) {
		var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
		var iterator = linkExtractor.extractLinks(text).iterator();
		var sb = new StringBuilder(text.length());
		var index = 0;
		
		while (iterator.hasNext()) {
			var linkSpan = iterator.next();
			var link = text.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
			
			sb.append(text.substring(index, linkSpan.getBeginIndex()));
			
			if (link.equals(target)) {
				sb.append(replacement);
			} else {
				sb.append(link);
			}
			
			index = linkSpan.getEndIndex();
		}
		
		sb.append(text.substring(index));
		return sb.toString();
	}
}
