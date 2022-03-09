package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class MorfeoDatabase {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MorfeoDatabase/");
	private static final Properties defaultSQLProperties = new Properties();
	
	private static final String SQL_PLWIKT_URI = "jdbc:mysql://plwiktionary.analytics.db.svc.wikimedia.cloud:3306/plwiktionary_p";
	private static final String SQL_EOM_URI = "jdbc:mysql://tools.db.svc.eqiad.wmflabs:3306/s52584__plwikt_eom_backlinks";
	private static final String SQL_COMMON_URI = "jdbc:mysql://tools.db.svc.eqiad.wmflabs:3306/s52584__plwikt_common";
	
	private static final byte MORPHEM_RED_LINK = 0;
	private static final byte MORPHEM_MISSING = 1;
	private static final byte MORPHEM_NORMAL = 2;
	private static final byte MORPHEM_PREFIX = 4;
	private static final byte MORPHEM_SUFFIX = 8;
	private static final byte MORPHEM_GRAMMATICAL = 16;
	private static final byte MORPHEM_UNKNOWN = 32;
	
	private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
	
	static {
		defaultSQLProperties.setProperty("enabledTLSProtocols", "TLSv1.2");
	}
	
	public static void main(String[] args) throws Exception {
		Login.login(wb);
		
		List<PageContainer> pages = wb.getContentOfTransclusions("Szablon:morfeo", Wiki.MAIN_NAMESPACE);
		Map<String, List<String>> items = retrieveItems(pages);
		
		List<String> morphems = items.values().stream()
			.flatMap(Collection::stream)
			.distinct()
			.toList();
		
		System.out.printf("%d items retrieved (%d distinct morphems).%n", items.size(), morphems.size());
		
		Class.forName("com.mysql.cj.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		Map<String, Byte> morphemInfo;
		
		try (Connection plwiktConn = DriverManager.getConnection(SQL_PLWIKT_URI, properties)) {
			morphemInfo = findMissingPages(plwiktConn, morphems);
		} catch (SQLException e) {
			morphemInfo = checkMissingPagesFallback(morphems);
		}
		
		inspectEsperantoCategories(morphemInfo);
		
		Connection eomConn = DriverManager.getConnection(SQL_EOM_URI, properties);
		
		try (eomConn) {
			eomConn.setAutoCommit(false);
			
			int deleted = deleteMorfeoItems(eomConn, items);
			int updated = updateMorfeoItems(eomConn, items, morphemInfo);
			int inserted = insertMorfeoItems(eomConn, items, morphemInfo);
			
			System.out.printf("%d/%d/%d rows deleted/updated/inserted.%n", deleted, updated, inserted);
			
			if (deleted + updated + inserted != 0) {
				System.out.println("Committing changes to database.");
				eomConn.commit();
			} else {
				eomConn.rollback(); // finalize the transaction, release any locks held
			}
		} catch (SQLException e) {
			e.printStackTrace();
			eomConn.rollback();
			return;
		}
		
		try (Connection commonConn = DriverManager.getConnection(SQL_COMMON_URI, properties)) {
			updateTimestampTable(commonConn);
		}
	}
	
	private static Map<String, List<String>> retrieveItems(List<PageContainer> pages) {
		Map<String, List<String>> map = pages.stream()
			.map(Page::wrap)
			.flatMap(page -> page.getSection("esperanto").stream())
			.flatMap(section -> section.getField(FieldTypes.MORPHOLOGY).stream())
			.filter(field -> !field.isEmpty())
			.collect(Collectors.toMap(
				field -> field.getContainingSection().get().getContainingPage().get().getTitle(),
				MorfeoDatabase::parseMorfeoTemplatesFromField
			));
		
		map.values().removeIf(Objects::isNull);
		return map;
	}
	
	private static List<String> parseMorfeoTemplatesFromField(Field f) {
		List<String> templates = ParseUtils.getTemplates("morfeo", f.getContent());
		
		if (templates.isEmpty()) {
			return null;
		}
		
		String template = templates.get(0);
		
		Map<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
		params.remove("templateName");
		params.values().removeIf(String::isEmpty);
		
		return new ArrayList<>(params.values());
	}
	
	private static Map<String, Byte> findMissingPages(Connection conn, List<String> morphems) throws SQLException {
		String values = morphems.stream()
			.map(morphem -> String.format("'%s'", morphem.replace("'", "\\'")))
			.collect(Collectors.joining(","));
		
		String query = String.format("""
			SELECT
				CONVERT(page_title USING utf8) AS page_title
			FROM
				page
			WHERE
				page_namespace = 0 AND
				page_title IN (%s);
			""", values);
		
		ResultSet rs = conn.createStatement().executeQuery(query);
		Set<String> set = new HashSet<>(morphems.size());
		
		while (rs.next()) {
			String title = rs.getString("page_title");
			set.add(title);
		}
		
		System.out.printf("%d out of %d pages found in plwiktionary_p.%n", set.size(), morphems.size());
		
		// Map.merge doesn't like null values, don't use java 8 streams here
		Map<String, Byte> map = new HashMap<>(morphems.size(), 1);
		
		for (String morphem : morphems) {
			if (set.contains(morphem)) {
				map.put(morphem, null);
			} else {
				map.put(morphem, MORPHEM_RED_LINK);
			}
		}
		
		return map;
	}
	
	private static Map<String, Byte> checkMissingPagesFallback(List<String> morphems) throws IOException {
		boolean[] exist = wb.exists(morphems);
		Map<String, Byte> map = new HashMap<>(morphems.size(), 1);
		
		for (int i = 0; i < morphems.size(); i++) {
			if (exist[i]) {
				map.put(morphems.get(i), null);
			} else {
				map.put(morphems.get(i), MORPHEM_RED_LINK);
			}
		}
		
		return map;
	}
	
	private static void inspectEsperantoCategories(Map<String, Byte> map) throws IOException {
		List<String> list = map.entrySet().stream()
			.filter(entry -> entry.getValue() == null)
			.map(Map.Entry::getKey)
			.toList();
		
		List<String> _all = wb.getCategoryMembers("esperanto (morfem) (indeks)", Wiki.MAIN_NAMESPACE);
		List<String> _normal = wb.getCategoryMembers("Esperanto - morfemy", Wiki.MAIN_NAMESPACE);
		List<String> _prefix = wb.getCategoryMembers("Esperanto - morfemy przedrostkowe", Wiki.MAIN_NAMESPACE);
		List<String> _suffix = wb.getCategoryMembers("Esperanto - morfemy przyrostkowe", Wiki.MAIN_NAMESPACE);
		List<String> _grammatical = wb.getCategoryMembers("Esperanto - końcówki gramatyczne", Wiki.MAIN_NAMESPACE);
		
		Set<String> all = new HashSet<>(_all);
		Set<String> normal = new HashSet<>(_normal);
		Set<String> prefix = new HashSet<>(_prefix);
		Set<String> suffix = new HashSet<>(_suffix);
		Set<String> grammatical = new HashSet<>(_grammatical);
		
		for (String morphem : list) {
			if (!all.contains(morphem)) {
				map.put(morphem, MORPHEM_MISSING);
			} else {
				byte bitmask = 0;
				
				if (normal.contains(morphem)) {
					bitmask |= MORPHEM_NORMAL;
				}
				
				if (prefix.contains(morphem)) {
					bitmask |= MORPHEM_PREFIX;
				}
				
				if (suffix.contains(morphem)) {
					bitmask |= MORPHEM_SUFFIX;
				}
				
				if (grammatical.contains(morphem)) {
					bitmask |= MORPHEM_GRAMMATICAL;
				}
				
				if (bitmask == 0) {
					bitmask = MORPHEM_UNKNOWN;
				}
				
				map.put(morphem, bitmask);
			}
		}
	}
	
	private static Properties prepareSQLProperties() throws IOException {
		Properties properties = new Properties(defaultSQLProperties);
		Pattern patt = Pattern.compile("(.+)='(.+)'");
		Path cnf = Paths.get("./replica.my.cnf");
		
		if (!Files.exists(cnf)) {
			cnf = LOCATION.resolve(".my.cnf");
		}
		
		Files.readAllLines(cnf).stream()
			.map(patt::matcher)
			.filter(Matcher::matches)
			.forEach(m -> properties.setProperty(m.group(1), m.group(2)));
		
		return properties;
	}
	
	private static int deleteMorfeoItems(Connection conn, Map<String, List<String>> items) throws SQLException {
		ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM morfeo;");
		List<Integer> ids = new ArrayList<>(100);
		
		while (rs.next()) {
			int id = rs.getInt("id");
			String title = rs.getString("title");
			List<String> morphems = items.get(title);
			
			if (morphems == null) {
				ids.add(id);
			} else {
				int position = rs.getInt("position");
				
				if (position > morphems.size()) {
					ids.add(id);
				}
			}
		}
		
		if (!ids.isEmpty()) {
			String values = ids.stream().map(Object::toString).collect(Collectors.joining(","));
			String query = "DELETE FROM morfeo WHERE id IN (" + values + ");";
			return conn.createStatement().executeUpdate(query);
		} else {
			return 0;
		}
	}
	
	private static int updateMorfeoItems(Connection conn, Map<String, List<String>> items,
			Map<String, Byte> morphemInfo) throws SQLException {
		Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
		ResultSet rs = stmt.executeQuery("SELECT * FROM morfeo;");
		int updatedRows = 0;
		
		while (rs.next()) {
			String title = rs.getString("title");
			String morphem = rs.getString("morphem");
			int position = rs.getInt("position");
			byte type = rs.getByte("type");
			
			List<String> morphems = items.get(title);
			String extractedMorphem = morphems.set(position - 1, null);
			byte morphemType = morphemInfo.get(extractedMorphem);
			boolean isUpdatedRow = false;
			
			if (!morphem.equals(extractedMorphem)) {
				rs.updateString("morphem", extractedMorphem);
				isUpdatedRow = true;
			}
			
			if (type != morphemType) {
				rs.updateByte("type", morphemType);
				isUpdatedRow = true;
			}
			
			if (isUpdatedRow) {
				rs.updateRow();
				updatedRows++;
			}
		}
		
		items.values().removeIf(morphems -> morphems.stream().allMatch(Objects::isNull));
		return updatedRows;
	}
	
	private static int insertMorfeoItems(Connection conn, Map<String, List<String>> items,
		Map<String, Byte> morphemInfo) throws SQLException {
		List<String> values = new ArrayList<>(items.size());
		
		for (Map.Entry<String, List<String>> entry : items.entrySet()) {
			String title = entry.getKey();
			List<String> morphems = entry.getValue();
			
			for (int i = 0; i < morphems.size(); i++) {
				String morphem = morphems.get(i);
				
				if (morphem == null) {
					continue;
				}
				
				String row = String.format(
					"('%s', '%s', %d, %d)",
					title.replace("'", "\\'"), morphem.replace("'", "\\'"), i + 1, morphemInfo.get(morphem)
				);
				
				values.add(row);
			}
		}
		
		if (!values.isEmpty()) {
			String query = "INSERT INTO morfeo (title, morphem, position, type) VALUES " + String.join(",", values) + ";";
			return conn.createStatement().executeUpdate(query);
		} else {
			return 0;
		}
	}
	
	private static void updateTimestampTable(Connection conn) throws SQLException {
		String query = """
			INSERT INTO execution_log (type)
			VALUES ('tasks.plwikt.MorfeoDatabase')
			ON DUPLICATE KEY
			UPDATE timestamp = NOW();
			""";
		
		conn.createStatement().executeUpdate(query);
	}
}
