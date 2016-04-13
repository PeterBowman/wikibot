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
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
	
	private static final String SQL_EOM_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_eom_backlinks";
	private static final String SQL_COMMON_URI = "jdbc:mysql://tools-db:3306/s52584__plwikt_common";
	
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
		
		System.out.printf("%d morphems retrieved.%n", items.size());
		
		Class.forName("com.mysql.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		
		try (Connection conn = DriverManager.getConnection(SQL_EOM_URI, properties)) {
			updateMorfeoTable(conn, items);
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
	
	private static void updateMorfeoTable(Connection conn, Map<String, List<String>> items) throws SQLException {
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
					
					if (!morphem.equals(extractedMorphem)) {
						rs.updateString("morphem", extractedMorphem);
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
