package com.github.wikibot.webapp;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.RequestDispatcher;
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
	private static final String MOVE_LOG_COMMENT = "artykuł należy dopracować";
	private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwiki-sandbox-redirects.jsp";
	private static final Pattern P_MOVE_LOG;
	
	private DataSource dataSource;

	static {
		P_MOVE_LOG = Pattern.compile("^a:([23]):\\{s:9:\"4::target\";s:\\d+:\"(Wikipedysta:(.+))\";s:10:\"5::noredir\";s:1:\"[01]\";(?:s:17:\"associated_rev_id\";i:\\d+;)?\\}$");
	}

	@Override
	public void init() throws ServletException {
		try {
			System.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.apache.naming.java.javaURLContextFactory");
			Context context = (Context) new InitialContext().lookup("java:comp/env");
			dataSource = (DataSource) context.lookup("jdbc/plwiktionary");
		} catch (NamingException | SecurityException e) {
			throw new UnavailableException(e.getMessage());
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		int offset = handleIntParameter(request, "offset", 0);
		
		List<Map<String, String>> results = new ArrayList<>(limit);
		boolean hasNext = false;
		
		try (Connection conn = dataSource.getConnection()) {
			String query = "SELECT"
					+ " CONVERT(log_title USING utf8mb4) AS log_title,"
					+ " CONVERT(log_params USING utf8mb4) AS log_params,"
					+ " log_timestamp"
				+ " FROM plwiki_p.logging"
				+ " WHERE"
					+ " log_type = 'move' AND"
					+ " log_namespace = 0 AND"
					+ " log_comment = '" + MOVE_LOG_COMMENT + "' AND"
					+ " log_params LIKE '%:\\\"Wikipedysta:%'"
				+ " ORDER BY log_id DESC"
				+ " LIMIT " + (limit + 10)
				+ " OFFSET " + offset;
			
			Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			stmt.setFetchSize(Integer.MIN_VALUE);
			ResultSet rs = stmt.executeQuery(query);
			
			int touched = 0;
			
			while (rs.next()) {
				String logTitle = rs.getString("log_title");
				String logParams = rs.getString("log_params");
				String logTimestamp = rs.getString("log_timestamp");
				
				LogData logData = extractSerializedData(logParams);
				
				if (++touched > limit) {
					hasNext = true;
					break;
				}
				
				Map<String, String> info = new HashMap<>();
				info.put("source", logTitle);
				info.put("sourceExists", logData.sourceExists ? "1" : "0");
				info.put("target", logData.targetPage);
				info.put("targetDisplay", logData.targetDisplay);
				info.put("timestamp", logTimestamp);
				
				results.add(info);
			}
		} catch (SQLException e) {
			throw new UnavailableException(e.getMessage());
		}
		
		RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
		request.setAttribute("results", results);
		request.setAttribute("hasNext", hasNext);
		dispatcher.forward(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	private static int handleIntParameter(HttpServletRequest request, String param, final int reference) {
		String paramStr = request.getParameter(param);
		
		try {
			return Math.max(0, Integer.parseInt(paramStr));
		} catch (NumberFormatException e) {
			return reference;
		}
	}
	
	private static LogData extractSerializedData(String serialized) {
		Matcher m = P_MOVE_LOG.matcher(serialized);
		LogData logData = new LogData();
		
		if (m.matches()) {
			String type = m.group(1);
			logData.sourceExists = (type == "3");
			logData.targetPage = m.group(2);
			logData.targetDisplay = m.group(3);
			return logData;
		} else {
			throw new RuntimeException("Błąd odczytu danych: " + serialized);
		}
	}
	
	private static class LogData {
		boolean sourceExists;
		String targetPage;
		String targetDisplay;
	}
}
