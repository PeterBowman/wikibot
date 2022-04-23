package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
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
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginException;

import org.jsoup.Jsoup;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Page;
import com.github.wikibot.parsing.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PluralRules;

public final class ArchiveThread {
    private static final List<String> TARGET_PAGES = List.of("Wikisłownik:Zgłoś błąd w haśle");
    private static final List<String> TARGET_TEMPLATES = List.of("załatwione");

    private static final Period MIN_PERIOD = Period.ofDays(30);

    // from CommentParser::doWikiLinks()
    private static final Pattern P_HEADER_LINK = Pattern.compile("\\[{2}\\s*+:?([^\\[\\]\\|]+)(?:\\|((?:]?[^\\]])*+))?\\]{2}");
    private static final Pattern P_TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}, \\d{1,2} [a-ząćęłńóśźż]{3} \\d{4} \\(CES?T\\)");

    private static final DateTimeFormatter DT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm, d LLL yyyy (z)").withLocale(new Locale("pl"));

    private static final String TALK_HEADER_FORMAT = "Przeniesione z [[Specjalna:Niezmienny link/%d#%s|%s]]";

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
    private static final OffsetDateTime refDateTime = OffsetDateTime.now().minus(MIN_PERIOD);
    private static final Plural SUMMARY_FORMATTER;

    static {
        var threads = new WordForms[] {
            new WordForms(new String[] {"wątek", "wątki", "wątków"})
        };

        SUMMARY_FORMATTER = new Plural(PluralRules.POLISH, threads);
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);
        System.out.printf("Reference date-time: %s%n", refDateTime);

        for (var title : TARGET_PAGES) {
            var rev = wb.getTopRevision(title);
            var page = Page.store(title, rev.getText());
            var numThreads = processPage(page, rev.getID());

            if (numThreads > 0) {
                var summary = String.format("zarchiwizowano %d %s", numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek"));
                wb.edit(title, page.toString(), summary, rev.getTimestamp());
            }
        }
    }

    private static int processPage(Page page, long revid) throws LoginException, IOException {
        var detachedSections = new ArrayList<Section>();
        var archivedSections = new ArrayList<Section>();

        for (var section : page.getAllSections().stream().filter(s -> s.getLevel() == 2).toList()) {
            var text = ParseUtils.removeCommentsAndNoWikiText(section.toString());
            var header = section.getHeader();

            var targets = P_HEADER_LINK.matcher(header).results()
                .map(m -> m.group(1).trim())
                .filter(target -> wb.namespace(target) == Wiki.MAIN_NAMESPACE)
                .distinct()
                .toList();

            var timestamps = retrieveTimestamps(text);

            if (hasDoneTemplate(text) && !timestamps.isEmpty() && timestamps.get(0).isBefore(refDateTime)) {
                var mustArchive = true;

                for (var info : wb.getPageInfo(targets)) {
                    if ((Boolean)info.get("exists")) {
                        var pageName = (String)info.get("pagename");
                        var talkPageName = wb.getTalkPage(pageName);
                        var summary = String.format(TALK_HEADER_FORMAT, revid, pageName, page.getTitle());

                        wb.newSection(talkPageName, summary, section.getFlattenedContent(), false, true);
                        mustArchive = false;
                    }
                }

                if (mustArchive) {
                    archivedSections.add(section);
                }

                detachedSections.add(section);
            }
        }

        if (!archivedSections.isEmpty()) {
            var title = page.getTitle() + "/archiwum";
            var text = Optional.ofNullable(wb.getPageText(List.of(title)).get(0)).orElse("");
            var archive = Page.store(title, text);

            archive.appendSections(archivedSections);

            var numThreads = archivedSections.size();
            var summary = String.format("przeniesiono %d %s do archiwum", numThreads, SUMMARY_FORMATTER.pl(numThreads, "wątek"));

            wb.edit(title, archive.toString(), summary);
        }

        detachedSections.forEach(Section::detach);
        return detachedSections.size();
    }

    private static boolean hasDoneTemplate(String text) {
        var doc = Jsoup.parseBodyFragment(text);
        doc.getElementsByTag("nowiki").remove(); // already removed along with comments, but why not
        doc.getElementsByTag("s").remove();
        doc.getElementsByTag("pre").remove();
        doc.getElementsByTag("syntaxhighlight").remove();
        doc.getElementsByTag("source").remove();

        return TARGET_TEMPLATES.stream().anyMatch(template -> !ParseUtils.getTemplates(template, doc.text()).isEmpty());
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
