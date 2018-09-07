package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.stream.Stream;

import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class MorfeoDatabase {
	private static final String LOCATION = "./data/tasks.plwikt/MorfeoDatabase/";
	private static final Properties defaultSQLProperties = new Properties();
	
	private static final String SQL_PLWIKT_URI = "jdbc:mysql://plwiktionary.labsdb:3306/plwiktionary_p";
	private static final String SQL_EOM_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_eom_backlinks";
	private static final String SQL_COMMON_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_common";
	
	private static final byte MORPHEM_RED_LINK = 0;
	private static final byte MORPHEM_MISSING = 1;
	private static final byte MORPHEM_NORMAL = 2;
	private static final byte MORPHEM_PREFIX = 4;
	private static final byte MORPHEM_SUFFIX = 8;
	private static final byte MORPHEM_GRAMMATICAL = 16;
	private static final byte MORPHEM_UNKNOWN = 32;
	
	private static Wikibot wb;
	
	static {
		defaultSQLProperties.setProperty("autoReconnect", "true");
		defaultSQLProperties.setProperty("useUnicode", "yes");
		defaultSQLProperties.setProperty("characterEncoding", "UTF-8");
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		PageContainer[] pages = wb.getContentOfTransclusions("Szablon:morfeo", 0);
		Map<String, List<String>> items = retrieveItems(pages);
		
		String[] morphems = items.values().stream()
			.flatMap(Collection::stream)
			.distinct()
			.toArray(String[]::new);
		
		System.out.printf("%d items retrieved (%d distinct morphems).%n", items.size(), morphems.length);
		
		Class.forName("com.mysql.cj.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		Map<String, Byte> morphemInfo;
		
		try (Connection plwiktConn = DriverManager.getConnection(SQL_PLWIKT_URI, properties)) {
			morphemInfo = findMissingPages(plwiktConn, morphems);
		} catch (SQLException e) {
			morphemInfo = checkMissingPagesFallback(morphems);
		}
		
		inspectEsperantoCategories(morphemInfo);
		
		Connection eomConn = null;
		
		try {
			eomConn = DriverManager.getConnection(SQL_EOM_URI, properties);
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
		} finally {
			if (eomConn != null) {
				eomConn.close();
			}
		}
		
		try (Connection commonConn = DriverManager.getConnection(SQL_COMMON_URI, properties)) {
			updateTimestampTable(commonConn);
		}
	}
	
	private static Map<String, List<String>> retrieveItems(PageContainer[] pages) {
		Map<String, List<String>> map = Stream.of(pages)
			.map(Page::wrap)
			.flatMap(page -> Utils.streamOpt(page.getSection("esperanto")))
			.flatMap(section -> Utils.streamOpt(section.getField(FieldTypes.MORPHOLOGY)))
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
	
	private static Map<String, Byte> findMissingPages(Connection conn, String[] morphems) throws SQLException {
		String values = Stream.of(morphems)
			.map(morphem -> String.format("'%s'", morphem.replace("'", "\\'")))
			.collect(Collectors.joining(","));
		
		String query = "SELECT CONVERT(page_title USING utf8) AS page_title"
			+ " FROM page"
			+ " WHERE page_namespace = 0"
			+ " AND page_title IN (" + values + ");";
		
		ResultSet rs = conn.createStatement().executeQuery(query);
		Set<String> set = new HashSet<>(morphems.length);
		
		while (rs.next()) {
			String title = rs.getString("page_title");
			set.add(title);
		}
		
		System.out.printf("%d out of %d pages found in plwiktionary_p.%n", set.size(), morphems.length);
		
		// Map.merge doesn't like null values, don't use java 8 streams here
		Map<String, Byte> map = new HashMap<>(morphems.length, 1);
		
		for (String morphem : morphems) {
			if (set.contains(morphem)) {
				map.put(morphem, null);
			} else {
				map.put(morphem, MORPHEM_RED_LINK);
			}
		}
		
		return map;
	}
	
	private static Map<String, Byte> checkMissingPagesFallback(String[] morphems) throws IOException {
		Map<String, Object>[] info = wb.getPageInfo(morphems);
		Map<String, Byte> map = new HashMap<>(morphems.length, 1);
		
		for (int i = 0; i < info.length; i++) {
			if ((boolean) info[i].get("exists")) {
				map.put(morphems[i], null);
			} else {
				map.put(morphems[i], MORPHEM_RED_LINK);
			}
		}
		
		return map;
	}
	
	private static void inspectEsperantoCategories(Map<String, Byte> map) throws IOException {
		List<String> list = map.entrySet().stream()
			.filter(entry -> entry.getValue() == null)
			.map(Map.Entry::getKey)
			.collect(Collectors.toList());
		
		String[] _all = wb.getCategoryMembers("esperanto (morfem) (indeks)", 0);
		String[] _normal = wb.getCategoryMembers("Esperanto - morfemy", 0);
		String[] _prefix = wb.getCategoryMembers("Esperanto - morfemy przedrostkowe‎", 0);
		String[] _suffix = wb.getCategoryMembers("Esperanto - morfemy przyrostkowe", 0);
		String[] _grammatical = wb.getCategoryMembers("Esperanto - końcówki gramatyczne", 0);
		
		Set<String> all = new HashSet<>(Arrays.asList(_all));
		Set<String> normal = new HashSet<>(Arrays.asList(_normal));
		Set<String> prefix = new HashSet<>(Arrays.asList(_prefix));
		Set<String> suffix = new HashSet<>(Arrays.asList(_suffix));
		Set<String> grammatical = new HashSet<>(Arrays.asList(_grammatical));
		
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
			String query = "INSERT INTO morfeo (title, morphem, position, type)"
				+ " VALUES " + String.join(",", values) + ";";
			
			return conn.createStatement().executeUpdate(query);
		} else {
			return 0;
		}
	}
	
	private static void updateTimestampTable(Connection conn) throws SQLException {
		String query = "INSERT INTO execution_log (type)"
			+ " VALUES ('tasks.plwikt.MorfeoDatabase')"
			+ " ON DUPLICATE KEY"
			+ " UPDATE timestamp = NOW();";
		
		conn.createStatement().executeUpdate(query);
	}
}
