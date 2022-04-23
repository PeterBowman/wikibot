package com.github.wikibot.tasks.plwikt;

import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.Section;
import com.github.wikibot.utils.Login;

public final class ArchiveThread {
    private static final List<String> TARGET_PAGES = List.of("Wikisłownik:Zgłoś błąd w haśle");
    private static final List<String> TARGET_TEMPLATES = List.of("załatwione");

    private static final Period MIN_PERIOD = Period.ofDays(30);

    private static final Pattern P_HEADER = Pattern.compile("^\\[{2}:?([^]]+?)(?:#[^]]+?)?\\]{2}$");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\(CES?T\\)");

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm, d LLL yyyy (z)").withLocale(new Locale("pl"));

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final OffsetDateTime refDateTime = OffsetDateTime.now().minus(MIN_PERIOD);

    public static void main(String[] args) throws Exception {
        Login.login(wb);
        System.out.printf("Reference date-time: %s%n", refDateTime);

        for (var title : TARGET_PAGES) {
            var rev = wb.getTopRevision(title);
            var text = rev.getText();
            var page = Page.store(title, text);

            if (processPage(page, rev.getID())) {
                wb.edit(title, page.toString(), "archiwizacja", rev.getTimestamp());
            }
        }
    }

    private static boolean processPage(Page page, long revid) {
        var detachedSections = new ArrayList<Section>();

        for (var section : page.getAllSections()) {
            var header = section.getHeader();
            var text = ParseUtils.removeCommentsAndNoWikiText(section.toString());
            var m = P_HEADER.matcher(header);

            if (m.matches() && TARGET_TEMPLATES.stream().anyMatch(template -> !ParseUtils.getTemplates(template, text).isEmpty())) {
                var title = m.toMatchResult().group(1).trim();
                var timestamps = retrieveTimestamps(text);

                if (!timestamps.isEmpty() && timestamps.get(0).isBefore(refDateTime)) {
                    try {
                        if ((Boolean) wb.getPageInfo(List.of(title)).get(0).get("exists")) {
                            var talkPage = wb.getTalkPage(title);
                            var summary = String.format("Przeniesione z [[Specjalna:Niezmienny link/%d#%s|%s]]",
                                    revid, title, page.getTitle());

                            wb.newSection(talkPage, summary, section.getFlattenedContent(), false, true);
                            detachedSections.add(section);
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                        continue;
                    }
                }
            }
        }

        detachedSections.forEach(Section::detach);
        return !detachedSections.isEmpty();
    }

    private static List<OffsetDateTime> retrieveTimestamps(String text) {
        return P_TIMESTAMP.matcher(text).results()
            .map(MatchResult::group)
            .map(group -> {
                try {
                    return ZonedDateTime.parse(group, DT_FORMATTER);
                } catch (DateTimeParseException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .map(ZonedDateTime::toOffsetDateTime)
            .sorted(Comparator.reverseOrder())
            .toList();
    }
}
