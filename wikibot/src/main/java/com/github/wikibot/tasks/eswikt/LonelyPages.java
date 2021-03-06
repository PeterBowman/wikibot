package com.github.wikibot.tasks.eswikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.wikibot.utils.Misc;

public final class LonelyPages {
	private static final Path LOCATION = Paths.get("./data/tasks.eswikt/LonelyPages/");
	private static final Properties defaultSQLProperties = new Properties();	
	private static final String SQL_ESWIKT_URI = "jdbc:mysql://eswiktionary.analytics.db.svc.wikimedia.cloud:3306/eswiktionary_p";
	
	static {
		defaultSQLProperties.setProperty("autoReconnect", "true");
		defaultSQLProperties.setProperty("useUnicode", "yes");
		defaultSQLProperties.setProperty("characterEncoding", StandardCharsets.UTF_8.name());
		defaultSQLProperties.setProperty("sslMode", "DISABLED");
	}
	
	public static void main(String[] args) throws Exception {
		Class.forName("com.mysql.cj.jdbc.Driver");
		Properties properties = prepareSQLProperties();
		List<String> lonelyPages = new ArrayList<>(75000);
		
		try (Connection eswiktConn = DriverManager.getConnection(SQL_ESWIKT_URI, properties)) {
			double start = System.currentTimeMillis();
			fetchLonelyPages(eswiktConn, lonelyPages);
			double elapsed = (System.currentTimeMillis() - start) / 1000;
			System.out.printf("%d titles fetched in %.3f seconds.%n", lonelyPages.size(), elapsed);
		}
		
		storeData(lonelyPages);
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
	
	private static void fetchLonelyPages(Connection conn, List<String> list) throws SQLException {
		String query = "SELECT CONVERT(page_title USING utf8mb4) AS title"
			+ " FROM page"
			+ " LEFT JOIN pagelinks"
			+ " ON pl_namespace = page_namespace"
			+ " AND pl_title = page_title"
			+ " LEFT JOIN templatelinks"
			+ " ON tl_namespace = page_namespace"
			+ " AND tl_title = page_title"
			+ " WHERE pl_namespace IS NULL"
			+ " AND page_namespace = 0"
			+ " AND page_is_redirect = 0"
			+ " AND tl_namespace IS NULL;";
		
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery(query);
		
		while (rs.next()) {
			String title = rs.getString("title").replace("_", " ");
			list.add(title);
		}
	}
	
	private static void storeData(List<String> list) throws FileNotFoundException, IOException, ClassNotFoundException {
		var data = LOCATION.resolve("data.ser");
		var ctrl = LOCATION.resolve("UPDATED");
		var cal = LOCATION.resolve("timestamp.ser");
		
		if (!data.toFile().exists() || list.hashCode() != (int) Misc.deserialize(data).hashCode()) {
			Misc.serialize(list, data);
			Misc.serialize(OffsetDateTime.now(), cal);
			ctrl.toFile().delete();
		}
	}
}
