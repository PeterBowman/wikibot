package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Collator;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.ParsingException;
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
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	private static final int COLUMN_ELEMENT_THRESHOLD = 50;
	private static final int NUMBER_OF_COLUMNS = 3;
	
	private static PLWikt wb;
	
	static {
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Collator collator = Misc.getCollator("pl");
		Map<String, Collection<Item>> map = new ConcurrentSkipListMap<>(collator::compare);
		
		char op;
		
		if (args.length == 0) {
			System.out.println("'p' - patrol RC, 'd' - read from dump file");
			System.out.print("Option: ");
			op = (char) System.in.read();
		} else {
			op = args[0].toCharArray()[0];
		}
		
		switch (op) {
			case 'p': // patrol RC
				String[] newTitles = extractRecentChanges();
				String[] storedTitles = extractStoredTitles();
				
				if (newTitles.length == 0 && storedTitles.length == 0) {
					System.out.println("No entries found/extracted.");
					return;
				}
				
				String[] distinctTitles = Stream.of(newTitles, storedTitles)
					.flatMap(Stream::of)
					.distinct()
					.toArray(String[]::new);
				
				PageContainer[] pages = wb.getContentOfPages(distinctTitles, 100);
				Stream.of(pages).parallel().forEach(pc -> findErrors(map, pc));
				break;
			case 'd': // read from dump file, TODO: only local environment
				wb.readXmlDump(pc -> findErrors(map, pc));
				break;
			default:
				System.out.printf("Unrecognized argument: %c%n.", op);
				return;
		}
		
		File fHash = new File(LOCATION + "hash.ser");
		
		if (fHash.exists() && (int) Misc.deserialize(fHash) == map.hashCode()) {
			System.out.println("No changes detected, aborting.");
			return;
		} else {
			Misc.serialize(map.hashCode(), fHash);
			String text = map.values().stream()
				.flatMap(coll -> coll.stream().map(item -> item.page))
				.distinct()
				.collect(Collectors.joining("\n"));
			IOUtils.writeToFile(text, LOCATION + "stored_titles.txt");
		}
		
		com.github.wikibot.parsing.Page page = makePage(map);
		
		IOUtils.writeToFile(page.toString(), LOCATION + "page.txt");
		
		wb.setMarkBot(false);
		wb.edit(page.getTitle(), page.toString(), "aktualizacja");
	}
	
	private static String[] extractRecentChanges() throws IOException {
		Calendar startCal;
		
		try {
			String timestamp = IOUtils.loadFromFile(LOCATION + "timestamp.txt", "", "UTF8")[0];
			startCal = Calendar.getInstance();
			startCal.setTime(DATE_FORMAT.parse(timestamp));
		} catch (FileNotFoundException | ArrayIndexOutOfBoundsException | ParseException e) {
			System.out.println("Setting new timestamp reference (-24h).");
			startCal = wb.makeCalendar();
			startCal.add(Calendar.DATE, -1);
		}
		
		Calendar endCal = wb.makeCalendar();
		
		if (!endCal.after(startCal)) {
			System.out.println("Extracted timestamp is greater than the current time, setting to -24h.");
			startCal = wb.makeCalendar();
			startCal.add(Calendar.DATE, -1);
		}
		
		final int rcTypes = Wikibot.RC_NEW | Wikibot.RC_EDIT;
		Wiki.Revision[] revs = wb.recentChanges(startCal, endCal, -1, rcTypes, false, Wiki.MAIN_NAMESPACE);
		
		// store current timestamp for the next iteration
		IOUtils.writeToFile(DATE_FORMAT.format(endCal.getTime()), LOCATION + "timestamp.txt");
		
		return Stream.of(revs)
			.map(Wiki.Revision::getPage)
			.distinct()
			.toArray(String[]::new);
	}
	
	private static String[] extractStoredTitles() {
		String[] titles;
		
		try {
			titles = IOUtils.loadFromFile(LOCATION + "stored_titles.txt", "", "UTF8");
		} catch (FileNotFoundException e) {
			titles = new String[]{};
		}
		
		return titles;
	}
	
	private static void findErrors(Map<String, Collection<Item>> map, PageContainer pc) {
		Page page;
		
		try {
			page = Page.wrap(pc);
		} catch (ParsingException e) {
			return;
		}
		
		page.getAllSections().stream()
			.filter(InconsistentHeaderTitles::filterSections)
			.forEach(section -> {
				String lang = section.getLangShort();
				String headerTitle = section.getHeaderTitle().replace("&#", "&amp;#");
				Collection<Item> coll = map.getOrDefault(lang, new ConcurrentSkipListSet<>());
				coll.add(new Item(pc.getTitle(), headerTitle));
				map.putIfAbsent(lang, coll);
			});
	}
	
	private static boolean filterSections(Section section) {
		String headerTitle = section.getHeaderTitle();
		
		if (StringUtils.containsAny(headerTitle, '{', '}', '[', ']')) {
			return false;
		} else {
			String pageTitle = section.getContainingPage().getTitle();
			pageTitle = pageTitle.replace("ʼ", "'").replace("…", "...");
			headerTitle = headerTitle.replace("ʼ", "'").replace("…", "...");
			return !pageTitle.equals(headerTitle);
		}
	}
	
	private static com.github.wikibot.parsing.Page makePage(Map<String, Collection<Item>> map) {
		List<String> values = map.values().stream()
			.flatMap(coll -> coll.stream().map(item -> item.page))
			.collect(Collectors.toList());
		
		int total = values.size();
		int unique = (int) values.stream().distinct().count();
		
		System.out.printf("Found: %d (%d unique)%n", total, unique);
		
		com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(TARGET_PAGE);
		
		page.setIntro(String.format(
			"Znaleziono %s (%s) w %s. Aktualizacja: ~~~~~.{{TOCright}}",
			Misc.makePluralPL(total, "hasło", "hasła", "haseł"),
			Misc.makePluralPL(unique, "strona", "strony", "stron"),
			Misc.makePluralPL(map.keySet().size(), "języku", "językach", "językach")
		));
		
		com.github.wikibot.parsing.Section[] sections = map.entrySet().stream()
			.map(entry -> {
				boolean useColumns = entry.getValue().size() > COLUMN_ELEMENT_THRESHOLD;
				String header = String.format("%s (%d)", entry.getKey(), entry.getValue().size());
				
				com.github.wikibot.parsing.Section section =
					com.github.wikibot.parsing.Section.create(header, 2);
				
				StringBuilder sb = new StringBuilder(entry.getValue().size() * 35);
				sb.append(String.format("{{język linków|%s}}", entry.getKey()));
				
				if (useColumns) {
					sb.append(String.format("{{columns|liczba=%d|", NUMBER_OF_COLUMNS));
				}
				
				sb.append("\n").append(entry.getValue().stream()
					.map(item -> String.format("#[[%s]]: %s", item.page, item.headerTitle))
					.collect(Collectors.joining("\n"))
				).append("\n");
				
				if (useColumns) {
					sb.append("}}");
				}
				
				sb.append("{{język linków}}");
				section.setIntro(sb.toString());
				
				return section;
			})
			.toArray(com.github.wikibot.parsing.Section[]::new);
		
		page.appendSections(sections);
		return page;
	}
	
	private static class Item implements Comparable<Item> {
		String page;
		String headerTitle;
		
		Item(String page, String headerTitle) {
			this.page = page;
			this.headerTitle = headerTitle;
		}
		
		@Override
		public int compareTo(Item item) {
			return page.compareTo(item.page);
		}
		
		@Override
		public int hashCode() {
			return page.hashCode() + headerTitle.hashCode();
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Item)) {
				return true;
			}
			
			if (o == this) {
				return true;
			}
			
			Item i = (Item) o;
			return page.equals(i.page) && headerTitle.equals(i.headerTitle);
		}
		
		@Override
		public String toString() {
			return String.format("[%s, %s]", page, headerTitle);
		}
	}
}
