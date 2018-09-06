package com.github.wikibot.webapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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
	private static final String DATE_FORMAT = "HH:mm, d MMM yyyy (z)";
	
	private static final File fData = new File(LOCATION + "data.ser");
	private static final File fCtrl = new File(LOCATION + "UPDATED");
	private static final File fCal = new File(LOCATION + "timestamp.ser");
	
	private static final List<String> storage = new ArrayList<>(0);
	private static final Calendar calendar = Calendar.getInstance();
	
	private static final int DEFAULT_LIMIT = 500;
	
	@Override
	public void init() throws ServletException {
		try {
			checkCurrentState(true, null, null);
		} catch (IOException e) {
			throw new UnavailableException(e.getMessage());
		}
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		int offset = handleIntParameter(request, "offset", 0);
		
		List<String> localStorage = new ArrayList<>(0);
		Calendar localCalendar = Calendar.getInstance();
		
		checkCurrentState(false, localStorage, localCalendar); // synchronized, returns local copies
		
		List<String> results = getDataView(localStorage, limit, offset);
		DateFormat sdf = new SimpleDateFormat(DATE_FORMAT, new Locale("es"));
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		String timestamp = sdf.format(localCalendar.getTime()); // SimpleDateFormat.format is not thread safe!
		
		if (getInitParameter("API") != null) {
			JSONObject json = new JSONObject();
			json.put("results", new JSONArray(results));
			json.put("total", localStorage.size());
			json.put("timestamp", timestamp);
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Content-Type", "application/json");
			response.getWriter().append(json.toString());
		} else {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			request.setAttribute("results", results);
			request.setAttribute("total", localStorage.size());
			request.setAttribute("timestamp", timestamp);
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
	
	private static synchronized void checkCurrentState(boolean forced, List<String> l, Calendar c) throws IOException {
		if (forced || !fCtrl.exists()) {
			try {
				List<String> list = deserialize(fData);
				storage.clear();
				storage.addAll(list);
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			try {
				OffsetDateTime odt = deserialize(fCal);
				calendar.setTime(Date.from(odt.toInstant()));
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			fCtrl.createNewFile();
		}
		
		if (l != null && c != null) {
			l.addAll(storage);
			c.setTime(calendar.getTime());
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T> T deserialize(File f) throws IOException, ClassNotFoundException {
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
			return (T) in.readObject();
		}
	}
	
	private static List<String> getDataView(List<String> list, final int limit, final int offset) {
		try {
			return list.subList(offset, Math.min(list.size(), offset + limit));
		} catch (IndexOutOfBoundsException | IllegalArgumentException e) {
			return Collections.emptyList();
		}
	}
}
