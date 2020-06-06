package com.github.wikibot.scripts.misc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class FemaleFormsInTranslations implements Selectorizable {
	private static Wikibot wb;
	private static final Path LOCATION = Paths.get("./data/scripts.misc/FemaleFormsInTranslations/");
	private static final Path LOCATION_SER = LOCATION.resolve("ser/");

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getLists();
				break;
			case '2':
				mascWorklist();
				break;
			case '3':
				femWorklist();
				break;
			case '4':
				wb = Login.createSession("pl.wiktionary.org");
				getChanges();
				break;
			case 'm':
				wb = Login.createSession("pl.wiktionary.org");
				editMasc();
				break;
			case 'f':
				wb = Login.createSession("pl.wiktionary.org");
				editFem();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		List<PageContainer> fempages = wb.getContentOfTransclusions("Szablon:zobtłum rodz", Wiki.MAIN_NAMESPACE);
		
		List<Translations> nouns = fempages.stream()
			.map(Page::wrap)
			.map(page -> {
				Section s = page.getPolishSection().get();
				Field translations = s.getField(FieldTypes.TRANSLATIONS).get();
				String translationsText = translations.getContent();
				List<String> templates = ParseUtils.getTemplates("zobtłum rodz", translationsText);
				String param = ParseUtils.getTemplateParam(templates.get(0), 1);
				return new Translations(page.getTitle(), param, s);
			})
			.collect(Collectors.toList());
		
		List<PageContainer> mascpages = wb.getContentOfPages(
			nouns.stream().map(item -> item.alt_gender).collect(Collectors.toList())
		);
		
		int count = 0;
		
		List<String> masc_errors = new ArrayList<>(50);
		List<String> fem_errors = new ArrayList<>(50);
		
		PrintWriter pw = null, pw_errs = null;
		
		try {
			pw = new PrintWriter(LOCATION.resolve("worklist.txt").toFile());
			pw_errs = new PrintWriter(LOCATION.resolve("errors.txt").toFile());
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
			return;
		}
		
		for (PageContainer page : mascpages) {
			String masc_noun = page.getTitle();
			Page p = Page.wrap(page);
			
			Translations masc = new Translations(masc_noun, p.getPolishSection().get());
        	Translations fem = null;
        	
        	for (Translations transl : nouns) {
        		if (transl.alt_gender.equals(masc_noun)) {
        			fem = transl;
        			break;
        		}
        	}
        	
        	if (fem == null) {
        		System.out.println(masc_noun);
        		continue;
        	}
        	
        	if (masc.hasErrors) {
        		masc_errors.add(masc_noun);
        		pw_errs.println("M - " + masc_noun);
        		continue;
        	} else if (fem.hasErrors) {
        		fem_errors.add(fem.entry);
        		pw_errs.println("F - " + fem.entry);
        		continue;
        	}
        	
        	boolean isHeader = true;
        	
        	for (Entry<String, String> entry2 : masc.translations.entrySet()) {
        		String lang = entry2.getKey();
        		String translation = entry2.getValue();
        		
        		if (translation.contains("{{f}}")) {
        			if (isHeader) {
        				pw.println(masc_noun);
        				count++;
        				isHeader = false;
        			}
        			
        			boolean lacksFemForm = !fem.translations.containsKey(lang);
        			pw.println(lang + ":" + translation + (lacksFemForm ? " || " + fem.entry : ""));
        		}
        	}
        	
        	if (!isHeader) {
        		pw.println("\n");
        	}
		}
		
		pw.close();
		pw_errs.close();
		System.out.println("Encontrados: " + count);
	}
	
	public static void mascWorklist() throws IOException {
		Map<String, List<String>> list = new LinkedHashMap<>(100);

		BufferedReader br = new BufferedReader(new FileReader(LOCATION.resolve("worklist.txt").toFile()));
		String line, header = null;
		List<String> aux = null;
		boolean isHeader = true;
		
		while ((line = br.readLine()) != null) {
			if (line.isEmpty()) {
				if (aux == null && list.containsKey(header)) {
					list.remove(header);
				}
				
				isHeader = true;
				header = null;
				aux = null;
				continue;
			}
			
			if (isHeader) {
				header = line;
				list.put(header, new ArrayList<>(6));
				isHeader = false;
			} else if (!line.startsWith("*")) {
				aux = list.get(header);
				aux.add(line);
				list.put(header, aux);
			}
		}
		
		br.close();
		
		PrintWriter pw_fem = new PrintWriter(LOCATION.resolve("fem_worklist.txt").toFile());
		
		for (Entry<String, List<String>> entry : list.entrySet()) {
			List<String> transls = entry.getValue();
			isHeader = true;
			
			for (String transl : transls) {
				int index = transl.indexOf(" || ");
				if (index != -1) {
					if (isHeader) {
						pw_fem.println(transl.substring(index + 4));
						isHeader = false;
					}
					pw_fem.println(transl.substring(0, index));
				}
			}
			
			pw_fem.println("\n");
		}
		
		pw_fem.close();
		Misc.serialize(list, LOCATION_SER.resolve("worklist"));
	}
	
	public static void femWorklist() throws IOException {
		Map<String, List<String>> list = new LinkedHashMap<>(100);

		BufferedReader br = new BufferedReader(new FileReader(LOCATION.resolve("fem_worklist.txt").toFile()));
		String line, header = null;
		List<String> aux = null;
		boolean isHeader = true;
		
		while ((line = br.readLine()) != null) {
			if (line.isEmpty()) {
				if (aux == null && list.containsKey(header))
					list.remove(header);
				isHeader = true;
				header = null;
				aux = null;
				continue;
			}
			
			if (isHeader) {
				header = line;
				list.put(header, new ArrayList<>(6));
				isHeader = false;
			} else if (!line.startsWith("*")) {
				aux = list.get(header);
				aux.add(line);
				list.put(header, aux);
			}
		}
		
		br.close();
		Misc.serialize(list, LOCATION_SER.resolve("fem_worklist"));
		
		System.out.println("Tamaño de la lista: " + list.size());
	}
	
	public static void getChanges() throws IOException, ClassNotFoundException {
		Map<String, List<String>> masc_list = Misc.deserialize(LOCATION_SER.resolve("worklist"));
		Map<String, List<String>> fem_list = Misc.deserialize(LOCATION_SER.resolve("fem_worklist"));
		
		System.out.printf("Tamaño de las listas: masc - %d, fem - %d\n", masc_list.size(), fem_list.size());
		
		PrintWriter pw_masc = new PrintWriter(LOCATION.resolve("masc_output.txt").toFile());
		PrintWriter pw_fem = new PrintWriter(LOCATION.resolve("fem_output.txt").toFile());
		
		Map<String, String> masc_ready = new LinkedHashMap<>(masc_list.size());
		Map<String, String> fem_ready = new LinkedHashMap<>(fem_list.size());
		
		Set<String> masc_set = masc_list.keySet();
		Set<String> fem_set = fem_list.keySet();
		
		List<PageContainer> masc_pages = wb.getContentOfPages(new ArrayList<>(masc_set));
		List<PageContainer> fem_pages = wb.getContentOfPages(new ArrayList<>(fem_set));
		
		for (PageContainer page : masc_pages) {
			String translationsText = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.TRANSLATIONS))
				.map(Field::getContent)
				.orElse("");
			
			List<String> transls = masc_list.get(page.getTitle());
			
			for (String transl : transls) {
				if (transl.indexOf(" || ") != -1) {
					transl = transl.substring(0, transl.indexOf(" || "));
				}
				String lang = transl.substring(0, transl.indexOf(":"));
				int start = translationsText.indexOf("* " + lang + ":");
				int end = translationsText.indexOf("\n", start);
				
				translationsText = translationsText.substring(0, start) + "* " + transl + translationsText.substring(end);
			}
			
			pw_masc.println(page.getTitle());
			pw_masc.println(translationsText);
			
			masc_ready.put(page.getTitle(), translationsText);
		}
		
		pw_masc.close();
		
		for (PageContainer page : fem_pages) {
			String translationsText = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.TRANSLATIONS))
				.map(Field::getContent)
				.orElse(""); 
			
			List<String> transls = fem_list.get(page.getTitle());
			
			String intro = translationsText.substring(0, translationsText.indexOf("\n* ") + 1);
			String body = translationsText.substring(translationsText.indexOf("\n* ") + 1);
			List<String> myList = new ArrayList<>(Arrays.asList(body.split("\n")));
			
			for (String transl : transls) {
				myList.add("* " + transl);
			}
			
			Misc.sortList(myList, "pl");
			String output = intro + String.join("\n", myList) + "\n";
			
			pw_fem.println(page.getTitle());
			pw_fem.println(output);
			
			fem_ready.put(page.getTitle(), output);
		}
		
		pw_fem.close();
		
		Misc.serialize(masc_ready, LOCATION_SER.resolve("masc_output"));
		Misc.serialize(fem_ready, LOCATION_SER.resolve("fem_output"));
	}
	
	public static void editMasc() throws IOException, LoginException, ClassNotFoundException {
		File f = LOCATION_SER.resolve("masc_output").toFile();
		Map<String, String> list = Misc.deserialize(f);
		
		wb.setThrottle(5000);
		
		for (Entry<String, String> entry : list.entrySet()) {
			String title = entry.getKey();
			String translations = entry.getValue();
			String content = wb.getPageText(List.of(title)).get(0);
			Page p = Page.store(title, content);
    		
			p.getPolishSection().get().getField(FieldTypes.TRANSLATIONS).get().editContent(translations, true);
    		
    		String summary = "usunięcie odnośników {{f}}; przeniesienie tlumaczeń do formy żeńskiej";
    		
    		try {
    			wb.edit(title, p.toString(), summary);
    		} catch (ConcurrentModificationException e) {
    			System.out.println("Conflicto - " + title);
    			continue;
    		}
		}
		
		f.delete();
	}
	
	public static void editFem() throws IOException, LoginException, ClassNotFoundException {
		File f = LOCATION_SER.resolve("fem_output").toFile();
		Map<String, String> list = Misc.deserialize(f);
		
		wb.setMarkMinor(false);
		wb.setMarkBot(true);
		wb.setThrottle(5000);
		
		for (Entry<String, String> entry : list.entrySet()) {
			String title = entry.getKey();
			String translations = entry.getValue();
			String content = wb.getPageText(List.of(title)).get(0);
			Page p = Page.store(title, content);
    		
			p.getPolishSection().get().getField(FieldTypes.TRANSLATIONS).get().editContent(translations, true);
    		
    		String summary = "przeniesienie tlumaczeń z formy męskiej";

    		try {
    			wb.edit(title, p.toString(), summary);
    		} catch (ConcurrentModificationException e) {
    			System.out.println("Conflicto - " + title);
    			continue;
    		}
		}
		
		f.delete();
	}

	public static void main(String[] args) {
		Misc.runTimerWithSelector(new FemaleFormsInTranslations());
	}
}

