package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public final class MissingWikiquoteBacklinks implements Selectorizable {
	private static PLWikt wb;
	private static Wikibot quote;
	private static final String location = "./data/scripts.plwikt/MissingWikiquoteBacklinks/";
	private static final String data = location + "data.tsv";
	private static final String worklist = location + "worklist.txt";
	private static final String ser_pages = location + "pages.ser";
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
				quote = Login.retrieveSession(Domains.PLQUOTE, Users.User1);
				getList();
				Login.saveSession(wb);
				Login.saveSession(quote);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User2);
				edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws FailedLoginException, IOException {
		TsvParserSettings settings = new TsvParserSettings();
		settings.setHeaderExtractionEnabled(true);
		TsvParser parser = new TsvParser(settings);
		List<String[]> list = parser.parseAll(new FileReader(new File(data)));
		
		System.out.printf("Tamaño de la lista: %d%n", list.size());
		
		Long[] wiktids = list.stream()
			.map(data -> Long.parseLong(data[0]))
			.toArray(Long[]::new);
		
		String[] quotetitles = list.stream()
			.map(data -> data[1])
			.toArray(String[]::new);
		
		PageContainer[] wiktpages = wb.getContentOfPageIds(wiktids);
		PageContainer[] quotepages = quote.getContentOfPages(quotetitles);
		Map<String, Collection<String>> map = new HashMap<>(list.size());
		
		for (PageContainer wiktpage : wiktpages) {
			Page wp = Page.wrap(wiktpage);
			Section s = wp.getPolishSection();
			
			if (s == null) {
				System.out.printf("Missing polish section: %s%n", wiktpage.getTitle());
				continue;
			}
			
			Field notes = s.getField(FieldTypes.NOTES);
			
			PageContainer qpage = Stream.of(quotepages)
				.filter(page -> page.getTitle().toUpperCase().equals(wiktpage.getTitle().toUpperCase()))
				.findFirst()
				.orElse(null);
			
			if (qpage == null) {
				System.out.printf("Missing wikiquote page: %s%n", wiktpage.getTitle());
				continue;
			}
			
			String notesText = notes.getContent();
			notes.editContent(notesText, true);
			notesText = "{{wikicytaty}}\n" + notesText;
			notesText = notesText.trim();
			notes.editContent(notesText, true);
			
			String[] data = new String[]{
				qpage.getText(),
				wiktpage.getTitle(),
				notes.getContent()
			};
			
			map.put(wiktpage.getTitle(), Arrays.asList(data));
		}
		
		Misc.serialize(wiktpages, ser_pages);
		IOUtils.writeToFile(Misc.makeMultiList(map), worklist);
	}
	
	public static void edit() throws ClassNotFoundException, IOException {
		String[] lines = IOUtils.loadFromFile(worklist, "", "UTF8");
		Map<String, String[]> map = Misc.readMultiList(lines);
		PageContainer[] pages = Misc.deserialize(ser_pages);
		List<String> errors = new ArrayList<>();
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			String[] data = entry.getValue();
			
			PageContainer page = Misc.retrievePage(pages, title);
			Calendar timestamp = page.getTimestamp();
			
			Page p = Page.wrap(page);
			Field notes = p.getPolishSection().getField(FieldTypes.NOTES);
			notes.editContent(data[data.length - 1], true);
			
			String summary = "+ {{wikicytaty}}";
			
			try {
				wb.edit(title, p.toString(), summary, false, true, -2, timestamp);
			} catch (Exception e) {
				System.out.printf("Error en: %s%n", title);
				errors.add(title);
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errores en: %s%n", errors.size(), errors.toString());
		}
		
		(new File(data)).delete();
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new MissingWikiquoteBacklinks());
	}
}
