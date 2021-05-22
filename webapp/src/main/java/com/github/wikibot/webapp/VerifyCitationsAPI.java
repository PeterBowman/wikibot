package com.github.wikibot.webapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.json.JSONArray;
import org.json.JSONObject;

public class VerifyCitationsAPI extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final int MAX_RES_LENGTH = 50;
	
	private DataSource dataSource;
	
	@Override
	public void init() throws ServletException {
		try {
			Context context = (Context) new InitialContext().lookup("java:comp/env");
			dataSource = (DataSource) context.lookup("jdbc/VerifyCitations");
		} catch (NamingException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String searchParam = request.getParameter("search");
		String typeParam = request.getParameter("type");
		String limitParam = request.getParameter("limit");
		final String emptyResult = "{\"results\":[]}";
		
		if (
			typeParam == null || (!typeParam.trim().equals("user") && !typeParam.trim().equals("title")) ||
			searchParam == null || searchParam.isBlank() || searchParam.contains("|")
		) {
			response.getWriter().append(emptyResult);
			return;
		}
		
		int limit;
		
		try {
			limit = Math.min(MAX_RES_LENGTH, Math.max(0, Integer.parseInt(limitParam.trim())));
		} catch (NumberFormatException | NullPointerException e) {
			limit = MAX_RES_LENGTH;
		}
		
		if (limit == 0) {
			response.getWriter().append(emptyResult);
			return;
		}
		
		String query;
		
		switch (typeParam) {
			case "user":
				query = "SELECT user AS result"
					+ " FROM ("
						+ " SELECT user"
						+ " FROM change_log"
						+ " WHERE user NOT LIKE '@%'"
						+ " UNION"
						+ " SELECT user"
						+ " FROM review_log"
					+ ") AS derived"
					+ " WHERE user";
				break;
			case "title":
				query = "SELECT page_title AS result"
					+ " FROM page_title"
					+ " WHERE page_title.page_title";
				break;
			default:
				response.getWriter().append(emptyResult);
				return;
		}
		
		query += " COLLATE utf8mb4_general_ci";
		query += " LIKE '" + searchParam.trim().replace("'", "\\'") + "%'";
		query += " LIMIT " + limit;
		
		try (Connection conn = dataSource.getConnection()) {
			ResultSet rs = conn.createStatement().executeQuery(query);
			List<String> results = new ArrayList<>(limit);
			
			while (rs.next()) {
				String morphem = rs.getString("result");
				results.add(morphem);
			}
			
			JSONObject json = new JSONObject();
			JSONArray ja = new JSONArray(results);
			json.put("results", ja);
			
			response.setContentType("application/json; charset=UTF-8");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().append(json.toString());
		} catch (SQLException e) {
			response.getWriter().append(emptyResult);
			return;
		}
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
}
