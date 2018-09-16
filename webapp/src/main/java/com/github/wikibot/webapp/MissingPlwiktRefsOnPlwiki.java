package com.github.wikibot.webapp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class MissingPlwiktRefsOnPlwiki extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final String LOCATION = "./data/tasks.plwikt/MissingRefsOnPlwiki/";
	private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwikt-missing-plwiki-backlinks.jsp";
	private static final String DATE_FORMAT = "HH:mm, d MMM yyyy (z)";
	
	private static final File fEntries = new File(LOCATION + "entries.xml");
	private static final File fStats = new File(LOCATION + "stats.xml");
	private static final File fTemplates = new File(LOCATION + "templates.xml");
	private static final File fTimestamp = new File(LOCATION + "timestamp.xml");
	private static final File fCtrl = new File(LOCATION + "UPDATED");
	
	private static final List<Entry> entries = new ArrayList<>(0);
	private static final Map<String, Integer> stats = new HashMap<>(0);
	private static final List<String> templates = new ArrayList<>(0);
	private static final Calendar calendar = Calendar.getInstance();
	
	private static final int DEFAULT_LIMIT = 500;
	
	private static XStream xstream;
	
	@Override
	public void init() throws ServletException {
		try {
			xstream = new XStream(new StaxDriver());
			XStream.setupDefaultSecurity(xstream);
			xstream.allowTypes(new Class[] {Entry.class});
			xstream.processAnnotations(Entry.class);
			
			checkCurrentState();
		} catch (IOException e) {
			throw new UnavailableException(e.getMessage());
		}
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		int offset = handleIntParameter(request, "offset", 0);
		
		boolean onlyRedirects = handleIntParameter(request, "onlyredirs", 0) != 0;
		boolean onlyMissing = handleIntParameter(request, "onlymissing", 0) != 0;
		boolean onlyDisambigs = handleIntParameter(request, "onlydisambigs", 0) != 0;
		
		List<Entry> localEntries = new ArrayList<>(0);
		Map<String, Integer> localStats = new HashMap<>(0);
		List<String> localTemplates = new ArrayList<>(0);
		Calendar localCalendar = Calendar.getInstance();
		
		checkCurrentState(localEntries, localStats, localTemplates, localCalendar); // synchronized, returns local copies
		
		if (onlyRedirects) {
			localEntries = localEntries.stream().filter(e -> e.getPlwikiRedir() != null).collect(Collectors.toList());
		}
		
		if (onlyMissing) {
			localEntries = localEntries.stream().filter(Entry::isPlwikiMissing).collect(Collectors.toList());
		}
		
		if (onlyDisambigs) {
			localEntries = localEntries.stream().filter(Entry::isPlwikiDisambig).collect(Collectors.toList());
		}
		
		List<Entry> results = getDataView(localEntries, limit, offset);
		DateFormat sdf = new SimpleDateFormat(DATE_FORMAT, new Locale("pl"));
		sdf.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));
		String timestamp = sdf.format(localCalendar.getTime()); // SimpleDateFormat.format is not thread safe!
		
		if (getInitParameter("API") != null) {
			JSONObject json = new JSONObject();
			json.put("results", new JSONArray(results));
			json.put("total", localEntries.size());
			json.put("stats", new JSONObject(localStats));
			json.put("templates", new JSONArray(localTemplates));
			json.put("timestamp", timestamp);
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Content-Type", "application/json");
			response.getWriter().append(json.toString());
		} else {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			request.setAttribute("results", results);
			request.setAttribute("total", localEntries.size());
			request.setAttribute("stats", localStats);
			request.setAttribute("templates", localTemplates.toArray(new String[localTemplates.size()]));
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
	
	private static void checkCurrentState() throws IOException {
		checkCurrentState(true, null, null, null, null);
	}
	
	private static void checkCurrentState(List<Entry> l, Map<String, Integer> s, List<String> t, Calendar c)
			throws IOException {
		checkCurrentState(false, l, s, t, c);
	}
	
	@SuppressWarnings("unchecked")
	private static synchronized void checkCurrentState(boolean forced, List<Entry> l, Map<String, Integer> s,
			List<String> t, Calendar c) throws IOException {
		if (forced || !fCtrl.exists()) {
			try {
				List<Entry> localEntries;
				
				try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(fEntries))) {
					localEntries = (List<Entry>) xstream.fromXML(bis);
				}
				
				entries.clear();
				entries.addAll(localEntries);
				
				Map<String, Integer> localStats = (Map<String, Integer>) xstream.fromXML(fStats);
				stats.clear();
				stats.putAll(localStats);
				
				Map<String, String> localTemplates = (Map<String, String>) xstream.fromXML(fTemplates);
				templates.clear();
				templates.addAll(localTemplates.keySet());
				
				OffsetDateTime odt = (OffsetDateTime) xstream.fromXML(fTimestamp);
				calendar.setTime(Date.from(odt.toInstant()));
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			fCtrl.createNewFile();
		}
		
		if (l != null && s != null && t != null && c != null) {
			entries.stream().map(Entry::copy).forEach(l::add); // deep copy
			s.putAll(stats);
			t.addAll(templates);
			c.setTime(calendar.getTime());
		}
	}
	
	private static List<Entry> getDataView(List<Entry> list, final int limit, final int offset) {
		try {
			return list.subList(offset, Math.min(list.size(), offset + limit));
		} catch (IndexOutOfBoundsException | IllegalArgumentException e) {
			return Collections.emptyList();
		}
	}
	
	// keep in sync with com.github.wikibot.tasks.plwikt.MissingRefsOnPlwiki
	// must be a nested public class in order to be accessed as a bean in JSP code
	@XStreamAlias("entry")
	public static class Entry {
		@XStreamAlias("plwikt")
		String plwiktTitle;
		
		@XStreamAlias("plwiki")
		String plwikiTitle;
		
		@XStreamAlias("redir")
		String plwikiRedir;
		
		@XStreamAlias("backlinks")
		Map<String, Boolean> plwiktBacklinks;
		
		@XStreamAlias("missing")
		boolean plwikiMissing;
		
		@XStreamAlias("disambig")
		boolean plwikiDisambig;
		
		public String getPlwiktTitle() {
			return plwiktTitle;
		}
		
		public String getPlwikiTitle() {
			return plwikiTitle;
		}
		
		public String getPlwikiRedir() {
			return plwikiRedir;
		}
		
		public Map<String, Boolean> getPlwiktBacklinks() {
			return plwiktBacklinks;
		}
		
		public boolean isPlwikiMissing() {
			return plwikiMissing;
		}
		
		public boolean isPlwikiDisambig() {
			return plwikiDisambig;
		}
		
		Entry copy() {
			Entry entry = new Entry();
			entry.plwiktTitle = plwiktTitle;
			entry.plwikiTitle = plwikiTitle;
			entry.plwikiRedir = plwikiRedir;
			entry.plwikiMissing = plwikiMissing;
			entry.plwikiDisambig = plwikiDisambig;
			
			if (plwiktBacklinks != null) {
				entry.plwiktBacklinks = new TreeMap<>(plwiktBacklinks);
			}
			
			return entry;
		}
	}
}
