package com.github.wikibot.tasks.plwiki;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class NewPageTagger {
	private static final String LOCATION = "./data/tasks.plwiki/NewPageTagger/";
	private static final String LAST_DATE = LOCATION + "last_date.txt";
	private static final String PICK_DATE = LOCATION + "pick_date.txt";
	private static final String TAG = "page-recreation";
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wikipedia.org");
		
		String startTimestamp = extractTimestamp();
		
		OffsetDateTime earliest = OffsetDateTime.parse(startTimestamp);
		OffsetDateTime latest = OffsetDateTime.now(wb.timezone());
		
		Wiki.RequestHelper rcHelper = wb.new RequestHelper().withinDateRange(earliest, latest);
		
		wb.newPages(rcHelper).stream()
			.filter(Wiki.Revision::isNew)
			.filter(NewPageTagger::hasDeleteActions)
			.forEach(NewPageTagger::applyTag);
		
		storeTimestamp(latest);
	}
	
	private static String extractTimestamp() throws IOException {
		File f_last_date = new File(LAST_DATE);
		File f_pick_date = new File(PICK_DATE);
		
		String startTimestamp;
		
		if (f_last_date.exists()) {
			startTimestamp = Files.readAllLines(Paths.get(LAST_DATE)).get(0);
		} else if (f_pick_date.exists()) {
			startTimestamp = Files.readAllLines(Paths.get(PICK_DATE)).get(0);
		} else {
			throw new UnsupportedOperationException("No timestamp file found.");
		}
		
		if (startTimestamp.isEmpty()) {
			throw new UnsupportedOperationException("No initial timestamp found.");
		}
		
		return startTimestamp;
	}
	
	private static boolean hasDeleteActions(Wiki.Revision rev) {
		try {
			Wiki.RequestHelper logHelper = wb.new RequestHelper().byTitle(rev.getTitle());
			return !wb.getLogEntries(Wiki.DELETION_LOG, "delete", logHelper).isEmpty();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private static void applyTag(Wiki.Revision rev) {
		try {
			Map<String, String> getparams = Map.of("action", "tag", "rcid", Long.toString(rev.getRcid()), "add", TAG);
			Map<String, Object> postparams = Map.of("token", wb.getToken("csrf"));
			wb.makeApiCall(getparams, postparams, "tag");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private static void storeTimestamp(OffsetDateTime timestamp) {
		try {
			Files.write(Paths.get(LAST_DATE), List.of(timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
		} catch (IOException e) {}
	}
}