class Translations {
	public String entry;
	public String alt_gender;
	public boolean only1def;
	public boolean hasErrors = false;
	public Map<String, String> translations;
	
	public Translations(String entry, Section section) {
		this.entry = entry;
		this.translations = new HashMap<>();
		
		try {
			analyzeDefs(section);
			analyzeTranslations(section);
		} catch (StringIndexOutOfBoundsException e) {
			hasErrors = true;
		}
	}
	
	public Translations(String entry, String alt_gender, Section section) {
		this.entry = entry;
		this.alt_gender = alt_gender;
		this.translations = new HashMap<>();
		
		try {
			analyzeDefs(section);
			analyzeTranslations(section);
		} catch (StringIndexOutOfBoundsException e) {
			hasErrors = true;
		}
	}
	
	private void analyzeDefs(Section section) {
		String defs = section.getField(FieldTypes.DEFINITIONS).get().getContent();
		
		if (defs.contains("(1.2)")) {
			only1def = false;
		} else {
			only1def = true;
		}
	}
	
	private void analyzeTranslations(Section section) {
		String transl = section.getField(FieldTypes.TRANSLATIONS).get().getContent();
		
		for (int t = transl.indexOf("\n* "); t != -1; t = transl.indexOf("\n* ", ++t)) {
			int start = transl.indexOf("* ", t) + 2;
			int colon = transl.indexOf(":", start);
			
			if (colon == -1) {
				hasErrors = true;
				break;
			}
			
			String langName = transl.substring(start, colon);
			String translation = transl.substring(colon + 1, transl.indexOf("\n", colon));
			
			translations.put(langName, translation);
		}
	}
}