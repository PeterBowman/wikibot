package com.github.wikibot.tasks.plwiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class SandboxRepublishTagger {
    private static final Path LOCATION = Paths.get("./data/tasks.plwiki/SandboxRepublishTagger/");
    private static final Path LAST_DATE = LOCATION.resolve("last_date.txt");
    private static final Path PICK_DATE = LOCATION.resolve("pick_date.txt");
    private static final String TAG = "sandbox-republish";

    private static final Wikibot wb = Wikibot.newSession("pl.wikipedia.org");

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var startTimestamp = extractTimestamp();
        var earliest = OffsetDateTime.parse(startTimestamp);
        var latest = OffsetDateTime.now(wb.timezone());
        var helper = wb.new RequestHelper().withinDateRange(earliest, latest).inNamespaces(Wiki.USER_NAMESPACE);

        wb.getLogEntries(Wiki.MOVE_LOG, null, helper).stream()
            .filter(log -> wb.namespace(log.getDetails().get("target_title")) == Wiki.MAIN_NAMESPACE)
            .filter(SandboxRepublishTagger::wasPreviouslyPublished)
            .forEach(SandboxRepublishTagger::applyTag);

        storeTimestamp(latest);
    }

    private static String extractTimestamp() throws IOException {
        String startTimestamp;

        if (Files.exists(LAST_DATE)) {
            startTimestamp = Files.readAllLines(LAST_DATE).get(0);
        } else if (Files.exists(PICK_DATE)) {
            startTimestamp = Files.readAllLines(PICK_DATE).get(0);
        } else {
            throw new UnsupportedOperationException("No timestamp file found.");
        }

        if (startTimestamp.isEmpty()) {
            throw new UnsupportedOperationException("No initial timestamp found.");
        }

        return startTimestamp;
    }

    private static boolean wasPreviouslyPublished(Wiki.LogEntry log) {
        try {
            var article = log.getDetails().get("target_title");
            var helper = wb.new RequestHelper().byTitle(article).withinDateRange(null, log.getTimestamp());

            return wb.getLogEntries(Wiki.MOVE_LOG, null, helper).stream()
                .anyMatch(le -> le.getDetails().get("target_title").equals(log.getTitle()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void applyTag(Wiki.LogEntry log) {
        try {
            var getparams = Map.of("action", "tag", "logid", Long.toString(log.getID()), "add", TAG);
            var postparams = Map.of("token", (Object)wb.getToken("csrf"));
            wb.makeApiCall(getparams, postparams, "tag");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void storeTimestamp(OffsetDateTime timestamp) {
        try {
            Files.write(LAST_DATE, List.of(timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        } catch (IOException e) {}
    }
}
