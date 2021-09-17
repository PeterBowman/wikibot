package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class GreetNewEditors {
	private static final Path LOCATION = Paths.get("./data/tasks.plwiki/GreetNewEditors/");
	private static final Path LAST_DATE = LOCATION.resolve("last_date.txt");
	private static final Path PICK_DATE = LOCATION.resolve("pick_date.txt");
	private static final String GREET_TEMPLATE = "Witaj redaktorze";
	private static final int DEFAULT_LAST_DAYS = 1;

	private static final String TEMPLATE_TEXT;
	private static Wikibot wb;
	
	static {
		TEMPLATE_TEXT = String.format(
				"{{subst:%s}}\n<span style=\"font-size:90%%\">Ten komunikat został wysłany automatycznie przez bota ~~~~</span>",
				GREET_TEMPLATE);
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wikipedia.org");
		
		String startTimestamp = extractTimestamp();
		
		OffsetDateTime earliest = OffsetDateTime.parse(startTimestamp);
		OffsetDateTime latest = OffsetDateTime.now(wb.timezone());
		
		var helper = wb.new RequestHelper().withinDateRange(earliest, latest).reverse(true);
		
		var usernames = wb.getLogEntries(Wiki.USER_RIGHTS_LOG, null, helper).stream()
			.filter(GreetNewEditors::selectNewEditors)
			.map(Wiki.LogEntry::getTitle)
			.filter(Objects::nonNull) // user was revdeleted?
			.map(username -> wb.removeNamespace(username, Wiki.USER_NAMESPACE))
			.distinct()
			.toList();
		
		if (usernames.isEmpty()) {
			System.out.println("No new editors detected, aborting.");
			storeTimestamp(latest);
			return;
		}
		
		var talkPages = wb.getUsers(usernames).stream()
			.filter(Objects::nonNull) // user does not exist (why?)
			.filter(user -> user.getGroups().contains("editor"))
			.filter(user -> !user.getGroups().contains("bot"))
			.map(Wiki.User::getUsername)
			.map(username -> String.format("%s:%s", wb.namespaceIdentifier(Wiki.USER_TALK_NAMESPACE), username))
			.toList();
		
		var infos = wb.getPageInfo(talkPages);
		
		for (int i = 0; i < talkPages.size(); i++) {
			var talkPage = talkPages.get(i);
			
			if ((Boolean)infos.get(i).get("redirect")) {
				System.out.printf("%s is a redirect.%n", talkPage);
				continue;
			}
			
			var pageText = wb.getPageText(List.of(talkPage)).get(0);
			
			if (pageText != null && !pageText.isBlank())
			{
				if (testAlreadyGreeted(pageText)) {
					System.out.printf("Already greeted in %s.%n", talkPage);
					continue;
				}
				
				pageText += "\n\n" + TEMPLATE_TEXT;
			} else {
				pageText = TEMPLATE_TEXT;
			}
			
			try {
				wb.edit(talkPage, pageText, "automatyczne powitanie edytora");
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		
		storeTimestamp(latest);
	}
	
	private static boolean selectNewEditors(Wiki.LogEntry log) {
		var details = log.getDetails();
		var oldGroups = Arrays.asList(details.get("oldgroups").split(","));
		var newGroups = Arrays.asList(details.get("newgroups").split(","));
		return !oldGroups.contains("editor") && newGroups.contains("editor");
	}
	
	private static boolean testAlreadyGreeted(String text) {
		return text.contains("<!-- Szablon:Witaj redaktorze -->") || !ParseUtils.getTemplatesIgnoreCase(GREET_TEMPLATE, text).isEmpty();
	}
	
	private static String extractTimestamp() throws IOException {
		String startTimestamp;
		
		if (Files.exists(LAST_DATE)) {
			startTimestamp = Files.readString(LAST_DATE).trim();
		} else if (Files.exists(PICK_DATE)) {
			startTimestamp = Files.readString(PICK_DATE).trim();
		} else {
			System.out.printf("No timestamp file found, picking last %d days.", DEFAULT_LAST_DAYS);
			var yesterday = OffsetDateTime.now(wb.timezone()).minusDays(DEFAULT_LAST_DAYS);
			startTimestamp = yesterday.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		}
		
		if (startTimestamp.isEmpty()) {
			throw new UnsupportedOperationException("No initial timestamp found.");
		}
		
		return startTimestamp;
	}
	
	private static void storeTimestamp(OffsetDateTime timestamp) throws IOException {
		Files.writeString(LAST_DATE, timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
	}
}
