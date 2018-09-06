package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class InconsistentHeaderTitles {
	private static final String LOCATION = "./data/tasks.plwikt/InconsistentHeaderTitles/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/nagłówki";
	private static final String PAGE_INTRO;
	
	private static final int COLUMN_ELEMENT_THRESHOLD = 50;
	private static final int NUMBER_OF_COLUMNS = 3;
	
	// from Linker::formatLinksInComment in Linker.php
	private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]");
	
	// https://en.wikipedia.org/wiki/Whitespace_character#Unicode
	// + SOFT HYPHEN (00AD), LEFT-TO-RIGHT MARK (200E), RIGHT-TO-LEFT MARK (200F)
	private static final Pattern P_WHITESPACE = Pattern.compile("[\u0009\u00a0\u00ad\u1680\u180e\u2000-\u200f\u2028-\u2029\u202f\u205f-\u2060\u3000\ufeff]");
	
	// http://www.freeformatter.com/html-entities.html
	private static final Pattern P_ENTITIES = Pattern.compile("&(nbsp|ensp|emsp|thinsp|zwnj|zwj|lrm|rlm);");
	
	private static final List<String> HEADER_TEMPLATES = Arrays.asList("zh", "ko", "ja");
	
	private static PLWikt wb;
	
	private static Map<String, Collection<Item>> map;
	
	static {
		PAGE_INTRO =
			"Spis zawiera listę haseł z rozbieżnością pomiędzy nazwą strony a tytułem sekcji językowej. " +
			"Odświeżany jest automatycznie przy użyciu wewnętrzej listy ostatnio przenalizowanych stron. " +
			"Zmiany wykonane ręcznie na tej stronie nie będą uwzględniane przez bota. " +
			"Spacje niełamliwe i inne znaki niewidoczne w podglądzie strony oznaczono symbolem " +
			"<code>&#9251;</code> ([[w:en:Whitespace character#Unicode]])." +
			"\n__NOEDITSECTION__\n{{TOChorizontal}}";
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Collator collator = Misc.getCollator("pl");
		map = new ConcurrentSkipListMap<>(collator::compare);
		
		CommandLine line = readOptions(args);
		
		if (line == null) {
			return;
		} else if (line.hasOption("patrol")) {
			String[] storedTitles = extractStoredTitles();
			analyzeRecentChanges(storedTitles);
		} else if (line.hasOption("dump")) {
			String[] candidateTitles = readDumpFile(line.getOptionValue("dump"));
			analyzeRecentChanges(candidateTitles);
		} else {
			System.out.printf("No options specified: %s%n", Arrays.asList(args));
			return;
		}
		
		if (map.isEmpty()) {
			System.out.println("No entries found/extracted.");
			return;
		}
		
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int) Misc.deserialize(fHash) == map.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(map.hashCode(), fHash);
			
			String[] arr = map.values().stream()
				.flatMap(Collection::stream)
				.map(item -> item.pageTitle)
				.distinct()
				.toArray(String[]::new);
			
			Misc.serialize(arr, LOCATION + "stored_titles.ser");
			System.out.printf("%d titles stored.%n", arr.length);
		}
		
		com.github.wikibot.parsing.Page page = makePage();
		IOUtils.writeToFile(page.toString(), LOCATION + "page.txt");
		
		wb.setMarkBot(false);
		wb.edit(page.getTitle(), page.toString(), "aktualizacja");
	}
	
	private static CommandLine readOptions(String[] args) {
		Options options = new Options();
		options.addOption("p", "patrol", false, "patrol recent changes");
		options.addOption("d", "dump", true, "read from dump file");
		
		if (args.length == 0) {
			System.out.print("Option: ");
			String input = Misc.readLine();
			args = input.split(" ");
		}
		
		CommandLineParser parser = new DefaultParser();
		
		try {
			return parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			HelpFormatter help = new HelpFormatter();
			help.printHelp(InconsistentHeaderTitles.class.getName(), options);
			return null;
		}
	}

	private static void analyzeRecentChanges(String[] bufferedTitles)
			throws IOException {
		String[] newTitles = extractRecentChanges();
		
		String[] distinctTitles = Stream.of(newTitles, bufferedTitles)
			.flatMap(Stream::of)
			.distinct()
			.toArray(String[]::new);
		
		if (distinctTitles.length == 0) {
			return;
		}
		
		PageContainer[] pages = wb.getContentOfPages(distinctTitles);
		Stream.of(pages).parallel().forEach(InconsistentHeaderTitles::findErrors);
	}

	private static String[] readDumpFile(String path) throws FileNotFoundException, IOException {
		XMLDumpReader reader;
		
		if (path.equals("local")) {
			reader = new XMLDumpReader(Domains.PLWIKT);
		} else {
			reader = new XMLDumpReader(path);
		}
		
		int size = wb.getSiteStatistics().get("pages");
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(size).stream()) {
			return stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.map(Page::getAllSections)
				.flatMap(Collection::stream)
				.filter(InconsistentHeaderTitles::filterSections)
				.map(section -> section.getContainingPage().get().getTitle())
				.distinct()
				.toArray(String[]::new);
		}
	}
	
	private static String[] extractRecentChanges() throws IOException {
		OffsetDateTime start;
		
		try {
			String timestamp = IOUtils.loadFromFile(LOCATION + "timestamp.txt", "", "UTF8")[0];
			start = OffsetDateTime.parse(timestamp);
		} catch (Exception e) {
			System.out.println("Setting new timestamp reference (-24h).");
			start = OffsetDateTime.now(wb.timezone()).minusDays(1);
		}
		
		OffsetDateTime end = OffsetDateTime.now(wb.timezone());
		
		if (!end.isAfter(start)) {
			System.out.println("Extracted timestamp is greater than the current time, setting to -24h.");
			start = OffsetDateTime.now(wb.timezone()).minusDays(1);
		}
		
		final int rcTypes = Wikibot.RC_NEW | Wikibot.RC_EDIT;
		Wiki.Revision[] revs = wb.recentChanges(start, end, -1, rcTypes, false, null, Wiki.MAIN_NAMESPACE);
		Wiki.LogEntry[] logs = wb.getLogEntries(Wiki.MOVE_LOG, "move", null, null, end, start, Integer.MAX_VALUE, Wiki.ALL_NAMESPACES);
		
		// store current timestamp for the next iteration
		IOUtils.writeToFile(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), LOCATION + "timestamp.txt");
		
		return Stream.concat(
			Stream.of(revs).map(Wiki.Revision::getPage),
			Stream.of(logs).map(Wiki.LogEntry::getDetails).filter(targetTitle -> wb.namespace((String) targetTitle) == Wiki.MAIN_NAMESPACE)
		).distinct().toArray(String[]::new);
	}
	
	private static String[] extractStoredTitles() {
		String[] titles;
		
		try {
			titles = Misc.deserialize(LOCATION + "stored_titles.ser");
			System.out.printf("%d titles extracted.%n", titles.length);
		} catch (ClassNotFoundException | IOException e) {
			titles = new String[]{};
		}
		
		return titles;
	}
	
	private static void findErrors(PageContainer pc) {
		Page page;
		
		try {
			page = Page.wrap(pc);
		} catch (Exception e) {
			return;
		}
		
		page.getAllSections().stream()
			.filter(InconsistentHeaderTitles::filterSections)
			.forEach(section -> {
				String lang = section.getLangShort();
				String headerTitle = section.getHeaderTitle();
				Item item = new Item(pc.getTitle(), headerTitle);
				
				// http://stackoverflow.com/a/10743710
				Collection<Item> coll = map.get(lang);
				
				if (coll == null) {
					map.putIfAbsent(lang, new ConcurrentSkipListSet<>());
					coll = map.get(lang);
				}
				
				coll.add(item);
			});
	}
	
	private static boolean filterSections(Section section) {
		String headerTitle = section.getHeaderTitle();
		headerTitle = ParseUtils.removeCommentsAndNoWikiText(headerTitle);
		
		if (StringUtils.containsAny(headerTitle, '{', '}')) {
			headerTitle = stripHeaderTemplates(headerTitle);
		}
		
		if (StringUtils.containsAny(headerTitle, '[', ']')) {
			headerTitle = stripWikiLinks(headerTitle);
		}
		
		String pageTitle = section.getContainingPage().get().getTitle();
		pageTitle = pageTitle.replace("ʼ", "'").replace("…", "...");
		headerTitle = headerTitle.replace("ʼ", "'").replace("…", "...");
		
		return !pageTitle.equals(headerTitle);
	}
	
	private static String stripHeaderTemplates(String text) {
		for (String headerTemplate : HEADER_TEMPLATES) {
			text = Utils.replaceTemplates(text, headerTemplate, template ->
				Optional.of(ParseUtils.getTemplateParametersWithValue(template))
					.map(params -> params.get("ParamWithoutName1"))
					.filter(Objects::nonNull)
					.orElse(template)
			);
		}
		
		return text;
	}
	
	private static String stripWikiLinks(String text) {
		Matcher m = P_LINK.matcher(text);
		StringBuffer sb = new StringBuffer(text.length());
		
		while (m.find()) {
			String target = Optional.ofNullable(m.group(2)).orElse(m.group(1));
			m.appendReplacement(sb, target);
		}
		
		return m.appendTail(sb).toString();
	}
	
	private static com.github.wikibot.parsing.Page makePage() {
		List<String> values = map.values().stream()
			.flatMap(coll -> coll.stream().map(item -> item.pageTitle))
			.collect(Collectors.toList());
		
		int total = values.size();
		int unique = (int) values.stream().distinct().count();
		
		System.out.printf("Found: %d (%d unique)%n", total, unique);
		
		com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(TARGET_PAGE);
		
		page.setIntro(PAGE_INTRO + "\n" + String.format(
			"Znaleziono %s (%s) w %s. Aktualizacja: ~~~~~.",
			Misc.makePluralPL(total, "hasło", "hasła", "haseł"),
			Misc.makePluralPL(unique, "strona", "strony", "stron"),
			Misc.makePluralPL(map.keySet().size(), "języku", "językach", "językach")
		));
		
		com.github.wikibot.parsing.Section[] sections = map.entrySet().stream()
			.map(entry -> {
				boolean useColumns = entry.getValue().size() > COLUMN_ELEMENT_THRESHOLD;
				
				com.github.wikibot.parsing.Section section =
					com.github.wikibot.parsing.Section.create(entry.getKey(), 2);
				
				StringBuilder sb = new StringBuilder(entry.getValue().size() * 35);
				sb.append(String.format("{{język linków|%s}}", entry.getKey()));
				
				if (useColumns) {
					sb.append(String.format("{{columns|liczba=%d|", NUMBER_OF_COLUMNS));
				}
				
				sb.append("\n");
				
				entry.getValue().stream()
					.map(item -> item.buildEntry("#[[%s]]: %s", "#[[%s|%s]]: %s"))
					.forEach(formatted -> sb.append(formatted).append("\n"));
				
				if (useColumns) {
					sb.append("}}");
				}
				
				section.setIntro(sb.toString());
				return section;
			})
			.toArray(com.github.wikibot.parsing.Section[]::new);
		
		page.appendSections(sections);
		return page;
	}
	
	private static class Item implements Comparable<Item> {
		String pageTitle;
		String headerTitle;
		
		Item(String pageTitle, String headerTitle) {
			this.pageTitle = pageTitle;
			this.headerTitle = headerTitle;
		}
		
		String buildEntry(String simpleTargetFmt, String pipedTargetFmt) {
			String normalizedPageTitle = normalizeTitle(pageTitle);
			String normalizedHeaderTitle = normalizeTitle(headerTitle);
			
			if (normalizedPageTitle.equals(pageTitle)) {
				return String.format(simpleTargetFmt, normalizedPageTitle, normalizedHeaderTitle);
			} else {
				return String.format(pipedTargetFmt, pageTitle, normalizedPageTitle, normalizedHeaderTitle);
			}
		}
		
		private static String normalizeTitle(String title) {
			final String blankCharEntity = "&#9251;";
			title = title.replace("&#", "&amp;#");
			title = title.replace("<", "&lt;").replace(">", "&gt;");
			title = P_WHITESPACE.matcher(title).replaceAll(blankCharEntity);
			title = P_ENTITIES.matcher(title).replaceAll(blankCharEntity);
			return title;
		}
		
		@Override
		public int compareTo(Item item) {
			return pageTitle.compareTo(item.pageTitle);
		}
		
		@Override
		public int hashCode() {
			return pageTitle.hashCode() + headerTitle.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			
			if (!(o instanceof Item)) {
				return false;
			}
			
			Item i = (Item) o;
			return pageTitle.equals(i.pageTitle) && headerTitle.equals(i.headerTitle);
		}
		
		@Override
		public String toString() {
			return String.format("[%s, %s]", pageTitle, headerTitle);
		}
	}
}
