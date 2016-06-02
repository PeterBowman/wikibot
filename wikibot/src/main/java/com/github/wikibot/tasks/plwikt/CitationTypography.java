package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.LoginException;

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
	
	private static final List<FieldTypes> ALLOWED_NON_POLISH_FIELDS = Arrays.asList(
		FieldTypes.EXAMPLES, FieldTypes.ETYMOLOGY, FieldTypes.NOTES
	);
	
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
	private static final int HOURS_GAP = 8;
	
	private static final String SQL_PLWIKT_URI = "jdbc:mysql://plwiktionary.labsdb:3306/plwiktionary_p";
	private static final String SQL_VC_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_verify_citations";
	private static final String SQL_COMMON_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_common";
	private static final Properties defaultSQLProperties = new Properties();
	
	private static final String EDIT_SUMMARY = "[[WS:Głosowania/Pozycja odsyłacza przypisu względem kropki]]";
	private static final int EDIT_THROTTLE_MS = 5000;
	
	private static PLWikt wb;
	
	static {
		P_REFERENCE = Pattern.compile("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", Pattern.CASE_INSENSITIVE);
		P_OCCURENCE = Pattern.compile("\\. *('{2})?((?i: *" + P_REFERENCE.pattern() + ")+)");
		P_LINE = Pattern.compile("^(.*)" + P_OCCURENCE.pattern() + "(.*)$", Pattern.MULTILINE);
		
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
		
		defaultSQLProperties.setProperty("autoReconnect", "true");
		defaultSQLProperties.setProperty("useUnicode", "yes");
		defaultSQLProperties.setProperty("characterEncoding", "UTF-8");
		
		// Don't use this, it either breaks the encoding or throws MysqlDataTruncation.
		// defaultSQLProperties.setProperty("character_set_server", "utf8mb4");
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		Class.forName("com.mysql.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		
		CommandLine line = readOptions(args);
		Set<String> titles = new HashSet<>(0);
		
		if (line.hasOption("patrol") || line.hasOption("dump")) {
			String[] rcTitles = extractRecentChanges();
			titles.addAll(Arrays.asList(rcTitles));
		}
		
		if (line.hasOption("dump")) {
			String[] candidateTitles = readDumpFile(line.getOptionValue("dump"));
			titles.addAll(Arrays.asList(candidateTitles));
		}
		
		List<Entry> entries;
		Map<String, String> contentCache;
		Map<String, Integer> titleToPageId = new HashMap<>(titles.size() * 2);
		
		if (!titles.isEmpty()) {
			String[] combinedTitles = titles.toArray(new String[titles.size()]);
			PageContainer[] pages = wb.getContentOfPages(combinedTitles, 400);
			
			entries = Stream.of(pages).parallel()
				.flatMap(CitationTypography::mapOccurrences)
				.collect(Collectors.toList());
			
			contentCache = Stream.of(pages)
				.collect(Collectors.toMap(PageContainer::getTitle, PageContainer::getText));
			
			System.out.printf("%d entries extracted.%n", entries.size());
			
			if (!entries.isEmpty()) {
				String[] arr = entries.stream().map(entry -> entry.title).distinct().toArray(String[]::new);
				
				try (Connection plwiktConn = DriverManager.getConnection(SQL_PLWIKT_URI, properties)) {
					queryPageTable(plwiktConn, arr, titleToPageId);
				} catch (SQLException e) {
					queryPageIdsFallback(arr, titleToPageId, line.hasOption("debug"));
				}
				
				entries.removeIf(entry -> !titleToPageId.containsKey(entry.title));
				System.out.printf("%d entries after pageid mapping.%n", entries.size());
				serializeResults(entries, titleToPageId);
			}
		} else {
			System.out.println("No titles extracted.");
			entries = new ArrayList<>(0);
			contentCache = new HashMap<>(titles.size());
		}
		
		if (line.hasOption("edit") || line.hasOption("update")) {
			try (
				Connection vcConn = DriverManager.getConnection(SQL_VC_URI, properties);
				Connection commonConn = DriverManager.getConnection(SQL_COMMON_URI, properties);
			) {
				vcConn.createStatement().executeQuery("SET NAMES utf8mb4;"); // important
				Map<Integer, Entry> entryMap = new LinkedHashMap<>(5000);
				
				if (line.hasOption("update")) {
					if (!entries.isEmpty()) {
						updatePageTitleTable(vcConn, entries, titleToPageId);
						updateEntryAndPendingTables(vcConn, entries, entryMap);
					}
					
					List<Entry> pendingEntries = queryPendingEntries(vcConn);
					
					if (!pendingEntries.isEmpty()) {
						List<Entry> worklist = reviewPendingEntries(vcConn, pendingEntries,
							contentCache, line.hasOption("dump"));
						
						if (!worklist.isEmpty()) {
							deleteObsoletePendingEntries(vcConn, worklist);
						}
					}
					
					updateTimestampTable(commonConn, "tasks.plwikt.CitationTypography.update");
				}
				
				if (line.hasOption("edit")) {
					wb.setThrottle(0);
					wb.setMarkMinor(true);
					
					processPendingEntries(vcConn, entryMap);
					updateTimestampTable(commonConn, "tasks.plwikt.CitationTypography.edit");
				}
			}
		}
	}
	
	private static CommandLine readOptions(String[] args) throws ParseException {
		Options options = new Options();
		
		options.addOption("p", "patrol", false, "patrol recent changes");
		options.addOption("d", "dump", true, "read from dump file");
		options.addOption("u", "update", false, "update database");
		options.addOption("e", "edit", false, "edit verified entries");
		options.addOption("g", "debug", false, "debug mode");
		
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
	
	private static Stream<Entry> mapOccurrences(PageContainer pc) {
		return Page.wrap(pc).getAllSections().stream()
			.map(Section::getAllFields)
			.flatMap(Collection::stream)
			.filter(CitationTypography::filterAllowedFields)
			.filter(field -> !field.isEmpty())
			.flatMap(CitationTypography::extractEntriesFromField);
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
	
	private static Stream<Entry> extractEntriesFromField(Field field) {
		boolean isForeignExample = isForeignExampleField(field);
		Matcher mLine = P_LINE.matcher(field.getContent());
		List<Entry> entries = new ArrayList<>();
		
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
				Entry entry = Entry.constructNewEntry(field, line, modified);
				entries.add(entry);
			}
		}
		
		return !entries.isEmpty() ? entries.stream() : Stream.empty();
	}
	
	private static boolean isForeignExampleField(Field field) {
		String langSection = field.getContainingSection().get().getLang();
		String content = field.getContent();
		
		return
			field.getFieldType() == FieldTypes.EXAMPLES &&
			!langSection.equals("język polski") &&
			!langSection.equals("termin obcy w języku polskim") &&
			content.contains("→");
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
	
	private static void queryPageTable(Connection conn, String[] titles, Map<String, Integer> titleToPageId)
			throws SQLException {
		String values = Stream.of(titles)
			.map(title -> String.format("'%s'", title.replace("'", "\\'").replace(" ", "_")))
			.collect(Collectors.joining(", "));
		
		String query = "SELECT CONVERT(page_title USING utf8mb4) AS page_title, page_id"
			+ " FROM page"
			+ " WHERE page_namespace = 0"
			+ " AND page_title IN (" + values + ");";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		while (rs.next()) {
			String title = rs.getString("page_title").replace("_", " ");
			int pageId = rs.getInt("page_id");
			titleToPageId.put(title, pageId);
		}
	}
	
	private static void queryPageIdsFallback(String[] titles, Map<String, Integer> titleToPageId, boolean dbg)
			throws IOException {
		if (dbg) {
			try {
				Map<String, Integer> stored = Misc.deserialize(LOCATION + "title_to_page_id.ser");
				titleToPageId.putAll(stored);
				
				titles = Stream.of(titles)
					.filter(title -> !stored.containsKey(title))
					.toArray(String[]::new);
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
			}
			
			if (titles.length == 0) {
				return;
			}
		}
		
		@SuppressWarnings("unchecked")
		Map<String, Object>[] infos = wb.getPageInfo(titles);
		
		for (int i = 0; i < infos.length; i++) {
			Map<String, Object> info = infos[i];
			String title = titles[i];
			long pageId = (long) info.getOrDefault("pageid", -1);
			
			if (pageId == -1) {
				continue;
			}
			
			titleToPageId.putIfAbsent(title, Math.toIntExact(pageId));
		}
	}
	
	private static void serializeResults(List<Entry> entries, Map<String, Integer> titleToPageId)
			throws FileNotFoundException, IOException {
		Misc.serialize(entries, LOCATION + "entries.ser");
		Misc.serialize(titleToPageId, LOCATION + "title_to_page_id.ser");
		
		Map<String, String> map = entries.stream()
			.collect(Collectors.toMap(
				entry -> entry.title,
				entry -> String.format("%s%n%n%s", entry.originalText, entry.newText),
				(e1, e2) -> e1,
				TreeMap::new
			));
		
		IOUtils.writeToFile(Misc.makeList(map), LOCATION + "diffs.txt");
	}
	
	private static Properties prepareSQLProperties() throws IOException {
		Properties properties = new Properties(defaultSQLProperties);
		Pattern patt = Pattern.compile("(.+)='(.+)'");
		File f = new File("./replica.my.cnf");
		
		if (!f.exists()) {
			f = new File(LOCATION + ".my.cnf");
		}
		
		Files.lines(f.toPath())
			.map(patt::matcher)
			.filter(Matcher::matches)
			.forEach(m -> properties.setProperty(m.group(1), m.group(2)));
		
		return properties;
	}
	
	private static void updatePageTitleTable(Connection conn, List<Entry> entries, Map<String, Integer> titleToPageId)
			throws SQLException {
		String values = entries.stream()
			.map(entry -> String.format("(%d, '%s')", titleToPageId.get(entry.title), entry.title.replace("'", "\\'")))
			.distinct()
			.collect(Collectors.joining(", "));
		
		String query = "INSERT INTO page_title (page_id, page_title)"
			+ " VALUES " + values
			+ " ON DUPLICATE KEY"
			+ " UPDATE page_title = VALUES(page_title);";
		
		Statement stmt = conn.createStatement();
		int updatedRows = stmt.executeUpdate(query);
		System.out.printf("%d rows inserted or updated in 'page_title' table.%n", updatedRows);
	}
	
	private static void updateEntryAndPendingTables(Connection conn, List<Entry> entries,
			Map<Integer, Entry> storedEntries) throws SQLException {
		Set<Entry> verifiedNonPendingEntries = new HashSet<>(entries.size());
		Set<Entry> nonReviewedNonPendingEntries = new HashSet<>(entries.size());
		
		analyzeStoredEntries(conn, entries, storedEntries, verifiedNonPendingEntries, nonReviewedNonPendingEntries);
		
		System.out.printf("%d entries already stored in DB.%n", storedEntries.size());
		System.out.printf("%d marked as verified and eligible for edit.%n", verifiedNonPendingEntries.size());
		System.out.printf("%d not reviewed and not pending entries.%n", nonReviewedNonPendingEntries.size());
		
		List<Entry> newEntries = new ArrayList<>(entries);
		newEntries.removeAll(new HashSet<>(storedEntries.values())); // remove stored entries
		
		if (!newEntries.isEmpty()) {
			storeNewEntries(conn, newEntries);
		}
		
		if (!nonReviewedNonPendingEntries.isEmpty()) {
			List<Integer> list = storedEntries.entrySet().stream()
				.filter(entry -> nonReviewedNonPendingEntries.contains(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toList());
			
			populatePendingTable(conn, list);
		}
		
		storedEntries.values().retainAll(verifiedNonPendingEntries);
	}
	
	private static void analyzeStoredEntries(Connection conn, List<Entry> entries, Map<Integer, Entry> entryMap,
			Set<Entry> verifiedNonPendingEntries, Set<Entry> nonReviewedNonPendingEntries)
			throws SQLException {
		String titles = entries.stream()
			.map(entry -> String.format("'%s'", entry.title.replace("'", "\\'")))
			.collect(Collectors.joining(", "));
		
		String query = "SELECT entry_id, page_title, language, field_id, source_text, edited_text,"
				+ " review_status, is_pending"
			+ " FROM all_entries"
			+ " WHERE page_title IN (" + titles + ");";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		Set<Entry> set = new HashSet<>(entries);
		
		while (rs.next()) {
			Entry entry = processEntryResultSet(rs, entryMap);
			
			Boolean verified = rs.getBoolean("review_status");
			verified = rs.wasNull() ? null : verified;
			
			if (!rs.getBoolean("is_pending") && set.contains(entry)) {
				if (verified != null && verified.booleanValue()) {
					verifiedNonPendingEntries.add(entry);
				} else if (verified == null) {
					nonReviewedNonPendingEntries.add(entry);
				}
			}
		}
		
		entryMap.values().retainAll(set);
	}
	
	private static Entry processEntryResultSet(ResultSet rs, Map<Integer, Entry> entryMap) throws SQLException {
		int entryId = rs.getInt("entry_id");
		
		String title = rs.getString("page_title");
		String language = rs.getString("language");
		
		int fieldId = rs.getInt("field_id");
		
		FieldTypes fieldType = FieldTypes.values()[fieldId - 1];
		
		String sourceLineText = rs.getString("source_text");
		String editedLineText = rs.getString("edited_text");
		
		Entry entry = new Entry(title, language, fieldType, sourceLineText, editedLineText);
		
		entryMap.put(entryId, entry);
		
		return entry;
	}
	
	private static void storeNewEntries(Connection conn, List<Entry> entries) throws SQLException {
		String preparedSourceLineQuery = "INSERT INTO source_line (source_text) VALUES (?);";
		String preparedEditedLineQuery = "INSERT INTO edited_line (edited_text) VALUES (?);";
		String preparedChangeLogQuery = "INSERT INTO change_log (edited_line_id) VALUES (?);";
		
		String preparedEntryQuery = "INSERT INTO entry"
			+ " (page_id, language, field_id, source_line_id, current_change_id)"
			+ " SELECT page_id, ?, ?, ?, ?"
			+ " FROM page_title"
			+ " WHERE page_title.page_title = ?;";
		
		String preparedPendingQuery = "INSERT INTO pending (entry_id) VALUES (?);";
		
		int opt = Statement.RETURN_GENERATED_KEYS;
		
		PreparedStatement insertSourceLine = conn.prepareStatement(preparedSourceLineQuery, opt);
		PreparedStatement insertEditedLine = conn.prepareStatement(preparedEditedLineQuery, opt);
		PreparedStatement insertChangeLog = conn.prepareStatement(preparedChangeLogQuery, opt);
		PreparedStatement insertEntry = conn.prepareStatement(preparedEntryQuery, opt);
		PreparedStatement insertPending = conn.prepareStatement(preparedPendingQuery, opt);
		
		conn.setAutoCommit(false);
		
		int sourceLineRows = 0;
		int editedLineRows = 0;
		int entryRows = 0;
		int pendingRows = 0;
		
		for (Entry entry : entries) {
			int sourceLineId;
			int editedLineId;
			int changeLogId;
			int entryId;
			
			ResultSet rs;
			
			insertSourceLine.setString(1, entry.originalText);
			insertSourceLine.executeUpdate();
			
			rs = insertSourceLine.getGeneratedKeys();
			
			if (rs.next()) {
				sourceLineId = rs.getInt(1);
				sourceLineRows++;
			} else {
				continue;
			}
			
			insertEditedLine.setString(1, entry.newText);
			insertEditedLine.executeUpdate();
			
			rs = insertEditedLine.getGeneratedKeys();
			
			if (rs.next()) {
				editedLineId = rs.getInt(1);
				editedLineRows++;
			} else {
				continue;
			}
			
			insertChangeLog.setInt(1, editedLineId);
			insertChangeLog.executeUpdate();
			
			rs = insertChangeLog.getGeneratedKeys();
			
			if (rs.next()) {
				changeLogId = rs.getInt(1);
			} else {
				continue;
			}
			
			insertEntry.setString(1, entry.langSection);
			insertEntry.setInt(2, entry.fieldType.ordinal() + 1);
			insertEntry.setInt(3, sourceLineId);
			insertEntry.setInt(4, changeLogId);
			insertEntry.setString(5, entry.title);
			insertEntry.executeUpdate();
			
			rs = insertEntry.getGeneratedKeys();
			
			if (rs.next()) {
				entryId = rs.getInt(1);
				entryRows++;
			} else {
				continue;
			}
			
			insertPending.setInt(1, entryId);
			insertPending.executeUpdate();
			pendingRows++;
			
			conn.commit();
		}
		
		conn.setAutoCommit(true);
		
		System.out.printf(
			"Inserted rows: source_line - %d, edited_line - %d, entry - %d, pending - %d.%n",
			sourceLineRows, editedLineRows, entryRows, pendingRows
		);
	}
	
	private static void populatePendingTable(Connection conn, List<Integer> entryIds) throws SQLException {
		String values = entryIds.stream()
			.map(id -> String.format("(%d)", id))
			.collect(Collectors.joining(", "));
		
		String query = "INSERT INTO pending (entry_id)"
			+ " VALUES " + values
			+ " ON DUPLICATE KEY"
			+ " UPDATE entry_id = VALUES(entry_id);";
		
		Statement stmt = conn.createStatement();
		int insertedRows = stmt.executeUpdate(query);
		System.out.printf("%d rows inserted into 'pending' table.%n", insertedRows);
	}
	
	private static List<Entry> queryPendingEntries(Connection conn) throws SQLException {
		String query = "SELECT entry_id, page_title, language, field_id, source_text, edited_text"
			+ " FROM all_entries"
			+ " WHERE is_pending IS TRUE;";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		Map<Integer, Entry> entryMap = new LinkedHashMap<>(1000);
		
		while (rs.next()) {
			processEntryResultSet(rs, entryMap);
		}
		
		return new ArrayList<>(entryMap.values());
	}
	
	private static List<Entry> reviewPendingEntries(Connection conn, List<Entry> pendingEntries,
			Map<String, String> contentCache, boolean isDump) throws SQLException {
		Set<String> titles = pendingEntries.stream()
			.map(entry -> entry.title)
			.collect(Collectors.toSet());
		
		contentCache.keySet().retainAll(titles);
		
		if (isDump) {
			titles.removeAll(contentCache.keySet());
			
			if (!titles.isEmpty()) {
				String[] arr = titles.toArray(new String[titles.size()]);
				
				try {
					Stream.of(wb.getContentOfPages(arr, 400)).forEach(pc ->
						contentCache.putIfAbsent(pc.getTitle(), pc.getText())
					);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		List<Entry> worklist = new ArrayList<>(300);
		
		for (Entry entry : pendingEntries) {
			if (!contentCache.containsKey(entry.title)) {
				continue;
			}
			
			String pageText = contentCache.get(entry.title);
			
			boolean isPresent = Optional.of(Page.store(entry.title, pageText))
				.flatMap(p -> p.getSection(entry.langSection))
				.flatMap(s -> s.getField(entry.fieldType))
				.filter(f -> !f.isEmpty())
				.map(Field::getContent)
				.filter(text -> !text.contains(entry.originalText))
				.isPresent();
			
			if (isPresent) {
				worklist.add(entry);
			}
		}
		
		return worklist;
	}
	
	private static void deleteObsoletePendingEntries(Connection conn, List<Entry> entries) throws SQLException {
		String values = entries.stream()
			.map(entry -> String.format("'%s'", entry.title.replace("'", "\\'")))
			.collect(Collectors.joining(", "));
		
		String query = "DELETE pending"
			+ " FROM pending"
			+ " INNER JOIN entry"
			+ " ON entry.entry_id = pending.entry_id"
			+ " INNER JOIN page_title"
			+ " ON page_title.page_id = entry.page_id"
			+ " WHERE page_title IN (" + values + ");";
		
		Statement stmt = conn.createStatement();
		int deletedRows = stmt.executeUpdate(query);
		System.out.printf("%d rows deleted from 'pending' table.%n", deletedRows);
	}
	
	private static void processPendingEntries(Connection conn, Map<Integer, Entry> entryMap) throws SQLException {
		String gapTimestamp = getGapTimestamp();
		System.out.printf("Gap timestamp set to %s (-%d hours).%n", gapTimestamp, HOURS_GAP);
		
		Map<Integer, Entry> verifiedEntries = queryVerifiedEntries(conn, gapTimestamp);
		int deletedEntries = deleteRejectedEntries(conn, gapTimestamp);
		
		System.out.printf("%d entries fetched from RC/read from dump.%n", entryMap.size());
		System.out.printf("%d verified entries retrieved from DB.%n", verifiedEntries.size());
		System.out.printf("%d rejected entries deleted from DB.%n", deletedEntries);
		
		entryMap.putAll(verifiedEntries);
		conn.setAutoCommit(false);
		
		for (Map.Entry<Integer, Entry> entry : entryMap.entrySet()) {
			if (editEntry(conn, entry.getKey(), entry.getValue(), gapTimestamp)) {
				try {
					Thread.sleep(EDIT_THROTTLE_MS);
				} catch (InterruptedException e) {}
			}
		}
		
		conn.setAutoCommit(true);
	}
	
	private static String getGapTimestamp() {
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.HOUR, -HOURS_GAP);
		return DATE_FORMAT.format(cal.getTime());
	}
	
	private static Map<Integer, Entry> queryVerifiedEntries(Connection conn, String gapTimestamp) throws SQLException {
		String query = "SELECT entry_id, page_title, language, field_id, source_text, edited_text"
			+ " FROM all_entries"
			+ " WHERE is_pending IS TRUE"
			+ " AND review_status = 1"
			+ " AND review_timestamp <= " + gapTimestamp + ";";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		Map<Integer, Entry> entryMap = new LinkedHashMap<>(1000);
		
		while (rs.next()) {
			processEntryResultSet(rs, entryMap);
		}
		
		return entryMap;
	}
	
	private static int deleteRejectedEntries(Connection conn, String gapTimestamp) throws SQLException {
		String query = "DELETE pending"
			+ " FROM pending"
			+ " INNER JOIN reviewed"
			+ " ON reviewed.entry_id = pending.entry_id"
			+ " INNER JOIN review_log"
			+ " ON review_log.review_log_id = reviewed.review_log_id"
			+ " WHERE review_log.review_status = 0"
			+ " AND review_log.timestamp <= " + gapTimestamp + ";";
		
		return conn.createStatement().executeUpdate(query);
	}
	
	private static boolean editEntry(Connection conn, int entryId, Entry entry, String gapTimestamp)
			throws SQLException {
		Statement queryRevisionLog = conn.createStatement();
		
		ResultSet rs = queryRevisionLog.executeQuery(
			"SELECT review_log.user, review_log.timestamp"
			+ " FROM reviewed"
			+ " INNER JOIN review_log"
			+ " ON review_log.review_log_id = reviewed.review_log_id"
			+ " WHERE reviewed.entry_id = " + entryId + ";"
		);
		
		if (!rs.next()) {
			System.out.printf("Entry not found: %s.%n", entry.title);
			conn.rollback();
			return false;
		}
		
		String user = rs.getString("user");
		String timestamp = rs.getString("timestamp");
		
		if (Integer.parseUnsignedInt(timestamp) > Integer.parseUnsignedInt(gapTimestamp)) {
			System.out.printf("log-timestamp > gap-timestamp (%s).%n", entry.title);
			conn.rollback();
			return false;
		}
		
		Statement deletePending = conn.createStatement(); 
		deletePending.executeUpdate("DELETE FROM pending WHERE pending.entry_id = " + entryId + ";");
		
		Calendar now = Calendar.getInstance();
		Optional<Wiki.Revision> optRevision;
		
		try {
			Calendar basetime = wb.getTopRevision(entry.title).getTimestamp();
			String pageText = wb.getPageText(entry.title);
			Page page = Page.store(entry.title, pageText);
			
			String summary = String.format(
				"%1$s; weryfikacja: [[User:%2$s|%2$s]] (#%d)",
				EDIT_SUMMARY, user, entryId
			);
			
			Field field = page.getSection(entry.langSection)
				.flatMap(s -> s.getField(entry.fieldType))
				.filter(f -> !f.isEmpty())
				.filter(f -> Stream.of(f.getContent().split("\n"))
					.anyMatch(s -> s.equals(entry.originalText))
				)
				.orElseThrow(() -> new Error("Could not find targeted text for page '" + entry.title + "'."));
			
			String newContent = Stream.of(field.getContent().split("\n"))
				.map(line -> line.equals(entry.originalText) ? entry.newText : line)
				.collect(Collectors.joining("\n"));
			
			field.editContent(newContent);
			wb.edit(entry.title, page.toString(), summary, basetime);
			
			optRevision = Stream.of(wb.contribs("PBbot", "", now, null, 0))
				 .filter(c -> c.getPage().equals(entry.title) && c.getSummary().startsWith(EDIT_SUMMARY))
				 .findFirst();
		} catch (AssertionError | AccountLockedException e) {
			System.out.println(e.getMessage());
			conn.rollback();
			System.exit(0);
			return false;
		} catch (IOException | LoginException e) {
			System.out.println(e.getMessage());
			conn.rollback();
			return false;
		} catch (Error e) {
			System.out.println(e.getMessage());
			conn.commit();
			return false;
		}
		
		if (optRevision.isPresent()) {
			Wiki.Revision revision = optRevision.get();
			long revId = revision.getRevid();
			Timestamp revTimestamp = new Timestamp(revision.getTimestamp().getTime().getTime());
			
			// 'edit_timestamp' may be omitted thanks to declaring CURRENT_TIMESTAMP as the default value.
			PreparedStatement st = conn.prepareStatement("INSERT INTO edit_log"
				+ " (entry_id, rev_id, edit_timestamp)"
				+ " VALUES (?, ?, ?);"
			);
			
			st.setInt(1, entryId);
			st.setInt(2, (int) revId);
			st.setTimestamp(3, revTimestamp);
			
			st.executeUpdate();
		}
		
		conn.commit();
		return true;
	}
	
	private static void updateTimestampTable(Connection conn, String type) throws SQLException {
		String query = "INSERT INTO execution_log (type)"
			+ " VALUES ('" + type + "')"
			+ " ON DUPLICATE KEY"
			+ " UPDATE timestamp = NOW();";
		
		conn.createStatement().executeUpdate(query);
	}
	
	private static class Entry implements Serializable, Comparable<Entry> {
		private static final long serialVersionUID = 4565508346026187762L;
		
		String title;
		String langSection;
		FieldTypes fieldType;
		String originalText;
		String newText;
		
		Entry(String title, String langSection, FieldTypes fieldType, String originalText, String newText) {
			this.title = title;
			this.langSection = langSection;
			this.fieldType = fieldType;
			this.originalText = Optional.ofNullable(originalText).orElse("");
			this.newText = Optional.ofNullable(newText).orElse("");
		}
		
		static Entry constructNewEntry(Field field, String originalText, String newText) {
			Section section = field.getContainingSection().get();
			String pageTitle = section.getContainingPage().get().getTitle();
			return new Entry(pageTitle, section.getLang(), field.getFieldType(), originalText, newText);
		}
		
		@Override
		public String toString() {
			return String.format(
				"['%s', %s, %s]:%n%s%n%s%n",
				title, langSection, fieldType, originalText, newText
			);
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			
			if (!(o instanceof Entry)) {
				return false;
			}
			
			Entry e = (Entry) o;
			
			return
				title.equals(e.title) &&
				langSection.equals(e.langSection) &&
				fieldType == e.fieldType &&
				originalText.equals(e.originalText) &&
				newText.equals(e.newText);
		}
		
		@Override
		public int hashCode() {
			return
				title.hashCode() + langSection.hashCode() + fieldType.hashCode() +
				originalText.hashCode() + newText.hashCode();
		}

		@Override
		public int compareTo(Entry e) {
			if (!title.equals(e.title)) {
				return title.compareTo(e.title);
			}
			
			if (!langSection.equals(e.langSection)) {
				return langSection.compareTo(langSection);
			}
			
			if (fieldType != e.fieldType) {
				return fieldType.compareTo(e.fieldType);
			}
			
			if (!originalText.equals(e.originalText)) {
				return originalText.compareTo(e.originalText);
			}
			
			return 0;
		}
	}
}