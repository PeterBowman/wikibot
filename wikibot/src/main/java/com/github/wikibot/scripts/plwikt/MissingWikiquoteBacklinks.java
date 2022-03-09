package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.security.auth.login.FailedLoginException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public final class MissingWikiquoteBacklinks {
	private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
	private static final Wikibot quote = Wikibot.newSession("pl.wikiquote.org");
	private static final Path LOCATION = Paths.get("./data/scripts.plwikt/MissingWikiquoteBacklinks/");
	private static final Path DATA = LOCATION.resolve("data.tsv");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path PAGES_SER = LOCATION.resolve("pages.xml");
	
	private static void selector(char op) throws Exception {
		switch (op) {
			case '1':
				Login.login(wb);
				Login.login(quote);
				getList();
				break;
			case 'e':
				Login.login(wb);
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws FailedLoginException, IOException {
		TsvParserSettings settings = new TsvParserSettings();
		settings.setHeaderExtractionEnabled(true);
		TsvParser parser = new TsvParser(settings);
		List<String[]> list = parser.parseAll(Files.newBufferedReader(DATA));
		
		System.out.printf("Tamaño de la lista: %d%n", list.size());
		
		List<Long> wiktids = list.stream().map(data -> Long.parseLong(data[0])).toList();
		List<String> quotetitles = list.stream().map(data -> data[1]).toList();
		
		List<PageContainer> wiktpages = wb.getContentOfPageIds(wiktids);
		List<PageContainer> quotepages = quote.getContentOfPages(quotetitles);
		Map<String, Collection<String>> map = new HashMap<>(list.size());
		
		for (PageContainer wiktpage : wiktpages) {
			Page wp = Page.wrap(wiktpage);
			Section s = wp.getPolishSection().orElse(null);
			
			if (s == null) {
				System.out.printf("Missing polish section: %s%n", wiktpage.getTitle());
				continue;
			}
			
			Field notes = s.getField(FieldTypes.NOTES).get();
			
			PageContainer qpage = quotepages.stream()
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
		
		Files.writeString(PAGES_SER, new XStream().toXML(wiktpages));
		Files.write(WORKLIST, List.of(Misc.makeMultiList(map)));
	}
	
	public static void edit() throws IOException {
		Map<String, String[]> map = Misc.readMultiList(Files.readString(WORKLIST));
		@SuppressWarnings("unchecked")
		var pages = (List<PageContainer>) new XStream().fromXML(PAGES_SER.toFile());
		List<String> errors = new ArrayList<>();
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			String[] data = entry.getValue();
			
			PageContainer page = pages.stream().filter(p -> p.getTitle().equals(title)).findAny().orElse(null);
			OffsetDateTime timestamp = page.getTimestamp();
			
			Page p = Page.wrap(page);
			
			Optional.of(p)
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.NOTES))
				.ifPresent(f -> f.editContent(data[data.length - 1], true));
			
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
		
		Files.deleteIfExists(DATA);
	}
	
	public static void main(String[] args) throws Exception {
		System.out.println("Option: ");
		var op = (char) System.in.read();
		selector(op);
	}
}
