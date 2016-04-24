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
		
		List<Item> items;
		
		if (!titles.isEmpty()) {
			String[] combinedTitles = titles.toArray(new String[titles.size()]);
			PageContainer[] pages = wb.getContentOfPages(combinedTitles, 400);
			
			items = Stream.of(pages).parallel()
				.flatMap(CitationTypography::mapOccurrences)
				.collect(Collectors.toList());
			
			System.out.printf("%d items extracted.%n", items.size());
			
			if (!items.isEmpty()) {
				mapPageIds(items);
				storeResults(items);
			}
		} else {
			System.out.println("No titles extracted.");
			items = new ArrayList<>(0);
		}
		
		if (line.hasOption("edit") || line.hasOption("update")) {
			Class.forName("com.mysql.jdbc.Driver");
			Properties properties = prepareSQLProperties();
			
			try (
				Connection vcConn = DriverManager.getConnection(SQL_VC_URI, properties);
				Connection commonConn = DriverManager.getConnection(SQL_COMMON_URI, properties);
			) {
				vcConn.createStatement().executeQuery("SET NAMES utf8mb4;"); // important
				Map<Integer, Item> entryMap = new LinkedHashMap<>();
				
				if (line.hasOption("update")) {
					if (!items.isEmpty()) {
						entryMap = updateSQLTables(vcConn, items);
					}
					
					updateTimestampTable(commonConn, "tasks.plwikt.CitationTypography.update");
				}
				
				if (line.hasOption("edit")) {
					wb.setThrottle(0);
					wb.setMarkMinor(true);
					
					processPendingItems(vcConn, entryMap);
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
		boolean isForeignExample = isForeignExampleField(field);
		Matcher mLine = P_LINE.matcher(field.getContent());
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
	
	private static void mapPageIds(List<Item> items) throws IOException {
		String[] titles = items.stream().map(item -> item.title).distinct().toArray(String[]::new);
		@SuppressWarnings("unchecked")
		Map<String, Object>[] infos = wb.getPageInfo(titles);
		Map<String, Integer> titleToPageId = new HashMap<>(infos.length, 1);
		
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
	
	private static void storeResults(List<Item> items) throws FileNotFoundException, IOException {
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
	
	private static Map<Integer, Item> updateSQLTables(Connection conn, List<Item> items) throws SQLException {
		updatePageTitleTable(conn, items);
		
		Map<Integer, Item> entryMap = analyzeStoredEntries(conn, items);
					
		if (!items.isEmpty()) {
			storeNewItems(conn, items);
		}
		
		// TODO: review pending entries, remove the outdated ones
		
		return entryMap;
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
	
	private static Map<Integer, Item> analyzeStoredEntries(Connection conn, List<Item> items)
			throws SQLException {
		String pageIdList = items.stream()
			.map(item -> item.pageId)
			.map(Object::toString)
			.distinct()
			.collect(Collectors.joining(", "));
		
		String query = "SELECT entry.entry_id, entry.page_id, page_title.page_title,"
				+ " entry.language, entry.field_id, source_line.source_text, edited_line.edited_text,"
				+ " review_log.review_status, pending.entry_id AS pending_id"
			+ " FROM entry"
			+ " LEFT JOIN pending"
			+ " ON pending.entry_id = entry.entry_id"
			+ " INNER JOIN source_line"
			+ " ON source_line.source_line_id = entry.source_line_id"
			+ " INNER JOIN edited_line"
			+ " ON edited_line.edited_line_id = entry.edited_line_id"
			+ " INNER JOIN page_title"
			+ " ON page_title.page_id = entry.page_id"
			+ " LEFT JOIN reviewed"
			+ " ON reviewed.entry_id = entry.entry_id"
			+ " LEFT JOIN review_log"
			+ " ON review_log.review_log_id = reviewed.review_log_id"
			+ " WHERE entry.page_id IN (" + pageIdList + ");";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		Map<Integer, Item> entryMap = new LinkedHashMap<>(items.size());
		Set<Item> verifiedNonPendingItems = new HashSet<>(items.size());
		
		while (rs.next()) {
			Item item = processEntryResultSet(rs, entryMap);
			
			boolean verified = rs.getBoolean("review_status");
			boolean isPending = rs.getInt("pending_id") != 0;
			
			if (verified && !isPending) {
				verifiedNonPendingItems.add(item);
			}
		}
		
		System.out.printf(
			"%d items already stored in DB, %d marked as verified and eligible for automated edit.%n",
			entryMap.size(), verifiedNonPendingItems.size()
		);
		
		items.removeAll(new HashSet<>(entryMap.values()));
		entryMap.keySet().retainAll(verifiedNonPendingItems);
		
		return entryMap;
	}
	
	private static Item processEntryResultSet(ResultSet rs, Map<Integer, Item> entryMap) throws SQLException {
		int entryId = rs.getInt("entry_id");
		int pageId = rs.getInt("page_id");
		
		String title = rs.getString("page_title");
		String language = rs.getString("language");
		
		int fieldId = rs.getInt("field_id");
		FieldTypes fieldType = FieldTypes.values()[fieldId - 1];
		
		String sourceLineText = rs.getString("source_text");
		String editedLineText = rs.getString("edited_text");
		
		Item item = new Item(pageId, title, language, fieldType, sourceLineText, editedLineText);
		
		entryMap.put(entryId, item);
		
		return item;
	}
	
	private static void storeNewItems(Connection conn, List<Item> items) throws SQLException {
		String preparedSourceLineQuery = "INSERT INTO source_line (source_text)"
			+ " VALUES (?);";
		
		String preparedEditedLineQuery = "INSERT INTO edited_line (edited_text, user, timestamp)"
			+ " VALUES (?, ?, '" + formatCalendar(Calendar.getInstance()) + "');";
		
		String preparedEntryQuery = "INSERT INTO entry"
			+ " (page_id, language, field_id, source_line_id, edited_line_id)"
			+ " VALUES (?, ?, ?, ?, ?);";
		
		String preparedPendingQuery = "INSERT INTO pending (entry_id) VALUES (?);";
		
		int opt = Statement.RETURN_GENERATED_KEYS;
		
		PreparedStatement insertSourceLine = conn.prepareStatement(preparedSourceLineQuery, opt);
		PreparedStatement insertEditedLine = conn.prepareStatement(preparedEditedLineQuery, opt);
		PreparedStatement insertEntry = conn.prepareStatement(preparedEntryQuery, opt);
		PreparedStatement insertPending = conn.prepareStatement(preparedPendingQuery, opt);
		
		conn.setAutoCommit(false);
		
		for (Item item : items) {
			int sourceLineId;
			int editedLineId;
			int entryId;
			
			ResultSet rs;
			
			insertSourceLine.setString(1, item.originalText);
			insertSourceLine.executeUpdate();
			
			rs = insertSourceLine.getGeneratedKeys();
			
			if (rs.next()) {
				sourceLineId = rs.getInt(1); 
			} else {
				continue;
			}
			
			insertEditedLine.setString(1, item.newText);
			insertEditedLine.setString(2, "PBbot");
			insertEditedLine.executeUpdate();
			
			rs = insertEditedLine.getGeneratedKeys();
			
			if (rs.next()) {
				editedLineId = rs.getInt(1);
			} else {
				continue;
			}
			
			insertEntry.setInt(1, item.pageId);
			insertEntry.setString(2, item.langSection);
			insertEntry.setInt(3, item.fieldType.ordinal() + 1);
			insertEntry.setInt(4, sourceLineId);
			insertEntry.setInt(5, editedLineId);
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
	
	private static String formatCalendar(Calendar cal) {
		return DATE_FORMAT.format(cal.getTime());
	}
	
	private static void processPendingItems(Connection conn, Map<Integer, Item> entryMap) throws SQLException {
		String gapTimestamp = getGapTimestamp();
		System.out.printf("Gap timestamp set to %s (-%d hours).%n", gapTimestamp, HOURS_GAP);
		
		Map<Integer, Item> verifiedEntries = queryVerifiedEntries(conn, gapTimestamp);
		int deletedEntries = deleteRejectedEntries(conn, gapTimestamp);
		
		System.out.printf("%d entries fetched from RC/read from dump.%n", entryMap.size());
		System.out.printf("%d verified entries retrieved from DB.%n", verifiedEntries.size());
		System.out.printf("%d rejected entries deleted from DB.%n", deletedEntries);
		
		entryMap.putAll(verifiedEntries);
		conn.setAutoCommit(false);
		
		for (Map.Entry<Integer, Item> entry : entryMap.entrySet()) {
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
	
	private static Map<Integer, Item> queryVerifiedEntries(Connection conn, String gapTimestamp) throws SQLException {
		String query = "SELECT entry.entry_id, entry.page_id, page_title.page_id,"
				+ " entry.language, entry.field_id, source_line.source_text, edited_line.edited_text"
			+ " FROM entry"
			+ " INNER JOIN page_title"
			+ " ON page_title.page_id = entry.page_id"
			+ " INNER JOIN source_line"
			+ " ON source_line.source_line_id = entry.source_line_id"
			+ " INNER JOIN edited_line"
			+ " ON edited_line.edited_line_id = entry.edited_line_id"
			+ " INNER JOIN reviewed"
			+ " ON reviewed.entry_id = entry.entry_id"
			+ " INNER JOIN review_log"
			+ " ON review_log.review_log_id = reviewed.review_log_id"
			+ " WHERE EXISTS (SELECT NULL FROM pending WHERE pending.entry_id = entry.entry_id)"
			+ " AND review_log.review_status = 1"
			+ " AND review_log.timestamp <= " + gapTimestamp + ";";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		Map<Integer, Item> entryMap = new LinkedHashMap<>(1000);
		
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
	
	private static boolean editEntry(Connection conn, int entryId, Item item, String gapTimestamp)
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
			System.out.printf("Entry not found: %s.%n", item.title);
			conn.rollback();
			return false;
		}
		
		String user = rs.getString("user");
		String timestamp = rs.getString("timestamp");
		
		if (Integer.parseUnsignedInt(timestamp) > Integer.parseUnsignedInt(gapTimestamp)) {
			System.out.printf("log-timestamp > gap-timestamp (%s).%n", item.title);
			conn.rollback();
			return false;
		}
		
		Statement deletePending = conn.createStatement(); 
		deletePending.executeUpdate("DELETE FROM pending WHERE pending.entry_id = " + entryId + ";");
		
		Calendar now = Calendar.getInstance();
		Optional<Wiki.Revision> optRevision = Optional.empty();
		
		try {
			Calendar basetime = wb.getTopRevision(item.title).getTimestamp();
			String pageText = wb.getPageText(item.title);
			Page page = Page.store(item.title, pageText);
			
			String summary = String.format(
				"%1$s; weryfikacja: [[User:%2$s|%2$s]] (#%d)",
				EDIT_SUMMARY, user, entryId
			);
			
			Field field = page.getSection(item.langSection)
				.flatMap(s -> s.getField(item.fieldType))
				.filter(f -> !f.isEmpty())
				.filter(f -> Stream.of(f.getContent().split("\n"))
					.anyMatch(s -> s.equals(item.originalText))
				)
				.orElseThrow(() -> new Error("Could not find targeted text for page '" + item.title + "'."));
			
			String newContent = Stream.of(field.getContent().split("\n"))
				.map(line -> line.equals(item.originalText) ? item.newText : line)
				.collect(Collectors.joining("\n"));
			
			field.editContent(newContent);
			wb.edit(item.title, page.toString(), summary, basetime);
			
			optRevision = Stream.of(wb.contribs("PBbot", "", now, null, 0))
				 .filter(c -> c.getPage().equals(item.title) && c.getSummary().startsWith(EDIT_SUMMARY))
				 .findAny();
		} catch (AssertionError | AccountLockedException e) {
			System.out.println(e.getMessage());
			conn.rollback();
			System.exit(0);
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
			String revTimestamp = formatCalendar(revision.getTimestamp());
			Statement insertEditLog = conn.createStatement();
			
			insertEditLog.executeUpdate("INSERT INTO edit_log"
				+ " (entry_id, rev_id, edit_timestamp)"
				+ " VALUES (" + entryId + ", " + revId + ", " + revTimestamp + ");"
			);
		}
		
		conn.commit();
		return optRevision.isPresent();
	}
	
	private static void updateTimestampTable(Connection conn, String type) throws SQLException {
		Calendar cal = Calendar.getInstance();
		String timestamp = DATE_FORMAT.format(cal.getTime());
		
		String query = "INSERT INTO execution_log (type, timestamp)"
			+ " VALUES ('" + type + "', " + timestamp + ")"
			+ " ON DUPLICATE KEY"
			+ " UPDATE timestamp = VALUES(timestamp);";
		
		conn.createStatement().executeUpdate(query);
		System.out.printf("New timestamp (%s): %s.%n", type, timestamp);
	}
	
	private static class Item implements Serializable, Comparable<Item> {
		private static final long serialVersionUID = 4565508346026187762L;
		
		int pageId;
		String title;
		String langSection;
		FieldTypes fieldType;
		String originalText;
		String newText;
		
		Item(String title, String langSection, FieldTypes fieldType, String originalText, String newText) {
			this(0, title, langSection, fieldType, originalText, newText);
		}
		
		Item(int pageId, String title, String langSection, FieldTypes fieldType,
				String originalText, String newText) {
			this.pageId = pageId;
			this.title = title;
			this.langSection = langSection;
			this.fieldType = fieldType;
			this.originalText = Optional.ofNullable(originalText).orElse("");
			this.newText = Optional.ofNullable(newText).orElse("");
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
