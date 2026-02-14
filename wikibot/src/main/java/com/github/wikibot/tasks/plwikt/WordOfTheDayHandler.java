package com.github.wikibot.tasks.plwikt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;

public class WordOfTheDayHandler {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/WordOfTheDayHandler/");
    private static final Path LAST_DATE_FILE_PATH = LOCATION.resolve("last-date.txt");
    private static final String MAIN_PAGE = "Wikisłownik:Strona główna";
    private static final String SCHEDULE_PAGE_FMT = "Wikisłownik:Słowo dnia/Rozkład/%04d/%02d/%02d";
    private static final String SCHEDULE_TEMPLATE = "Słowo dnia";
    private static final String WOTD_TEMPLATE = "słowo dnia";
    private static final String WOTD_TEMPLATE_FMT = "{{%s|%04d|%02d|%02d}}";
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Warsaw");
    private static final int MAX_LOOK_BEHIND_DAYS = 10;
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    public static void main(String[] args) throws Exception {
        final var today = LocalDate.now(ZONE_ID);

        LocalDate refDate;

        if (Files.exists(LAST_DATE_FILE_PATH)) {
            var refDateStr = Files.readString(LAST_DATE_FILE_PATH).trim();
            refDate = LocalDate.parse(refDateStr, DateTimeFormatter.ISO_DATE);
        } else {
            System.out.println("Last date file does not exist, assuming no previous updates.");
            refDate = today.minusDays(MAX_LOOK_BEHIND_DAYS);
        }

        if (refDate.isAfter(today)) {
            throw new IllegalArgumentException("Last date is in the future, aborting.");
        }

        if (!refDate.isBefore(today)) {
            System.out.println("No update needed, last date is not before today.");
            return;
        }

        Login.login(wb);
        wb.purge(false, MAIN_PAGE);

        while (refDate.isBefore(today)) {
            refDate = refDate.plusDays(1);

            var schedulePage = String.format(SCHEDULE_PAGE_FMT, refDate.getYear(), refDate.getMonthValue(), refDate.getDayOfMonth());
            var pcs = wb.getContentOfPages(List.of(schedulePage));

            try {
                System.out.println("Checking schedule page: " + schedulePage);
                processSchedulePage(pcs.get(0).text(), refDate);
                Files.writeString(LAST_DATE_FILE_PATH, refDate.format(DateTimeFormatter.ISO_DATE));
            } catch (IndexOutOfBoundsException e) {
                throw new RuntimeException("Schedule page does not exist: " + schedulePage);
            }
        }
    }

    public static void processSchedulePage(String text, LocalDate refDate) throws Exception {
        var templates = ParseUtils.getTemplates(SCHEDULE_TEMPLATE, text);

        if (templates.size() != 1) {
            throw new RuntimeException("There should be exactly one schedule template");
        }

        var params = ParseUtils.getTemplateParametersWithValue(templates.get(0));
        var title = params.getOrDefault("hasło", "");

        if (title.isBlank()) {
            throw new RuntimeException("Missing or empty entry title parameter in schedule template");
        }

        var pcs = wb.getContentOfPages(List.of(title));

        if (pcs.isEmpty()) {
            throw new RuntimeException("Entry page does not exist: " + title);
        }

        var page = Page.wrap(pcs.get(0));
        var optSection = page.getPolishSection();

        if (optSection.isEmpty()) {
            throw new RuntimeException("Entry page does not have a Polish section: " + title);
        }

        var section = optSection.get();
        var intro = section.getIntro();

        if (!intro.isBlank() && !ParseUtils.getTemplates(WOTD_TEMPLATE, intro).isEmpty()) {
            System.out.println("Entry already has a WOTD template, skipping: " + title);
            return;
        }

        var wotdTemplate = WOTD_TEMPLATE_FMT.formatted(WOTD_TEMPLATE, refDate.getYear(), refDate.getMonthValue(), refDate.getDayOfMonth());
        section.setIntro(wotdTemplate + "\n" + intro);

        wb.edit(title, page.toString(), wotdTemplate, pcs.get(0).timestamp());
    }
}
