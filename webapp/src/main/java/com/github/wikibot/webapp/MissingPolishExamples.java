package com.github.wikibot.webapp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

/**
 * Servlet implementation class MissingPolishExamples
 */
public class MissingPolishExamples extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishExamples/");
	private static final String JSP_DISPATCH_TARGET = "/WEB-INF/includes/weblists/plwikt-missing-polish-examples.jsp";
	private static final String DATE_TIME_FORMAT = "HH:mm, d MMM yyyy (z)";
	private static final String DATE_FORMAT = "d MMM yyyy";
	
	private static final Path fEntries = LOCATION.resolve("entries.xml");
	private static final Path fDumpTimestamp = LOCATION.resolve("dump-timestamp.xml");
	private static final Path fBotTimestamp = LOCATION.resolve("bot-timestamp.xml");
	private static final Path fCtrl = LOCATION.resolve("UPDATED");
	
	private static final List<Entry> entries = new ArrayList<>(0);
	private static final Calendar calDump = Calendar.getInstance();
	private static final Calendar calBot = Calendar.getInstance();
	
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
    
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int limit = handleIntParameter(request, "limit", DEFAULT_LIMIT);
		int offset = handleIntParameter(request, "offset", 0);
		
		List<Entry> localEntries = new ArrayList<>(0);
		Calendar localDumpCalendar = Calendar.getInstance();
		Calendar localBotCalendar = Calendar.getInstance();
		
		checkCurrentState(localEntries, localDumpCalendar, localBotCalendar); // synchronized, returns local copies
		
		List<Entry> results = getDataView(localEntries, limit, offset);
		
		DateFormat sdfDump = new SimpleDateFormat(DATE_FORMAT, new Locale("pl"));
		sdfDump.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));
		
		DateFormat sdfBot = new SimpleDateFormat(DATE_TIME_FORMAT, new Locale("pl"));
		sdfBot.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));
		
		String dumpTimestamp = sdfDump.format(localDumpCalendar.getTime()); // SimpleDateFormat.format is not thread safe!
		String botTimestamp = sdfBot.format(localBotCalendar.getTime()); // SimpleDateFormat.format is not thread safe!
		
		if (getInitParameter("API") != null) {
			JSONObject json = new JSONObject();
			json.put("results", new JSONArray(results));
			json.put("total", localEntries.size());
			json.put("dumptimestamp", dumpTimestamp);
			json.put("bottimestamp", botTimestamp);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setHeader("Content-Type", "application/json");
			response.getWriter().append(json.toString());
		} else {
			RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
			request.setAttribute("results", results);
			request.setAttribute("total", localEntries.size());
			request.setAttribute("dumptimestamp", dumpTimestamp);
			request.setAttribute("bottimestamp", botTimestamp);
			dispatcher.forward(request, response);
		}
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
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
		checkCurrentState(true, null, null, null);
	}
	
	private static void checkCurrentState(List<Entry> l, Calendar cd, Calendar cb) throws IOException {
		checkCurrentState(false, l, cd, cb);
	}
	
	@SuppressWarnings("unchecked")
	private static synchronized void checkCurrentState(boolean forced, List<Entry> l, Calendar cd, Calendar cb) throws IOException {
		if (forced || !Files.exists(fCtrl)) {
			try {
				List<Entry> localEntries;
				
				try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(fEntries))) {
					localEntries = (List<Entry>) xstream.fromXML(bis);
				}
				
				entries.clear();
				entries.addAll(localEntries);
				
				LocalDate ld = (LocalDate) xstream.fromXML(fDumpTimestamp.toFile());
				calDump.setTime(Date.from(ld.atStartOfDay().atZone(ZoneOffset.UTC).toInstant()));
				
				OffsetDateTime odt = (OffsetDateTime) xstream.fromXML(fBotTimestamp.toFile());
				calBot.setTime(Date.from(odt.toInstant()));
			} catch (Exception e) {
				throw new IOException(e);
			}
			
			try {
				Files.createFile(fCtrl);
			} catch (FileAlreadyExistsException e) {}
		}
		
		if (l != null && cd != null && cb != null) {
			entries.stream().map(Entry::copy).forEach(l::add); // deep copy
			cd.setTime(calDump.getTime());
			cb.setTime(calBot.getTime());
		}
	}
	
	private static List<Entry> getDataView(List<Entry> list, final int limit, final int offset) {
		try {
			return list.subList(offset, Math.min(list.size(), offset + limit));
		} catch (IndexOutOfBoundsException | IllegalArgumentException e) {
			return Collections.emptyList();
		}
	}
	
	// keep in sync with com.github.wikibot.tasks.plwikt.MissingPolishExamples
	// must be a nested public class in order to be accessed as a bean in JSP code
	@XStreamAlias("entry")
	public static class Entry {
		@XStreamAlias("t")
		String title;
		
		@XStreamAlias("blt")
		List<String> backlinkTitles;
		
		@XStreamAlias("bls")
		List<String> backlinkSections;
		
		Entry copy() {
			Entry entry = new Entry();
			entry.title = title;
			entry.backlinkTitles = new ArrayList<>(backlinkTitles);
			entry.backlinkSections = new ArrayList<>(backlinkSections);
			return entry;
		}
		
		public String getTitle() {
			return title;
		}
		
		public List<String> getBacklinkTitles() {
			return backlinkTitles;
		}
		
		public List<String> getBacklinkSections() {
			return backlinkSections;
		}
		
		public int getBacklinks() {
			return backlinkTitles.size();
		}
	}
}
