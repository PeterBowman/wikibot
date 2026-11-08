package com.github.wikibot.webapp;

import java.nio.file.Paths;
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

import org.json.JSONArray;
import org.json.JSONObject;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MonitorTalkPages extends HttpServlet {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MonitorTalkPages/");
    private static final String JSP_DISPATCH_TARGET = "/jsp/monitor-talk-pages.jsp";
    private static final String DATE_FORMAT = "HH:mm, d MMM yyyy (z)";

    private static final List<Entry> entries = new ArrayList<>(0);
    private static final Map<String, Integer> stats = new HashMap<>(0);
    private static final Map<String, List<String>> langSections = new HashMap<>(0);
    private static final Calendar calendar = Calendar.getInstance();

    private static final int DEFAULT_LIMIT = 500;

    private static XStream xstream;

    @Override
    public void init() throws ServletException {
        try {
            xstream = new XStream(new StaxDriver());
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

        var localEntries = new ArrayList<Entry>(0);
        var localStats = new HashMap<String, Integer>(0);
        var localLangSections = new HashMap<String, List<String>>(0);
        var localCalendar = Calendar.getInstance();

        // synchronized, returns local copies
        checkCurrentState(false, localEntries, localStats, localLangSections, localCalendar);

        var results = getDataView(localEntries, limit, offset);
        var sdf = new SimpleDateFormat(DATE_FORMAT, Locale.forLanguageTag("pl"));
        sdf.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));
        var timestamp = sdf.format(localCalendar.getTime()); // SimpleDateFormat.format is not thread safe!

        if (getInitParameter("API") != null) {
            var json = new JSONObject();
            json.put("results", new JSONArray(results));
            json.put("total", localEntries.size());
            json.put("stats", new JSONObject(localStats));
            json.put("templates", new JSONObject(localLangSections));
            json.put("timestamp", timestamp);

            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Content-Type", "application/json");
            response.getWriter().append(json.toString());
        } else {
            var dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
            request.setAttribute("results", results);
            request.setAttribute("total", localEntries.size());
            request.setAttribute("stats", localStats);
            request.setAttribute("templates", localLangSections);
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

    @SuppressWarnings("unchecked")
    private static synchronized void checkCurrentState(
        boolean forced, List<Entry> l, Map<String, Integer> s, Map<String, List<String>> t, Calendar c)
    throws IOException {
        final Path fEntries = LOCATION.resolve("entries.xml");
        final Path fStats = LOCATION.resolve("stats.xml");
        final Path fLangSections = LOCATION.resolve("langSections.xml");
        final Path fTimestamp = LOCATION.resolve("timestamp.xml");
        final Path fCtrl = LOCATION.resolve("UPDATED");

        if (forced || !Files.exists(fCtrl)) {
            try {
                List<Entry> localEntries;

                try (var bis = new BufferedInputStream(Files.newInputStream(fEntries))) {
                    localEntries = (List<Entry>) xstream.fromXML(bis);
                }

                entries.clear();
                entries.addAll(localEntries);

                var localStats = (Map<String, Integer>) xstream.fromXML(fStats.toFile());
                stats.clear();
                stats.putAll(localStats);

                var localLangSections = (Map<String, List<String>>) xstream.fromXML(fLangSections.toFile());
                langSections.clear();
                langSections.putAll(localLangSections);

                var odt = (OffsetDateTime) xstream.fromXML(fTimestamp.toFile());
                calendar.setTime(Date.from(odt.toInstant()));
            } catch (Exception e) {
                throw new IOException(e);
            }

            try {
                Files.createFile(fCtrl);
            } catch (FileAlreadyExistsException e) {}
        }

        if (l != null && s != null && t != null && c != null) {
            entries.stream().map(Entry::copy).forEach(l::add); // deep copy
            s.putAll(stats);
            t.putAll(langSections);
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

    // keep in sync with com.github.wikibot.tasks.plwikt.MonitorTalkPages
    // must be a nested public class in order to be accessed as a bean in JSP code
    @XStreamAlias("entry")
    public static class Entry {
        @XStreamAlias("pagename")
        String talkPage;

        @XStreamAlias("thread")
        String threadTitle;

        @XStreamAlias("state")
        String threadState;

        @XStreamAlias("timestamp")
        OffsetDateTime lastTimestamp;

        @XStreamAlias("orphaned")
        boolean isOrphaned;

        public String getTalkPage() {
            return talkPage;
        }

        public String getThreadTitle() {
            return threadTitle;
        }

        public String getThreadState() {
            return threadState;
        }

        public OffsetDateTime getLastTimestamp() {
            return lastTimestamp;
        }

        public boolean isOrphaned() {
            return isOrphaned;
        }

        Entry copy() {
            var entry = new Entry();
            entry.talkPage = talkPage;
            entry.threadTitle = threadTitle;
            entry.threadState = threadState;
            entry.lastTimestamp = lastTimestamp;
            entry.isOrphaned = isOrphaned;
            return entry;
        }
    }
}
