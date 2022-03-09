package com.github.wikibot.webapp;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/**
 * Servlet implementation class SandboxRedirects
 */
public class SandboxRedirects extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final int DEFAULT_LIMIT = 100;
	private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwiki-sandbox-redirects.jsp";
	private static final Pattern P_MOVE_LOG;
	
	private DataSource dataSource;

	static {
		P_MOVE_LOG = Pattern.compile("^a:[23]:\\{s:9:\"4::target\";s:\\d+:\"(Wikipedysta:(.+))\";s:10:\"5::noredir\";s:1:\"[01]\";(?:s:17:\"associated_rev_id\";i:\\d+;)?\\}$");
	}

	@Override
	public void init() throws ServletException {
		try {
			var context = (Context)new InitialContext().lookup("java:comp/env");
			dataSource = (DataSource)context.lookup("jdbc/plwiki-web");
		} catch (NamingException | SecurityException e) {
			throw new UnavailableException(e.getMessage());
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		var limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		var offset = handleIntParameter(request, "offset", 0);
		
		var results = new ArrayList<Map<String, Object>>(limit);
		var hasNext = false;
		
		try (var conn = dataSource.getConnection()) {
			var query = String.format("""
				SELECT
					p_source.page_id AS source_id,
					log_title,
					p_target.page_id AS target_id,
					log_params,
					log_timestamp
				FROM plwiki_p.logging
					LEFT JOIN page AS p_source ON
						p_source.page_title = log_title AND
						p_source.page_namespace = log_namespace
					LEFT JOIN page AS p_target ON
						p_target.page_id = log_page
				WHERE
					log_type = 'move' AND
					log_namespace = 0 AND
					log_params LIKE '%%:"Wikipedysta:%%'
				ORDER BY
					log_id DESC
				LIMIT
					%d
				OFFSET
					%d;
				""", limit + 1, offset);
			
			var stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			stmt.setFetchSize(Integer.MIN_VALUE);
			var rs = stmt.executeQuery(query);
			
			var touched = 0;
			
			while (rs.next()) {
				var sourceId = rs.getLong("source_id");
				var logTitle = rs.getString("log_title");
				var targetId = rs.getLong("target_id");
				var logParams = rs.getString("log_params");
				var logTimestamp = rs.getString("log_timestamp");
				
				var logData = extractSerializedData(logParams);
				
				if (++touched > limit) {
					hasNext = true;
					break;
				}
				
				results.add(Map.of(
					"source", logTitle,
					"sourceExists", sourceId != 0L,
					"target", logData.targetPage,
					"targetExists", targetId != 0L && targetId != sourceId,
					"targetDisplay", logData.targetDisplay,
					"timestamp", formatDate(logTimestamp)
				));
			}
		} catch (SQLException e) {
			throw new UnavailableException(e.getMessage());
		}
		
		var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
		request.setAttribute("results", results);
		request.setAttribute("hasNext", hasNext);
		dispatcher.forward(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	private static int handleIntParameter(HttpServletRequest request, String param, final int reference) {
		var paramStr = request.getParameter(param);
		
		try {
			return Math.max(0, Integer.parseInt(paramStr));
		} catch (NumberFormatException e) {
			return reference;
		}
	}
	
	private static LogData extractSerializedData(String serialized) {
		var m = P_MOVE_LOG.matcher(serialized);
		
		if (m.matches()) {
			return new LogData(m.group(1), m.group(2));
		} else {
			throw new RuntimeException("Błąd odczytu danych: " + serialized);
		}
	}
	
	private static Date formatDate(String timestamp) {
		var sdfDate = new SimpleDateFormat("yyyyMMddHHmmss");
		
		try {
			return sdfDate.parse(timestamp);
		} catch (ParseException e) {
			throw new RuntimeException("Nie udało się rozpoznać sygnatury czasowej: " + timestamp);
		}
	}
	
	private record LogData (String targetPage, String targetDisplay) {}
}
