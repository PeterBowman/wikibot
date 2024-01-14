package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
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

import com.github.wikibot.dumps.XMLDumpConfig;
import com.github.wikibot.dumps.XMLDumpTypes;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.DBUtils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;

public final class CitationTypography {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/CitationTypography/");

    private static final Pattern P_REFERENCE;
    private static final Pattern P_OCCURENCE;
    private static final Pattern P_LINE;

    private static final List<FieldTypes> ALLOWED_NON_POLISH_FIELDS = List.of(
        FieldTypes.EXAMPLES, FieldTypes.ETYMOLOGY, FieldTypes.NOTES
    );

    private static final int HOURS_GAP = 8;

    private static final String SQL_PLWIKT_URI = "jdbc:mysql://plwiktionary.analytics.db.svc.wikimedia.cloud:3306/plwiktionary_p";
    private static final String SQL_VC_URI = "jdbc:mysql://tools.db.svc.eqiad.wmflabs:3306/s52584__plwikt_verify_citations";
    private static final String SQL_COMMON_URI = "jdbc:mysql://tools.db.svc.eqiad.wmflabs:3306/s52584__plwikt_common";

    private static final String EDIT_SUMMARY = "[[WS:Głosowania/Pozycja odsyłacza przypisu względem kropki]]";
    private static final int EDIT_THROTTLE_MS = 5000;

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    static {
        P_REFERENCE = Pattern.compile("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", Pattern.CASE_INSENSITIVE);
        P_OCCURENCE = Pattern.compile("\\. *('{2})?((?i: *" + P_REFERENCE.pattern() + ")+)");
        P_LINE = Pattern.compile("^(.*)" + P_OCCURENCE.pattern() + "(.*)$", Pattern.MULTILINE);
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        Class.forName("com.mysql.cj.jdbc.Driver");
        Properties properties = DBUtils.prepareSQLProperties();

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
            List<PageContainer> pages = wb.getContentOfPages(titles);

            entries = pages.parallelStream()
                .flatMap(CitationTypography::mapOccurrences)
                .collect(Collectors.toCollection(ArrayList::new));

            contentCache = pages.stream().collect(Collectors.toMap(PageContainer::title, PageContainer::text));

            System.out.printf("%d entries extracted.%n", entries.size());

            if (!entries.isEmpty()) {
                List<String> l = entries.stream().map(entry -> entry.title).distinct().collect(Collectors.toCollection(ArrayList::new));

                try (Connection plwiktConn = DriverManager.getConnection(SQL_PLWIKT_URI, properties)) {
                    queryPageTable(plwiktConn, l, titleToPageId);
                } catch (SQLException e) {
                    queryPageIdsFallback(l, titleToPageId, line.hasOption("debug"));
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
                Map<Integer, Entry> entryMap = new LinkedHashMap<>(5000);

                if (line.hasOption("update")) {
                    if (!entries.isEmpty()) {
                        updatePageTitleTable(vcConn, entries, titleToPageId);
                        updateEntryAndPendingTables(vcConn, entries, entryMap);
                    }

                    Map<Integer, Entry> pendingMap = queryPendingEntries(vcConn);

                    if (!pendingMap.isEmpty()) {
                        reviewPendingEntries(vcConn, pendingMap, contentCache, line.hasOption("dump"));

                        if (!pendingMap.isEmpty()) {
                            deleteObsoletePendingEntries(vcConn, pendingMap);
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
        OffsetDateTime earliest;

        try {
            String timestamp = Files.readAllLines(LOCATION.resolve("timestamp.txt")).get(0);
            earliest = OffsetDateTime.parse(timestamp);
        } catch (Exception e) {
            System.out.println("Setting new timestamp reference (-24h).");
            earliest = OffsetDateTime.now(wb.timezone()).minusDays(1);
        }

        OffsetDateTime latest = OffsetDateTime.now(wb.timezone());

        if (!latest.isAfter(earliest)) {
            System.out.println("Extracted timestamp is greater than the current time, setting to -24h.");
            earliest = OffsetDateTime.now(wb.timezone()).minusDays(1);
        }

        List<String> rcTypes = List.of("new", "edit");
        List<Wiki.Revision> revs = wb.recentChanges(earliest, latest, null, rcTypes, false, null, Wiki.MAIN_NAMESPACE);

        Wiki.RequestHelper helper = wb.new RequestHelper().withinDateRange(earliest, latest);
        List<Wiki.LogEntry> logs = wb.getLogEntries(Wiki.MOVE_LOG, "move", helper);

        // store current timestamp for the next iteration
        Files.write(LOCATION.resolve("timestamp.txt"), List.of(latest.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));

        return Stream.concat(
            revs.stream().map(Wiki.Revision::getTitle),
            logs.stream().map(Wiki.LogEntry::getDetails)
                .map(details -> details.get("target_title"))
                .filter(title -> wb.namespace(title) == Wiki.MAIN_NAMESPACE)
        ).distinct().toArray(String[]::new);
    }

    private static String[] readDumpFile(String option) {
        var dumpConfig = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES);

        if (option.equals("local")) {
            dumpConfig.local();
        } else if (option.equals("remote")) {
            dumpConfig.remote();
        } else {
            throw new IllegalArgumentException("Illegal dump option (must be either 'local' or 'remote'): " + option);
        }

        var dump = dumpConfig.fetch().get();

        try (var stream = dump.stream()) {
            return stream
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
            StringBuilder sb = new StringBuilder(line.length());

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

    private static void queryPageTable(Connection conn, List<String> titles, Map<String, Integer> titleToPageId)
            throws SQLException {
        String values = titles.stream()
            .map(title -> String.format("'%s'", title.replace("'", "\\'").replace(" ", "_")))
            .collect(Collectors.joining(", "));

        String query = """
            SELECT
                page_title,
                page_id
            FROM
                page
            WHERE
                page_namespace = 0 AND
                page_title IN (%s);
            """.formatted(values);

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(query);

        while (rs.next()) {
            String title = rs.getString("page_title").replace("_", " ");
            int pageId = rs.getInt("page_id");
            titleToPageId.put(title, pageId);
        }
    }

    private static void queryPageIdsFallback(List<String> titles, Map<String, Integer> titleToPageId, boolean dbg)
            throws IOException {
        if (dbg) {
            try {
                @SuppressWarnings("unchecked")
                var stored = (Map<String, Integer>) new XStream().fromXML(LOCATION.resolve("title_to_page_id.xml").toFile());
                titleToPageId.putAll(stored);
                titles.removeIf(title -> stored.containsKey(title));
            } catch (XStreamException e) {
                e.printStackTrace();
            }

            if (titles.isEmpty()) {
                return;
            }
        }

        List<Map<String, Object>> infos = wb.getPageInfo(titles);

        for (int i = 0; i < infos.size(); i++) {
            Map<String, Object> info = infos.get(i);
            String title = titles.get(i);
            long pageId = (long) info.getOrDefault("pageid", -1);

            if (pageId == -1) {
                continue;
            }

            titleToPageId.putIfAbsent(title, Math.toIntExact(pageId));
        }
    }

    private static void serializeResults(List<Entry> entries, Map<String, Integer> titleToPageId) throws IOException {
        Files.writeString(LOCATION.resolve("entries.xml"), new XStream().toXML(entries));
        Files.writeString(LOCATION.resolve("title_to_page_id.xml"), new XStream().toXML(titleToPageId));

        Map<String, String> map = entries.stream()
            .collect(Collectors.toMap(
                entry -> entry.title,
                entry -> String.format("%s%n%n%s", entry.originalText, entry.newText),
                (e1, e2) -> e1,
                TreeMap::new
            ));

        Files.write(LOCATION.resolve("diffs.txt"), List.of(Misc.makeList(map)));
    }

    private static void updatePageTitleTable(Connection conn, List<Entry> entries, Map<String, Integer> titleToPageId)
            throws SQLException {
        String values = entries.stream()
            .map(entry -> String.format("(%d, '%s')", titleToPageId.get(entry.title), entry.title.replace("'", "\\'")))
            .distinct()
            .collect(Collectors.joining(", "));

        String query = """
            INSERT INTO page_title (page_id, page_title)
            VALUES %s
            ON DUPLICATE KEY
            UPDATE page_title = VALUES(page_title);
            """.formatted(values);

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
                .toList();

            populatePendingTable(conn, list);
        }

        storedEntries.values().retainAll(verifiedNonPendingEntries);
    }

    private static void analyzeStoredEntries(Connection conn, List<Entry> entries, Map<Integer, Entry> entryMap,
            Set<Entry> verifiedNonPendingEntries, Set<Entry> nonReviewedNonPendingEntries)
            throws SQLException {
        String titles = entries.stream()
            .map(entry -> entry.title)
            .distinct()
            .map(title -> String.format("'%s'", title.replace("'", "\\'")))
            .collect(Collectors.joining(","));

        String query = """
            SELECT
                entry_id,
                page_title,
                language,
                field_id,
                source_text,
                edited_text,
                review_status,
                is_pending
            FROM
                all_entries
            WHERE
                page_title IN (%s);
            """.formatted(titles);

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

        String preparedEntryQuery = """
            INSERT INTO entry (page_id, language, field_id, source_line_id, edited_line_id)
            SELECT page_id, ?, ?, ?, ?
            FROM page_title
            WHERE page_title.page_title = ?;
            """;

        String preparedChangeLogQuery = "INSERT INTO change_log (change_log_id, entry_id) VALUES (?, ?);";
        String preparedPendingQuery = "INSERT INTO pending (entry_id) VALUES (?);";

        int opt = Statement.RETURN_GENERATED_KEYS;

        PreparedStatement insertSourceLine = conn.prepareStatement(preparedSourceLineQuery, opt);
        PreparedStatement insertEditedLine = conn.prepareStatement(preparedEditedLineQuery, opt);
        PreparedStatement insertEntry = conn.prepareStatement(preparedEntryQuery, opt);
        PreparedStatement insertChangeLog = conn.prepareStatement(preparedChangeLogQuery, opt);
        PreparedStatement insertPending = conn.prepareStatement(preparedPendingQuery, opt);

        conn.setAutoCommit(false);

        int sourceLineRows = 0;
        int editedLineRows = 0;
        int entryRows = 0;
        int pendingRows = 0;

        for (Entry entry : entries) {
            int sourceLineId;
            int editedLineId;
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

            insertEntry.setString(1, entry.langSection);
            insertEntry.setInt(2, entry.fieldType.ordinal() + 1);
            insertEntry.setInt(3, sourceLineId);
            insertEntry.setInt(4, editedLineId);
            insertEntry.setString(5, entry.title);
            insertEntry.executeUpdate();

            rs = insertEntry.getGeneratedKeys();

            if (rs.next()) {
                entryId = rs.getInt(1);
                entryRows++;
            } else {
                continue;
            }

            insertChangeLog.setInt(1, editedLineId);
            insertChangeLog.setInt(2, entryId);
            insertChangeLog.executeUpdate();

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

        String query = """
            INSERT INTO pending (entry_id)
            VALUES %s
            ON DUPLICATE KEY
            UPDATE entry_id = VALUES(entry_id);
            """.formatted(values);

        Statement stmt = conn.createStatement();
        int insertedRows = stmt.executeUpdate(query);
        System.out.printf("%d rows inserted into 'pending' table.%n", insertedRows);
    }

    private static Map<Integer, Entry> queryPendingEntries(Connection conn) throws SQLException {
        String query = """
            SELECT
                entry_id,
                page_title,
                language,
                field_id,
                source_text,
                edited_text
            FROM
                all_entries
            WHERE
                is_pending IS TRUE;
            """;

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(query);

        Map<Integer, Entry> entryMap = new LinkedHashMap<>(1000);

        while (rs.next()) {
            processEntryResultSet(rs, entryMap);
        }

        return entryMap;
    }

    private static void reviewPendingEntries(Connection conn, Map<Integer, Entry> pendingMap,
            Map<String, String> contentCache, boolean isDump) {
        Set<String> titles = pendingMap.values().stream()
            .map(entry -> entry.title)
            .collect(Collectors.toSet());

        contentCache.keySet().retainAll(titles);

        if (isDump) {
            titles.removeAll(contentCache.keySet());

            if (!titles.isEmpty()) {
                try {
                    wb.getContentOfPages(titles).forEach(pc -> contentCache.putIfAbsent(pc.title(), pc.text()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        pendingMap.values().removeIf(entry ->
            !contentCache.containsKey(entry.title) ||
            Optional.of(Page.store(entry.title, contentCache.get(entry.title)))
                .flatMap(p -> p.getSection(entry.langSection))
                .flatMap(s -> s.getField(entry.fieldType))
                .filter(f -> !f.isEmpty())
                .map(Field::getContent)
                .filter(text -> Pattern.compile("\n").splitAsStream(text)
                    .anyMatch(line -> line.equals(entry.originalText))
                )
                .isPresent()
        );
    }

    private static void deleteObsoletePendingEntries(Connection conn, Map<Integer, Entry> pendingMap)
            throws SQLException {
        String values = pendingMap.keySet().stream()
            .map(Object::toString)
            .collect(Collectors.joining(", "));

        String query = "DELETE FROM pending WHERE entry_id IN (" + values + ");";
        int deletedRows = conn.createStatement().executeUpdate(query);

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
        return OffsetDateTime.now(wb.timezone()).minusHours(HOURS_GAP).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static Map<Integer, Entry> queryVerifiedEntries(Connection conn, String gapTimestamp) throws SQLException {
        String query = """
            SELECT
                entry_id,
                page_title,
                language,
                field_id,
                source_text,
                edited_text
            FROM
                all_entries
            WHERE
                is_pending IS TRUE AND
                review_status = 1 AND
                review_timestamp <= %s;
            """.formatted(gapTimestamp);

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(query);

        Map<Integer, Entry> entryMap = new LinkedHashMap<>(1000);

        while (rs.next()) {
            processEntryResultSet(rs, entryMap);
        }

        return entryMap;
    }

    private static int deleteRejectedEntries(Connection conn, String gapTimestamp) throws SQLException {
        String query = """
            DELETE
                pending
            FROM pending
                INNER JOIN reviewed ON reviewed.entry_id = pending.entry_id
                INNER JOIN review_log ON review_log.review_log_id = reviewed.review_log_id
            WHERE
                review_log.review_status = 0 AND
                review_log.timestamp <= %s;
            """.formatted(gapTimestamp);

        return conn.createStatement().executeUpdate(query);
    }

    private static boolean editEntry(Connection conn, int entryId, Entry entry, String gapTimestamp)
            throws SQLException {
        Statement queryRevisionLog = conn.createStatement();

        ResultSet rs = queryRevisionLog.executeQuery("""
            SELECT
                review_log.user,
                review_log.timestamp
            FROM
                reviewed INNER JOIN review_log ON review_log.review_log_id = reviewed.review_log_id
            WHERE
                reviewed.entry_id = %d;
            """.formatted(entryId));

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

        OffsetDateTime now = OffsetDateTime.now(wb.timezone());
        Optional<Wiki.Revision> optRevision;

        try {
            OffsetDateTime basetime = wb.getTopRevision(entry.title).getTimestamp();
            String pageText = wb.getPageText(List.of(entry.title)).get(0);

            if (pageText == null) {
                throw new FileNotFoundException("Page does not exist: " + entry.title);
            }

            Page page = Page.store(entry.title, pageText);

            String summary = String.format(
                "%1$s; weryfikacja: [[User:%2$s|%2$s]] (#%d)",
                EDIT_SUMMARY, user, entryId
            );

            Field field = page.getSection(entry.langSection)
                .flatMap(s -> s.getField(entry.fieldType))
                .filter(f -> !f.isEmpty())
                .filter(f -> f.getContent().lines()
                    .anyMatch(s -> s.equals(entry.originalText))
                )
                .orElseThrow(() -> new Error("Could not find targeted text for page '" + entry.title + "'."));

            String newContent = Stream.of(field.getContent().split("\n"))
                .map(line -> line.equals(entry.originalText) ? entry.newText : line)
                .collect(Collectors.joining("\n"));

            field.editContent(newContent);
            wb.edit(entry.title, page.toString(), summary, basetime);

            Wiki.RequestHelper helper = wb.new RequestHelper()
                .inNamespaces(Wiki.MAIN_NAMESPACE)
                .withinDateRange(now, null);

            optRevision = wb.contribs("PBbot", helper).stream()
                 .filter(c -> c.getTitle().equals(entry.title) && c.getComment().startsWith(EDIT_SUMMARY))
                 .findFirst();
        } catch (AssertionError | AccountLockedException e) {
            System.out.println(e.getMessage());
            conn.rollback();
            System.exit(0);
            return false;
        } catch (IOException | LoginException | ConcurrentModificationException | UncheckedIOException e) {
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
            long revId = revision.getID();
            Timestamp revTimestamp = Timestamp.from(revision.getTimestamp().toInstant());

            // 'edit_timestamp' may be omitted thanks to declaring CURRENT_TIMESTAMP as the default value.
            PreparedStatement st = conn.prepareStatement("""
                INSERT INTO
                    edit_log (change_log_id, rev_id, edit_timestamp)
                SELECT
                    change_log.change_log_id, ?, ?
                FROM
                    change_log
                WHERE
                    entry_id = %d AND
                    change_timestamp <= %d
                ORDER BY
                    change_log_id DESC
                LIMIT 1;
                """.formatted(entryId, gapTimestamp));

            st.setInt(1, (int) revId);
            st.setTimestamp(2, revTimestamp);

            st.executeUpdate();
        }

        conn.commit();
        return true;
    }

    private static void updateTimestampTable(Connection conn, String type) throws SQLException {
        String query = """
            INSERT INTO execution_log (type)
            VALUES ('%s')
            ON DUPLICATE KEY
            UPDATE timestamp = NOW();
            """.formatted(type);

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
            } else if (o instanceof Entry e) {
                return
                    title.equals(e.title) &&
                    langSection.equals(e.langSection) &&
                    fieldType == e.fieldType &&
                    originalText.equals(e.originalText) &&
                    newText.equals(e.newText);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, langSection, fieldType, originalText, newText);
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
