package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.plwikt.Editor;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class PolishMasculineNounHeaders implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/PolishMasculineNounHeaders/";
	private static final String f_allpages = location + "allpages.txt";
	private static final String f_worklist = location + "worklist.txt";
	private static final String f_serialized = location + "info.ser";
	private static final String f_stats = location + "stats.ser";
	private static final int LIMIT = 5;

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				//getList();
				Login.saveSession(wb);
				break;
			case '2':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getContents();
				Login.saveSession(wb);
				break;
			case 's':
				int stats = Misc.deserialize(f_stats);
				System.out.printf("Total editado: %d%n", stats);
				break;
			case 'u':
				//Misc.serialize(1508, f_stats);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws IOException {
		List<String> masc = Arrays.asList(wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskiego", PLWikt.MAIN_NAMESPACE));
		List<String> mrz = Arrays.asList(wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskorzeczowego", PLWikt.MAIN_NAMESPACE));
		List<String> mos = Arrays.asList(wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskoosobowego", PLWikt.MAIN_NAMESPACE));
		List<String> mzw = Arrays.asList(wb.getCategoryMembers("Język polski - rzeczowniki rodzaju męskozwierzęcego‎", PLWikt.MAIN_NAMESPACE));
		
		masc = new ArrayList<>(masc);
		masc.removeAll(mrz);
		masc.removeAll(mos);
		masc.removeAll(mzw);
		
		System.out.printf("Todos los sustantivos: %d%n", masc.size());
		
		List<String> decl = Arrays.asList(wb.whatTranscludesHere("Szablon:odmiana-rzeczownik-polski", 0));
		
		masc.retainAll(decl);
		
		System.out.printf("Sustantivos con declinación: %d%n", decl.size());
		System.out.printf("Sustantivos masculinos con declinación: %d%n", masc.size());
		
		List<String> acronyms = Arrays.asList(wb.getCategoryMembers("Język polski - skrótowce", PLWikt.MAIN_NAMESPACE));
		
		masc.removeAll(acronyms);
		
		System.out.printf("Acrónimos: %d%n", acronyms.size());
		System.out.printf("Tamaño final de la lista: %d%n", masc.size());
		
		IOUtils.writeToFile(String.join("\n", masc), f_allpages);
	}
	
	public static void getContents() throws UnsupportedEncodingException, IOException {
		String[] lines = IOUtils.loadFromFile(f_allpages, "", "UTF8");
		String[] selection = Arrays.copyOfRange(lines, 0, Math.min(LIMIT, lines.length - 1));
		
		System.out.printf("Tamaño de la lista: %d%n", selection.length);
		
		if (selection.length == 0) {
			return;
		}
		
		PageContainer[] pages = wb.getContentOfPages(selection);
		
		Map<String, Collection<String>> map = Stream.of(pages)
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
		
		IOUtils.writeToFile(Misc.makeMultiList(map), f_worklist);
		Misc.serialize(pages, f_serialized);
	}
	
	public static void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		String[] lines = IOUtils.loadFromFile(f_worklist, "", "UTF8");
		Map<String, String[]> map = Misc.readMultiList(lines);
		PageContainer[] pages = Misc.deserialize(f_serialized);
		
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
    		Calendar timestamp = page.getTimestamp();
			
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
		
		List<String> omitted = Stream.of(pages).map(page -> page.getTitle()).collect(Collectors.toList());
		omitted.removeAll(map.keySet());
		
		System.out.printf("Editados: %d, errores: %d, omitidos: %d%n", edited.size(), errors.size(), omitted.size());
		
		if (edited.isEmpty() && omitted.isEmpty()) {
			return;
		}
		
		String[] allpages = IOUtils.loadFromFile(f_allpages, "", "UTF8");
		List<String> temp = new ArrayList<>(Arrays.asList(allpages));
		temp.removeAll(edited);
		temp.removeAll(omitted);
		IOUtils.writeToFile(String.join("\n", temp), f_allpages);
		
		System.out.println("Lista actualizada");
		
		int stats = 0;
		
		stats = Misc.deserialize(f_stats);
		stats += edited.size();
		
		Misc.serialize(stats, f_stats);
		System.out.printf("Estadísticas actualizadas (editados en total: %d)%n", stats);
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		(new File(f_worklist)).renameTo(f);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new PolishMasculineNounHeaders());
	}
}