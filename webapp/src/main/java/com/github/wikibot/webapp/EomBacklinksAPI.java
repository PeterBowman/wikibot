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
import javax.sql.DataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/eom-backlinks/api")
public class EomBacklinksAPI extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int MAX_RES_LENGTH = 50;

    private DataSource dataSource;

    @Override
    public void init() throws ServletException {
        try {
            Context context = (Context) new InitialContext().lookup("java:comp/env");
            dataSource = (DataSource) context.lookup("jdbc/EomBacklinks");
        } catch (NamingException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String searchParam = request.getParameter("search");
        String limitParam = request.getParameter("limit");
        final String emptyResult = "{\"results\":[]}";

        if (searchParam == null || searchParam.isBlank() || searchParam.contains("|")) {
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

        try (Connection conn = dataSource.getConnection()) {
            String query = """
                SELECT morphem
                FROM morfeo
                WHERE morphem COLLATE utf8mb4_general_ci LIKE '%s%%'
                GROUP BY morphem
                ORDER BY COUNT(morphem) DESC
                LIMIT %d;
                """.formatted(searchParam.trim().replace("'", "\\'"), limit);

            ResultSet rs = conn.createStatement().executeQuery(query);
            List<String> results = new ArrayList<>(limit);

            while (rs.next()) {
                String morphem = rs.getString("morphem");
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
