package com.github.wikibot.webapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class LonelyPages extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final String LOCATION = "./data/tasks.eswikt/LonelyPages/";
	private static final String JSP_DISPATCH_TARGET = "/jsp/lonely-pages.jsp";
	
	private static final File fData = new File(LOCATION + "data.ser");
	private static final File fCtrl = new File(LOCATION + "UPDATED");
	private static final File fCal = new File(LOCATION + "timestamp.ser");
	
	private static final List<String> storage = new ArrayList<>(0);
	private static final Calendar calendar = Calendar.getInstance();
	
	private static final int DEFAULT_LIMIT = 500;
	
	@Override
	public void init() throws ServletException {
		try {
			checkCurrentState(true);
		} catch (IOException e) {
			throw new UnavailableException(e.getMessage());
		}
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		int offset = handleIntParameter(request, "offset", 0);
		
		checkCurrentState(false);
		
		List<String> results = getDataView(limit, offset);
		
		if (getInitParameter("API") != null) {
			JSONObject json = new JSONObject();
			json.put("results", new JSONArray(results));
			json.put("total", storage.size());
			json.put("timestamp", calendar.getTime());
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Content-Type", "application/json");
			response.getWriter().append(json.toString());
		} else {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			request.setAttribute("results", results);
			request.setAttribute("total", storage.size());
			request.setAttribute("timestamp", calendar.getTime());
			dispatcher.forward(request, response);
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	
	private static int handleIntParameter(HttpServletRequest request, String param, final int reference) {
		String paramStr = request.getParameter(param);
		
		try {
			return Integer.parseInt(paramStr);
		} catch (NumberFormatException e) {
			return reference;
		}
	}
	
	private static synchronized void checkCurrentState(boolean firstCall) throws IOException {
		if (firstCall || !fCtrl.exists()) {
			try {
				List<String> list = deserialize(fData);
				storage.clear();
				storage.addAll(list);
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			try {
				Calendar tempCal = deserialize(fCal);
				calendar.setTime(tempCal.getTime());
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			fCtrl.createNewFile();
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T> T deserialize(File f) throws IOException, ClassNotFoundException {
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
			return (T) in.readObject();
		}
	}
	
	private static List<String> getDataView(final int limit, final int offset) {
		try {
			return storage.subList(offset, Math.min(storage.size(), offset + limit));
		} catch (IndexOutOfBoundsException | IllegalArgumentException e) {
			return Collections.emptyList();
		}
	}
}
