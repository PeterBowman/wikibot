package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class SJPTemplates {
	private static PLWikt wb;
	
	private static final int SLEEP_MS = 2500;
	private static final String LOCATION = "./data/tasks.plwikt/SJPTemplates/";
	private static final String WIKI_PAGE = "Wikipedysta:PBbot/sjp.pl";
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		String[] titles = wb.whatTranscludesHere("Szablon:sjp.pl", Wiki.MAIN_NAMESPACE);
		List<Wiki.Revision> targetRevs = new ArrayList<>(titles.length);
		
		extractRevisions(titles, targetRevs);
		targetRevs.sort((rev1, rev2) -> rev1.getTimestamp().compareTo(rev2.getTimestamp()));
		
		if (!checkStoredData(targetRevs)) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		String output = makeTable(targetRevs);
		
		wb.setMarkBot(false);
		wb.edit(WIKI_PAGE, output, "aktualizacja");
	}
	
	private static void extractRevisions(String[] titles, List<Wiki.Revision> targetRevs)
			throws IOException, InterruptedException {
		List<String> errors = new ArrayList<>();
		
		for (String title : titles) {
			Wiki.Revision[] revs = wb.getPageHistory(title);
			Long[] revids = Stream.of(revs).map(Wiki.Revision::getID).toArray(Long[]::new);
			PageContainer[] pcs = wb.getContentOfRevIds(revids);
			
			PageContainer page = Stream.of(pcs)
				.sorted((pc1, pc2) -> pc1.getTimestamp().compareTo(pc2.getTimestamp()))
				.filter(pc -> !ParseUtils.getTemplates("sjp.pl", pc.getText()).isEmpty())
				.findFirst()
				.orElse(null);
			
			if (page == null) {
				errors.add(title);
				continue;
			}
			
			List<PageContainer> temp = Arrays.asList(pcs);
			Collections.reverse(temp);
			int index = temp.indexOf(page);
			Wiki.Revision targetRev = revs[index];
			
			targetRevs.add(targetRev);
			Thread.sleep(SLEEP_MS);
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errors: %s%n", errors.size(), errors);
			Misc.serialize(errors, LOCATION + "errors.ser");
		}
	}
	
	private static boolean checkStoredData(List<Wiki.Revision> targetRevs)
			throws FileNotFoundException, IOException {
		File f = new File(LOCATION + "hashcode.ser");
		int targetHash = targetRevs.hashCode();
		
		try {
			int storedHash = Misc.deserialize(f);
			return targetHash != storedHash;
		} catch (Exception e) {
			System.out.printf("Exception: " + e.getMessage());
			return true;
		} finally {
			Misc.serialize(targetHash, f);
		}
	}
	
	private static String makeTable(List<Wiki.Revision> revs) {
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
			.map(item -> String.format(
				"| [[%s]] || %s || [[Specjalna:Diff/%d|%s]] || %s",
				item.getTitle(), item.getUser(), item.getID(), item.getTimestamp().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				Optional.ofNullable(item.getComment()).map(s -> String.format("<nowiki>%s</nowiki>", s)).orElse("")
			))
			.forEach(s -> sb.append("|-\n").append(s).append("\n"));
		
		sb.append("|}");
		
		return sb.toString();
	}
}
