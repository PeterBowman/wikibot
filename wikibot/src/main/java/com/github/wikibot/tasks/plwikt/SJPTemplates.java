package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class SJPTemplates {
    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    private static final int SLEEP_MS = 2500;
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/SJPTemplates/");
    private static final String WIKI_PAGE = "Wikipedysta:PBbot/sjp.pl";

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        var titles = wb.whatTranscludesHere(List.of("Szablon:sjp.pl"), Wiki.MAIN_NAMESPACE).get(0);
        var targetRevs = new ArrayList<Wiki.Revision>(titles.size());

        extractRevisions(titles, targetRevs);
        targetRevs.sort(Comparator.comparing(Wiki.Revision::getTimestamp));

        var usernames = targetRevs.stream()
            .map(Wiki.Revision::getUser)
            .distinct()
            .toList();

        var registeredUsers = wb.getUsers(usernames).stream()
            .filter(Objects::nonNull)
            .map(Wiki.User::getUsername)
            .collect(Collectors.toSet());

        if (!checkStoredData(targetRevs)) {
            System.out.println("No changes detected, aborting.");
            return;
        }

        String output = makeTable(targetRevs, registeredUsers);

        wb.setMarkBot(false);
        wb.edit(WIKI_PAGE, output, "aktualizacja");
    }

    private static void extractRevisions(List<String> titles, List<Wiki.Revision> targetRevs)
            throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();

        for (String title : titles) {
            List<Wiki.Revision> revs = wb.getPageHistory(title, null);
            List<Long> revids = revs.stream().map(Wiki.Revision::getID).toList();
            List<PageContainer> pcs = wb.getContentOfRevIds(revids);

            PageContainer page = pcs.stream()
                .sorted(Comparator.comparing(PageContainer::timestamp))
                .filter(pc -> !ParseUtils.getTemplates("sjp.pl", pc.text()).isEmpty())
                .findFirst()
                .orElse(null);

            if (page == null) {
                errors.add(title);
                continue;
            }

            Collections.reverse(pcs);
            int index = pcs.indexOf(page);
            Wiki.Revision targetRev = revs.get(index);

            targetRevs.add(targetRev);
            Thread.sleep(SLEEP_MS);
        }

        if (!errors.isEmpty()) {
            System.out.printf("%d errors: %s%n", errors.size(), errors);
            Files.write(LOCATION.resolve("errors.txt"), errors);
        }
    }

    private static boolean checkStoredData(List<Wiki.Revision> targetRevs) throws IOException {
        Path path = LOCATION.resolve("hashcode.txt");
        int targetHash = targetRevs.hashCode();

        try {
            int storedHash = Integer.parseInt(Files.readString(path));
            return targetHash != storedHash;
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            return true;
        } finally {
            Files.writeString(path, Integer.toString(targetHash));
        }
    }

    private static String makeTable(List<Wiki.Revision> revs, Set<String> registeredUsers) {
        StringBuilder sb = new StringBuilder(revs.size() * 200);

        sb.append("Dyskusja w Barze:").append("\n");
        sb.append("* [[WS:Bar/Archiwum 16#Szablon sjp.pl]]").append("\n");
        sb.append("* [[WS:Bar/Archiwum 18#Szablon sjp.pl (kontynuacja)]]").append("\n");
        sb.append("Aktualizacja: ~~~~~.").append("\n\n");
        sb.append("{{język linków|polski}}").append("\n");
        sb.append("{| class=\"wikitable sortable autonumber\"").append("\n");
        sb.append("|-").append("\n");
        sb.append("! hasło !! autor !! sygnatura czasowa !! opis edycji").append("\n");

        revs.stream()
            .map(rev -> String.format(
                "| [[%s]] || %s || [[Specjalna:Diff/%d|%s]] || %s",
                rev.getTitle(), getUserLink(rev.getUser(), registeredUsers.contains(rev.getUser())), rev.getID(),
                rev.getTimestamp().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                Optional.ofNullable(rev.getComment()).map(s -> String.format("<nowiki>%s</nowiki>", s)).orElse("")
            ))
            .forEach(s -> sb.append("|-\n").append(s).append("\n"));

        sb.append("|}");

        return sb.toString();
    }

    private static String getUserLink(String username, boolean isRegistered) {
        if (isRegistered) {
            return String.format("[[User:%1$s|%1$s]]", username);
        } else {
            return String.format("[[Special:Contribs/%1$s|%1$s]]", username);
        }
    }
}
