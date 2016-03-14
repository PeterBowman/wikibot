package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikipedia.Wiki;
import org.wikiutils.IOUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class CitationTypography {
	private static final String LOCATION = "./data/tasks.plwikt/CitationTypography/";
	
	private static final Pattern P_REFERENCE;
	private static final Pattern P_OCCURENCE;
	private static final Pattern P_LINE;
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	private static final List<FieldTypes> ALLOWED_NON_POLISH_FIELDS = Arrays.asList(
		FieldTypes.EXAMPLES, FieldTypes.ETYMOLOGY, FieldTypes.NOTES
	);
	
	private static PLWikt wb;
	
	static {
		P_REFERENCE = Pattern.compile("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", Pattern.CASE_INSENSITIVE);
		P_OCCURENCE = Pattern.compile("\\. *('{2})?((?i: *" + P_REFERENCE.pattern() + ")+)");
		P_LINE = Pattern.compile("^(.*)" + P_OCCURENCE.pattern() + "(.*)$", Pattern.MULTILINE);
		
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		CommandLine line = readOptions(args);
		Set<String> set = new HashSet<>(0);
		
		if (line.hasOption("patrol") || line.hasOption("dump")) {
			String[] rcTitles = extractRecentChanges();
			set.addAll(Arrays.asList(rcTitles));
		}
		
		if (line.hasOption("dump")) {
			String[] candidateTitles = readDumpFile(line.getOptionValue("dump"));
			set.addAll(Arrays.asList(candidateTitles));
		}
		
		if (!set.isEmpty()) {
			String[] combinedTitles = set.toArray(new String[set.size()]);
			PageContainer[] pages = wb.getContentOfPages(combinedTitles, 400);
			
			List<Item> list = Stream.of(pages).parallel()
				.flatMap(CitationTypography::mapOccurrences)
				.collect(Collectors.toList());
			
			if (!list.isEmpty()) {
				Map<String, String> map = list.stream()
					.collect(Collectors.toMap(
						item -> item.title,
						item -> String.format("%s%n%n%s", item.originalText, item.newText),
						(i1, i2) -> i1,
						TreeMap::new
					));
				
				System.out.println(map.size());
				IOUtils.writeToFile(Misc.makeList(map), "./data/test8.txt");
			}
		}
		
		if (line.hasOption("edit")) {
			
		}
	}
	
	private static CommandLine readOptions(String[] args) throws ParseException {
		Options options = new Options();
		
		options.addOption("p", "patrol", false, "patrol recent changes");
		options.addOption("d", "dump", true, "read from dump file");
		options.addOption("u", "update", false, "update database");
		options.addOption("e", "edit", false, "edit verified entries");
		
		if (args.length == 0) {
			System.out.print("Option(s): ");
			String input = Misc.readLine();
			args = input.split(" ");
		}
		
		try {
			return new DefaultParser().parse(options, args);
		} catch (ParseException e) {
			new HelpFormatter().printHelp(CitationTypography.class.getName(), options);
			throw e;
		}
	}
	
	private static String[] extractRecentChanges() throws IOException {
		Calendar startCal;
		
		try {
			String timestamp = IOUtils.loadFromFile(LOCATION + "timestamp.txt", "", "UTF8")[0];
			startCal = Calendar.getInstance();
			startCal.setTime(DATE_FORMAT.parse(timestamp));
		} catch (Exception e) {
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
		Wiki.LogEntry[] logs = wb.getLogEntries(endCal, startCal, Integer.MAX_VALUE, Wiki.MOVE_LOG,
			"move", null, "", Wiki.ALL_NAMESPACES);
		
		// store current timestamp for the next iteration
		IOUtils.writeToFile(DATE_FORMAT.format(endCal.getTime()), LOCATION + "timestamp.txt");
		
		return Stream.concat(
			Stream.of(revs).map(Wiki.Revision::getPage),
			Stream.of(logs).map(Wiki.LogEntry::getDetails).filter(targetTitle -> {
				try {
					return wb.namespace((String) targetTitle) == Wiki.MAIN_NAMESPACE;
				} catch (Exception e) {
					return false;
				}
			})
		).distinct().toArray(String[]::new);
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
				.filter(xml -> P_OCCURENCE.matcher(xml.getText()).find())
				.map(XMLRevision::getTitle)
				.toArray(String[]::new);
		}
	}
	
	private static Stream<Item> mapOccurrences(PageContainer pc) {
		return Page.wrap(pc).getAllSections().stream()
			.map(Section::getAllFields)
			.flatMap(Collection::stream)
			.filter(CitationTypography::filterAllowedFields)
			.filter(field -> !field.isEmpty())
			.flatMap(CitationTypography::extractItems);
	}
	
	private static boolean filterAllowedFields(Field field) {
		Section section = field.getContainingSection().get();
		String lang = section.getLang();
		
		if (lang.equals("język polski") || lang.equals("termin obcy w języku polskim")) {
			return true;
		} else {
			return ALLOWED_NON_POLISH_FIELDS.contains(field.getFieldType());
		}
	}
	
	private static Stream<Item> extractItems(Field field) {
		String langSection = field.getContainingSection().get().getLang();
		String content = field.getContent();
		
		boolean isForeignExample =
			field.getFieldType() == FieldTypes.EXAMPLES &&
			!langSection.equals("język polski") &&
			!langSection.equals("termin obcy w języku polskim") &&
			content.contains("→");
		
		Matcher mLine = P_LINE.matcher(content);
		List<Item> items = new ArrayList<>();
		
		while (mLine.find()) {
			String line = mLine.group();
			Matcher mOccurence = P_OCCURENCE.matcher(line);
			StringBuffer sb = new StringBuffer(line.length());
			
			while (mOccurence.find()) {
				if (isForeignExample && mOccurence.end() < line.indexOf("→")) {
					continue;
				}
				
				String replacement = buildReplacementString(mOccurence);
				mOccurence.appendReplacement(sb, Matcher.quoteReplacement(replacement));
			}
			
			String modified = mOccurence.appendTail(sb).toString();
			
			if (!modified.equals(line)) {
				Item item = Item.constructNewItem(field, line, modified);
				items.add(item);
			}
		}
		
		return !items.isEmpty() ? items.stream() : Stream.empty();
	}
	
	private static String buildReplacementString(Matcher mOccurence) {
		String apostrophes = mOccurence.group(1);
		String references = mOccurence.group(2);
		
		Matcher mReferences = P_REFERENCE.matcher(references);
		StringBuilder sb = new StringBuilder(references.length());
		
		while (mReferences.find()) {
			sb.append(mReferences.group());
		}
		
		return Optional.ofNullable(apostrophes).orElse("") + sb.toString() + ".";
	}
	
	private static class Item implements Serializable, Comparable<Item> {
		private static final long serialVersionUID = 4565508346026187762L;
		
		String title;
		String langSection;
		FieldTypes fieldType;
		String originalText;
		String newText;

		Item(String title, String langSection, FieldTypes fieldType, String originalText, String newText) {
			this.title = title;
			this.langSection = langSection;
			this.fieldType = fieldType;
			this.originalText = originalText;
			this.newText = newText;
		}
		
		static Item constructNewItem(Field field, String originalText, String newText) {
			Section section = field.getContainingSection().get();
			String pageTitle = section.getContainingPage().get().getTitle();
			return new Item(pageTitle, section.getLang(), field.getFieldType(), originalText, newText);
		}
		
		@Override
		public String toString() {
			return String.format("%s, %s, %s:%n%s%n%s", title, langSection, fieldType, originalText, newText);
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
			
			return
				title.equals(i.title) && langSection.equals(i.langSection) &&
				fieldType == i.fieldType&& originalText.equals(i.originalText);
		}
		
		@Override
		public int hashCode() {
			return
				title.hashCode() + langSection.hashCode() + fieldType.hashCode() +
				originalText.hashCode();
		}

		@Override
		public int compareTo(Item i) {
			if (!title.equals(i.title)) {
				return title.compareTo(i.title);
			}
			
			if (!langSection.equals(i.langSection)) {
				return langSection.compareTo(langSection);
			}
			
			if (fieldType != i.fieldType) {
				return fieldType.compareTo(i.fieldType);
			}
			
			if (!originalText.equals(i.originalText)) {
				return originalText.compareTo(i.originalText);
			}
			
			return 0;
		}
	}
}
