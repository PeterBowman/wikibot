package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
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
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
	
	private static final List<FieldTypes> ALLOWED_NON_POLISH_FIELDS = Arrays.asList(
		FieldTypes.EXAMPLES, FieldTypes.ETYMOLOGY, FieldTypes.NOTES
	);
	
	private static final String SQL_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_verify_citations";
	private static final Properties defaultSQLProperties = new Properties();
	
	private static PLWikt wb;
	
	static {
		P_REFERENCE = Pattern.compile("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", Pattern.CASE_INSENSITIVE);
		P_OCCURENCE = Pattern.compile("\\. *('{2})?((?i: *" + P_REFERENCE.pattern() + ")+)");
		P_LINE = Pattern.compile("^(.*)" + P_OCCURENCE.pattern() + "(.*)$", Pattern.MULTILINE);
		
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
		
		defaultSQLProperties.setProperty("autoReconnect", "true");
		defaultSQLProperties.setProperty("useUnicode", "yes");
		defaultSQLProperties.setProperty("characterEncoding", "UTF-8");
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Class.forName("com.mysql.jdbc.Driver");
		
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
		
		List<Item> items;
		
		if (!set.isEmpty()) {
			String[] combinedTitles = set.toArray(new String[set.size()]);
			PageContainer[] pages = wb.getContentOfPages(combinedTitles, 400);
			
			items = Stream.of(pages).parallel()
				.flatMap(CitationTypography::mapOccurrences)
				.collect(Collectors.toList());
			
			if (!items.isEmpty()) {
				System.out.printf("%d items extracted.%n", items.size());
				
				mapPageIds(items);
				storeResult(items);
				
				if (!items.isEmpty() && line.hasOption("update")) {
					updateSQLTables(items);
				}
			} else {
				System.out.println("No items extracted.");
			}
		} else {
			System.out.println("No titles extracted.");
			items = new ArrayList<>();
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
	
	@SuppressWarnings("unchecked")
	private static void mapPageIds(List<Item> items) throws IOException {
		String[] titles = items.stream().map(item -> item.title).toArray(String[]::new);
		Map<String, Object>[] infos = wb.getPageInfo(titles);
		Map<String, Integer> titleToPageId = new HashMap<>(infos.length);
		
		for (int i = 0; i < infos.length; i++) {
			Map<String, Object> info = infos[i];
			String title = titles[i];
			long pageId = (long) info.getOrDefault("pageid", -1);
			
			if (pageId == -1) {
				continue;
			}
			
			titleToPageId.putIfAbsent(title, Math.toIntExact(pageId));
		}
		
		items.removeIf(item -> !titleToPageId.containsKey(item.title));
		items.forEach(item -> item.pageId = titleToPageId.get(item.title));
		
		System.out.printf("%d items after pageid mapping.%n", items.size());
	}
	
	private static void storeResult(List<Item> items) throws FileNotFoundException, IOException {
		Misc.serialize(items, LOCATION + "list.ser");
		
		Map<String, String> map = items.stream()
			.collect(Collectors.toMap(
				item -> item.title,
				item -> String.format("%s%n%n%s", item.originalText, item.newText),
				(i1, i2) -> i1,
				TreeMap::new
			));
		
		IOUtils.writeToFile(Misc.makeList(map), LOCATION + "diffs.txt");
	}
	
	private static Properties prepareSQLProperties() throws IOException {
		Properties properties = new Properties(defaultSQLProperties);
		Pattern patt = Pattern.compile("(.+)='(.+)'");
		
		Files.lines(Paths.get("./replica.my.cnf"))
			.map(patt::matcher)
			.filter(Matcher::matches)
			.forEach(m -> properties.setProperty(m.group(1), m.group(2)));
		
		return properties;
	}
	
	private static void updateSQLTables(List<Item> items) throws IOException, ClassNotFoundException {
		Properties properties = prepareSQLProperties();
		
		try (Connection conn = DriverManager.getConnection(SQL_URI, properties)) {
			updatePageTitleTable(conn, items);
			analyzeStoredEntries(conn, items);
			
			List<Item> candidateItems = items.stream()
				.filter(item -> item.verified == null)
				.collect(Collectors.toList());
			
			System.out.printf("%d candidate items to be stored in DB.%n", candidateItems.size());
			
			if (!candidateItems.isEmpty()) {
				storeNewItems(conn, candidateItems);
			}
			
			items.removeAll(candidateItems);
			items.removeIf(item -> !item.verified.booleanValue());
			
			System.out.printf("%d stored and verified items left for edit stage.%n", items.size());
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}
	
	private static void updatePageTitleTable(Connection conn, List<Item> items) throws SQLException {
		String values = items.stream()
			.map(item -> String.format("(%d, '%s')", item.pageId, item.title.replace("'", "\\'")))
			.distinct()
			.collect(Collectors.joining(", "));
		
		String query = "INSERT INTO page_title (page_id, page_title)"
			+ " VALUES " + values
			+ " ON DUPLICATE KEY"
			+ " UPDATE page_title = VALUES(page_title);";
		
		Statement stmt = conn.createStatement();
		int updatedRows = stmt.executeUpdate(query);
		System.out.printf("%d rows updated.%n", updatedRows);
	}
	
	private static void analyzeStoredEntries(Connection conn, List<Item> items) throws SQLException {
		String pageIdList = items.stream()
			.map(item -> item.pageId)
			.map(Object::toString)
			.distinct()
			.collect(Collectors.joining(", "));
		
		String query = "SELECT entry.entry_id, entry.page_id, page_title.page_title, entry.language,"
				+ " field.field_name, entry.current_line_id, line.line_id, line.text, entry.review_status"
			+ " FROM entry"
			+ " INNER JOIN line"
			+ " ON line.line_id = entry.first_line_id"
			+ " OR line.line_id = entry.current_line_id"
			+ " INNER JOIN page_title"
			+ " ON page_title.page_id = entry.page_id"
			+ " INNER JOIN field"
			+ " ON field.field_id = entry.field_id"
			+ " WHERE entry.page_id IN (" + pageIdList + ");";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		Map<Integer, Item> storedItems = new HashMap<>(items.size());
		
		while (rs.next()) {
			int entryId = rs.getInt("entry_id");
			int currentLineId = rs.getInt("current_line_id");
			int lineId = rs.getInt("line_id");
			
			String firstLineText = null;
			String currentLineText = null;
			
			if (lineId == currentLineId) {
				currentLineText = rs.getString("text");
			} else {
				firstLineText = rs.getString("text");
			}
			
			if (storedItems.containsKey(entryId)) {
				Item item = storedItems.get(entryId);
				
				if (item.originalText.isEmpty() && firstLineText != null) {
					item.originalText = firstLineText;
				}
				
				if (item.newText.isEmpty() && currentLineText != null) {
					item.newText = currentLineText;
				}
				
				continue;
			}
			
			int pageId = rs.getInt("page_id");
			String title = rs.getString("page_title");
			String language = rs.getString("language");
			String field = rs.getString("field_name");
			Boolean verified = rs.getBoolean("review_status");
			
			if (rs.wasNull()) {
				verified = null;
			}
			
			FieldTypes fieldType;
			
			try {
				fieldType = FieldTypes.valueOf(field);
			} catch (IllegalArgumentException e) {
				continue;
			}
			
			Item item = new Item(title, language, fieldType, firstLineText, currentLineText);
			
			item.verified = verified;
			item.pageId = pageId;
			
			storedItems.put(entryId, item);
		}
		
		Map<Item, Boolean> verifiedItems = storedItems.values().stream()
			.filter(i -> i.originalText.isEmpty() || i.newText.isEmpty())
			.collect(Collectors.toMap(i -> i, i -> i.verified, (i1, i2) -> i1));
		
		items.stream()
			.filter(verifiedItems::containsKey)
			.forEach(i -> i.verified = verifiedItems.get(i));
	}
	
	private static void storeNewItems(Connection conn, List<Item> items) throws SQLException {
		String preparedLineQuery = "INSERT INTO line (text, user, timestamp)"
			+ " VALUES (?, ?, '" + generateTimestamp() + "')";
		
		String preparedEntryQuery = "INSERT INTO entry"
			+ " (page_id, language, field_id, first_line_id, current_line_id)"
			+ " VALUES (?, ?, ?, ?, ?);";
		
		String preparedPendingQuery = "INSERT INTO pending (entry_id) VALUES (?);";
		
		int opt = Statement.RETURN_GENERATED_KEYS;
		PreparedStatement insertLine = conn.prepareStatement(preparedLineQuery, opt);
		PreparedStatement insertEntry = conn.prepareStatement(preparedEntryQuery, opt);
		PreparedStatement insertPending = conn.prepareStatement(preparedPendingQuery, opt);
		
		conn.setAutoCommit(false);
		
		for (Item item : items) {
			int firstLineId;
			int currentLineId;
			int entryId;
			
			ResultSet rs;
			
			insertLine.setString(1, item.originalText);
			insertLine.setString(2, "NULL");
			insertLine.executeUpdate();
			
			rs = insertLine.getGeneratedKeys();
			
			if (rs.next()) {
				firstLineId = rs.getInt(1); 
			} else {
				continue;
			}
			
			insertLine.setString(1, item.newText);
			insertLine.setString(2, "PBbot");
			insertLine.executeUpdate();
			
			rs = insertLine.getGeneratedKeys();
			
			if (rs.next()) {
				currentLineId = rs.getInt(1);
			} else {
				continue;
			}
			
			insertEntry.setInt(1, item.pageId);
			insertEntry.setString(2, item.langSection);
			insertEntry.setInt(3, item.fieldType.ordinal() + 1);
			insertEntry.setInt(4, firstLineId);
			insertEntry.setInt(5, currentLineId);
			insertEntry.executeUpdate();
			
			rs = insertEntry.getGeneratedKeys();
			
			if (rs.next()) {
				entryId = rs.getInt(1);
			} else {
				continue;
			}
			
			insertPending.setInt(1, entryId);
			insertPending.executeUpdate();
			
			conn.commit();
		}
		
		conn.setAutoCommit(true);
	}
	
	private static String generateTimestamp() {
		Calendar cal = Calendar.getInstance();
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
		return DATE_FORMAT.format(cal.getTime());
	}
	
	private static class Item implements Serializable, Comparable<Item> {
		private static final long serialVersionUID = 4565508346026187762L;
		
		int pageId;
		String title;
		String langSection;
		FieldTypes fieldType;
		String originalText;
		String newText;
		Boolean verified;
		
		Item(String title, String langSection, FieldTypes fieldType, String originalText, String newText) {
			this.title = title;
			this.langSection = langSection;
			this.fieldType = fieldType;
			this.originalText = Optional.ofNullable(originalText).orElse("");
			this.newText = Optional.ofNullable(newText).orElse("");
			
			this.pageId = 0;
			this.verified = null;
		}
		
		static Item constructNewItem(Field field, String originalText, String newText) {
			Section section = field.getContainingSection().get();
			String pageTitle = section.getContainingPage().get().getTitle();
			return new Item(pageTitle, section.getLang(), field.getFieldType(), originalText, newText);
		}
		
		@Override
		public String toString() {
			return String.format("[%d, %s, %s, %s]:%n%s%n%s%n",
				pageId, title, langSection, fieldType, originalText, newText);
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
				pageId == i.pageId && langSection.equals(i.langSection) &&
				fieldType == i.fieldType && originalText.equals(i.originalText) &&
				newText.equals(i.newText);
		}
		
		@Override
		public int hashCode() {
			return
				pageId + langSection.hashCode() + fieldType.hashCode() +
				originalText.hashCode() + newText.hashCode();
		}

		@Override
		public int compareTo(Item i) {
			if (pageId != i.pageId) {
				return Integer.compare(pageId, i.pageId);
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
