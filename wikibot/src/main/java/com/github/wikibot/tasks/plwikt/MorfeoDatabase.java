package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
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
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
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
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
	
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
	
	private static PLWikt wb;
	
	static {
		DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
		
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
		
		Class.forName("com.mysql.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		
		Map<String, Byte> morphemInfo;
		
		try (Connection conn = DriverManager.getConnection(SQL_PLWIKT_URI, properties)) {
			morphemInfo = findMissingPages(conn, morphems);
		} catch (SQLException e) {
			morphemInfo = checkMissingPagesFallback(morphems);
		}
		
		inspectEsperantoCategories(morphemInfo);
		
		try (Connection conn = DriverManager.getConnection(SQL_EOM_URI, properties)) {
			updateMorfeoTable(conn, items, morphemInfo);
		}
		
		try (Connection conn = DriverManager.getConnection(SQL_COMMON_URI, properties)) {
			updateTimestampTable(conn);
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
	
	private static Map<String, Byte> findMissingPages(Connection conn, String[] morphems) throws SQLException {
		String values = Stream.of(morphems)
			.map(morphem -> String.format("'%s'", morphem.replace("'", "\\'")))
			.collect(Collectors.joining(", "));
		
		String query = "SELECT page_title"
			+ " FROM page"
			+ " WHERE page_namespace = 0"
			+ " AND page_title IN (" + values + ");";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		Set<String> set = new HashSet<>(morphems.length);
		
		while (rs.next()) {
			String title = rs.getString("page_title");
			set.add(title);
		}
		
		System.out.printf("%d out of %d pages found in plwiktionary_p database.", set.size(), morphems.length);
		
		return Stream.of(morphems).collect(Collectors.toMap(
			morphem -> morphem,
			morphem -> set.contains(morphem) ? null : MORPHEM_RED_LINK
		));
	}
	
	private static Map<String, Byte> checkMissingPagesFallback(String[] morphems) throws IOException {
		@SuppressWarnings("unchecked")
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
	
	private static List<String> parseMorfeoTemplatesFromField(Field f) {
		List<String> templates = ParseUtils.getTemplates("morfeo", f.getContent());
		
		if (templates.isEmpty()) {
			return null;
		}
		
		String template = templates.get(0);
		
		Map<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
		params.remove("templateName");
		
		Collection<String> morphems = params.values();
		morphems.removeIf(String::isEmpty);
		
		return new ArrayList<>(morphems);
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
	
	private static void updateMorfeoTable(Connection conn, Map<String, List<String>> items,
			Map<String, Byte> morphemInfo) throws SQLException {
		Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
		ResultSet rs = stmt.executeQuery("SELECT * FROM morfeo;");
		List<String> storedTitles = new ArrayList<>(items.size());
		
		int added = 0;
		int updated = 0;
		int deleted = 0;
		
		while (rs.next()) {
			String title = rs.getString("title");
			String morphem = rs.getString("morphem");
			int position = rs.getInt("position");
			byte type = rs.getByte("type");
			
			if (!items.containsKey(title)) {
				rs.deleteRow();
				deleted++;
			} else {
				List<String> morphems = items.get(title);
				
				if (position > morphems.size()) {
					rs.deleteRow();
					deleted++;
				} else {
					String extractedMorphem = morphems.set(position - 1, null);
					byte morphemType = morphemInfo.get(morphem);
					boolean updatedRow = false;
					
					if (!morphem.equals(extractedMorphem)) {
						rs.updateString("morphem", extractedMorphem);
						updatedRow = true;
					}
					
					if (type != morphemType) {
						rs.updateByte("type", morphemType);
						updatedRow = true;
					}
					
					if (updatedRow) {
						rs.updateRow();
						updated++;
					}
					
					storedTitles.add(title);
				}
			}
		}
		
		items.values().removeIf(morphems -> morphems.stream().allMatch(Objects::isNull));
		rs.moveToInsertRow();
		
		for (Map.Entry<String, List<String>> entry : items.entrySet()) {
			String title = entry.getKey();
			List<String> morphems = entry.getValue();
			
			for (int i = 0; i < morphems.size(); i++) {
				String morphem = morphems.get(i);
				
				if (morphem == null) {
					continue;
				}
				
				rs.updateString("title", title);
				rs.updateString("morphem", morphem);
				rs.updateInt("position", i + 1);
				rs.updateByte("type", morphemInfo.get(morphem));
				rs.insertRow();
				added++;
			}
		}
		
		System.out.printf("Added: %d, updated: %d, deleted: %d.%n", added, updated, deleted);
	}
	
	private static void updateTimestampTable(Connection conn) throws SQLException {
		Calendar cal = Calendar.getInstance();
		String timestamp = DATE_FORMAT.format(cal.getTime());
		
		String query = "INSERT INTO execution_log (type, timestamp)"
			+ " VALUES ('tasks.plwikt.MorfeoDatabase', " + timestamp + ")"
			+ " ON DUPLICATE KEY"
			+ " UPDATE timestamp = VALUES(timestamp);";
		
		conn.createStatement().executeUpdate(query);
	}
}
