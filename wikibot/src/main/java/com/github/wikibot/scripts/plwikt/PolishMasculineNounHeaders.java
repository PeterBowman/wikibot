package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.plwikt.Editor;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class PolishMasculineNounHeaders implements Selectorizable {
	private static Wikibot wb;
	private static final Path LOCATION = Paths.get("./data/scripts.plwikt/PolishMasculineNounHeaders/");
	private static final Path ALL_PAGES = LOCATION.resolve("allpages.txt");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path SERIALIZED = LOCATION.resolve("info.ser");
	private static final Path STATS = LOCATION.resolve("stats.ser");
	private static final int LIMIT = 5;

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				//getList();
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				getContents();
				break;
			case 's':
				int stats = Misc.deserialize(STATS);
				System.out.printf("Total editado: %d%n", stats);
				break;
			case 'u':
				//Misc.serialize(1508, f_stats);
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws IOException {
		List<String> masc = wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskiego", Wiki.MAIN_NAMESPACE);
		List<String> mrz = wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskorzeczowego", Wiki.MAIN_NAMESPACE);
		List<String> mos = wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskoosobowego", Wiki.MAIN_NAMESPACE);
		List<String> mzw = wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskozwierzęcego‎", Wiki.MAIN_NAMESPACE);
		
		masc = new ArrayList<>(masc);
		masc.removeAll(mrz);
		masc.removeAll(mos);
		masc.removeAll(mzw);
		
		System.out.printf("Todos los sustantivos: %d%n", masc.size());
		
		List<String> decl = wb.whatTranscludesHere(List.of("Szablon:odmiana-rzeczownik-polski"), Wiki.MAIN_NAMESPACE).get(0);
		
		masc.retainAll(decl);
		
		System.out.printf("Sustantivos con declinación: %d%n", decl.size());
		System.out.printf("Sustantivos masculinos con declinación: %d%n", masc.size());
		
		List<String> acronyms = wb.getCategoryMembers("Język polski - skrótowce", Wiki.MAIN_NAMESPACE);
		
		masc.removeAll(acronyms);
		
		System.out.printf("Acrónimos: %d%n", acronyms.size());
		System.out.printf("Tamaño final de la lista: %d%n", masc.size());
		
		Files.write(ALL_PAGES, masc);
	}
	
	public static void getContents() throws UnsupportedEncodingException, IOException {
		List<String> lines = Files.readAllLines(ALL_PAGES);
		List<String> selection = lines.subList(0, Math.min(LIMIT, lines.size() - 1));
		
		System.out.printf("Tamaño de la lista: %d%n", selection.size());
		
		if (selection.isEmpty()) {
			return;
		}
		
		List<PageContainer> pages = wb.getContentOfPages(selection);
		
		Map<String, Collection<String>> map = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				page -> {
					Section s = Page.wrap(page).getPolishSection().get();
					String[] data = new String[]{
						s.getField(FieldTypes.DEFINITIONS).get().getContent(),
						s.getField(FieldTypes.INFLECTION).get().getContent()
					};
					return Arrays.asList(data);
				},
				(a, b) -> a,
				LinkedHashMap::new
			));
		
		Files.write(WORKLIST, List.of(Misc.makeMultiList(map)));
		Misc.serialize(pages, SERIALIZED);
	}
	
	public static void edit() throws IOException, ClassNotFoundException, LoginException {
		String[] lines = Files.readAllLines(WORKLIST).toArray(String[]::new);
		Map<String, String[]> map = Misc.readMultiList(lines);
		PageContainer[] pages = Misc.deserialize(SERIALIZED);
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		if (map.isEmpty()) {
			return;
		}
		
		wb.setThrottle(2500);
		List<String> errors = new ArrayList<>();
		List<String> edited = new ArrayList<>();
		
		String summaryTemplate = "półautomatyczne doprecyzowanie rodzaju męskiego, wer. [[User:Peter Bowman|Peter Bowman]]";
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			String[] data = entry.getValue();
			String definitionsText = data[0];
			
			PageContainer page = Misc.retrievePage(pages, title);
			Page p = Page.wrap(page);
			
			Optional.of(p)
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.DEFINITIONS))
				.ifPresent(f -> f.editContent(definitionsText, true));
			
			AbstractEditor editor = new Editor(p);
			editor.check();
			
			String summary = editor.getSummary(summaryTemplate);
			OffsetDateTime timestamp = page.getTimestamp();
			
    		try {
				wb.edit(title, editor.getPageText(), summary, false, true, -2, timestamp);
				edited.add(title);
				System.out.println(editor.getLogs());				
			} catch (Exception e) {
    			errors.add(title);
    			System.out.printf("Error en: %s%n", title);
    			continue;
    		}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("Errores en: %s%n", errors.toString());
		}
		
		List<String> omitted = Stream.of(pages).map(page -> page.getTitle()).collect(Collectors.toCollection(ArrayList::new));
		omitted.removeAll(map.keySet());
		
		System.out.printf("Editados: %d, errores: %d, omitidos: %d%n", edited.size(), errors.size(), omitted.size());
		
		if (edited.isEmpty() && omitted.isEmpty()) {
			return;
		}
		
		List<String> temp = new ArrayList<>(Files.readAllLines(ALL_PAGES));
		temp.removeAll(edited);
		temp.removeAll(omitted);
		Files.write(ALL_PAGES, temp);
		
		System.out.println("Lista actualizada");
		
		int stats = 0;
		
		stats = Misc.deserialize(STATS);
		stats += edited.size();
		
		Misc.serialize(stats, STATS);
		System.out.printf("Estadísticas actualizadas (editados en total: %d)%n", stats);
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new PolishMasculineNounHeaders());
	}
}