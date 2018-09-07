package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.scripts.plwikt.MissingPolishGerunds;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.MorfeuszLookup;
import com.github.wikibot.utils.PageContainer;

public class PolishGerundsList implements Selectorizable {
	private static Wikibot wb;
	private static final String location = "./data/tasks.plwikt/PolishGerundsList/";
	private static final String locationser = location + "ser/";
	private static final String location_old = MissingPolishGerunds.location;
	private static final String wikipage = "Wikipedysta:PBbot/rzeczowniki odczasownikowe";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				foreignGerunds();
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				//incorrectFormat();
				break;
			case '3':
				wb = Login.createSession("pl.wiktionary.org");
				makeLists();
				break;
			case 'm':
				getMorfeuszList();
				break;
			case 'f':
				wb = Login.createSession("pl.wiktionary.org");
				//writeFormat();
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				writeLists();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void foreignGerunds() throws IOException {
		String[] all_gerunds = wb.whatTranscludesHere("Szablon:odczasownikowy od", 0);
		List<String> list_gerunds = new ArrayList<>(Arrays.asList(all_gerunds));
		
		List<String> polish_gerunds = new ArrayList<>(Arrays.asList(org.wikipedia.ArrayUtils.intersection(
			wb.getCategoryMembers("polski (indeks)", 0),
			all_gerunds
		)));
		
		list_gerunds.removeAll(polish_gerunds);
		
		System.out.println("Total de gerundios: " + all_gerunds.length + ", en polaco: " + polish_gerunds.size() + ", otros: " + list_gerunds.size());
		
		int c = 0;
		for (String gerund : list_gerunds) {
			System.out.println(++c + ". " + gerund);
		}
	}
	
	/*public static void incorrectFormat() throws IOException {
		String[] titles = wb.whatLinksHere("Kategoria:Język polski - rzeczowniki rodzaju nijakiego");
		String[] all_gerunds = wb.whatTranscludesHere("Szablon:odczasownikowy od", 0);
		
		System.out.println("Sustantivos neutros: " + titles.length + ", gerundios: " + all_gerunds.length);
		
		List<String> list = new ArrayList<>(Arrays.asList(titles));
		list.removeAll(new ArrayList<>(Arrays.asList(all_gerunds)));
		
		System.out.println("Tamaño tras excluir gerundios con plantilla: " + list.size());
		
		List<String> nongerund_list = new ArrayList<>(list.size());
		
		for (String page : list) {
			if (!page.endsWith("ie")) {
				nongerund_list.add(page);
			}
		}
		
		list.removeAll(nongerund_list);
		
		System.out.println("Tamaño tras excluir sustantivos no acabados en -ie: " + list.size());
		
		Map<String, String> contentmap = wb.getContentOfPageList(list, "język polski", 400);
		PageContainer[] pages = wb.getContentOfPages(list.toArray(new String[list.size()]), 400);
		List<String[]> work_list = new ArrayList<>(pages.length);
		MyArrayList txt = new MyArrayList(contentmap.size());
		String patt = ".*?\\(\\d\\.\\d\\)\\s\\{\\{rzecz.*";
		
		contentmap.forEach((title, content) -> {
			String defs = EntryParser.getField(content, FieldTypes.DEFINITIONS);
			defs = defs.replace("\n", "_N_");
			defs = defs.substring(defs.indexOf("rzeczownik") + 1);
			
			if (defs.matches(patt)) {
				int rzecz = defs.indexOf("rzecz");
				//rzecz = defs.indexOf("rzecz", rzecz + 1);
				
				//if (rzecz == -1) break;
				
				int start = defs.substring(0, rzecz).lastIndexOf("_N_") + 3;
				int end = defs.indexOf("_N_", start);
				
				String def = defs.substring(start, end);
				
				String verb = def.substring(def.indexOf("[[") + 2, def.indexOf("]]"));
				
				txt.add(title + " - " + def + " || " + verb);
				work_list.add(new String[]{title, verb});
			}
		});
		
		IOUtils.writeToFile(txt.join("\n"), location + "sin formato.txt");
		System.out.println("Errores encontrados: " + work_list.size());
			
		try {
			Misc.serialize(work_list, locationser + "sin formato.ser");
	    } catch (IOException e) {
	    	e.printStackTrace();
	    }
	}*/
	
	public static void getMorfeuszList() throws FileNotFoundException, IOException {
		Set<String> gers = new HashSet<>(300000);
		Set<String> substs = new HashSet<>(150000);
		
		MorfeuszLookup morfeuszLookup = new MorfeuszLookup("");
		morfeuszLookup.setCompression(false);
		
		morfeuszLookup.find(row -> {
			String form = (String) row[0];
			String canonical = (String) row[1];
			String info = (String) row[2];
			
			if (info.startsWith("ger:")) {
				gers.add(form);
			} else if (info.startsWith("subst:") && !substs.contains(canonical)) {
				substs.add(canonical);
			}
		});
		
		System.out.printf("Gerundios: %d, sustantivos: %d%n", gers.size(), substs.size());
		Misc.serialize(gers, locationser + "gers.ser");
		Misc.serialize(substs, locationser + "substs.ser");
	}
	
	public static void writeFormat() throws IOException, LoginException {
		List<String[]> list;
		
		try {
			list = Misc.deserialize(locationser + "sin formato.ser");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			return;
		}
		
		wb.setThrottle(10000);
		System.out.println("Tamaño de la lista: " + list.size());
		
		for (String[] entry : list) {
			String page = entry[0];
			Revision rev = wb.getTopRevision(page);
			OffsetDateTime timestamp = rev.getTimestamp();
			
			String content = wb.getPageText(page);
			
			if (content == null) {
				throw new FileNotFoundException("Page not found: " + page);
			}
			
			int a = content.indexOf("{{znaczenia}}");
			a = content.indexOf(") {{rzecz}} ");
			int b = content.indexOf("\n", a);
			
			String newcontent = content.substring(0, a);
			newcontent += ") {{odczasownikowy od|" + entry[1] + "}}";
			newcontent += content.substring(b);
			
			wb.edit(page, newcontent, "{{odczasownikowy od}}", true, true, -2, timestamp);
		}
	}
	
	public static void makeLists() throws IOException, InterruptedException, ExecutionException, ClassNotFoundException {
		String[] intersection = org.wikipedia.ArrayUtils.intersection(
			wb.getCategoryMembers("Język polski - rzeczowniki rodzaju nijakiego", 0),
			wb.whatTranscludesHere("Szablon:odczasownikowy od", 0)
		);
		
		System.out.printf("Gerundios detectados por transclusión: %d\n", intersection.length);
		
		Map<String, String> list = Misc.deserialize(location_old + "list.ser");
		
		System.out.printf("Gerundios extraídos de plantillas de conjugación: %d\n", list.size());
		
		Set<String> gers = Misc.deserialize(locationser + "gers.ser");
		Set<String> substs = Misc.deserialize(locationser + "substs.ser");
		Set<String> gerunds = new HashSet<>(list.keySet());
		Collections.addAll(gerunds, intersection);
		
		System.out.printf("Gerundios en total: %d\n", gerunds.size());
		
		List<String> listOnlyDefinitions = new ArrayList<>(200);
		List<String> listOnlyTemplates = new ArrayList<>(500);
		List<String> listErrors = new ArrayList<>(200);
		List<String> listNoDictEntry = new ArrayList<>(500);
		
		PageContainer[] pages = wb.getContentOfPages(gerunds.toArray(new String[gerunds.size()]));
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section s = p.getPolishSection().get();
			String definitionsText = null;
			
			try {
				definitionsText = s.getField(FieldTypes.DEFINITIONS).get().getContent();
			} catch (NullPointerException | NoSuchElementException e) {
				System.out.printf("NullPointerException en %s%n", title);
				continue;
			}
			
			if (!definitionsText.contains("{{odczasownikowy od|")) {
				listOnlyDefinitions.add(title);
				continue;
			}
			
			if (!definitionsText.contains(", rodzaj nijaki''")) {
				listErrors.add(String.format("%s - prawdopodobnie nieprawidłowy rodzaj", title));
				continue;
			}
			
			int start = definitionsText.indexOf("\n", definitionsText.indexOf(", rodzaj nijaki''")) + 1;
			String[] lines = definitionsText.substring(start).trim().split("\n");
			boolean hasDef = false;
			
			for (String line : lines) {
				if (!line.startsWith(":")) {
					break;
				}
				
				if (!line.contains("{{odczasownikowy od|")) {
					hasDef = true;
					break;
				}
			}
			
			if (substs.contains(title)) {
				if (!hasDef) {
					listOnlyTemplates.add(title);
				}
			} else if (!gers.contains(title)) {
				listNoDictEntry.add(title);
			}
		}
		
		System.out.printf(
			"Faltan: %d. Solo definición: %d, solo plantilla: %d, errores: %d, sin entrada: %d\n",
			gerunds.size() - pages.length, listOnlyDefinitions.size(), listOnlyTemplates.size(), listErrors.size(), listNoDictEntry.size()
		);
		
		Misc.serialize(listOnlyDefinitions, locationser + "sin plantilla.ser");
		Misc.serialize(listOnlyTemplates, locationser + "sin definición.ser");
		Misc.serialize(listNoDictEntry, locationser + "sin entrada.ser");
		
		List<String> listOnlyDefinitions2 = new ArrayList<>(listOnlyDefinitions.stream()
			.map(gerund -> String.format("%s (%s)", gerund, list.getOrDefault(gerund, "---")))
			.collect(Collectors.toList()));
		
		Files.write(Paths.get(location + "sin plantilla.txt"), listOnlyDefinitions2);
		Files.write(Paths.get(location + "sin definición.txt"), listOnlyTemplates);
		Files.write(Paths.get(location + "errores.txt"), listErrors);
		Files.write(Paths.get(location + "sin entrada.txt"), listNoDictEntry);
		
		Misc.serialize(String.format(
			"Analizowano %s z tabelką odmiany oraz %s.",
			Misc.makePluralPL(list.size(), "czasowniki", "czasowników"),
			Misc.makePluralPL(gerunds.size(), "rzeczowniki odczasownikowe", "rzeczowników odczasownikowych")
		), locationser + "stats.ser");
	}
	
	public static void writeLists() throws IOException, LoginException, ClassNotFoundException {
		Map<String, String> list = Misc.deserialize(location_old + "list.ser");
		List<String> listOnlyDefinitions = Misc.deserialize(locationser + "sin plantilla.ser");
		List<String> listOnlyTemplates = Misc.deserialize(locationser + "sin definición.ser");
		List<String> listNoDictEntry = Misc.deserialize(locationser + "sin entrada.ser");
		
		// Page creation
		
		com.github.wikibot.parsing.Page page = com.github.wikibot.parsing.Page.create(wikipage);
		String stats = Misc.deserialize(locationser + "stats.ser");
		page.setIntro(stats + " Aktualizacja: ~~~~~.");
		
		// Only definition
		
		com.github.wikibot.parsing.Section onlyDefinitionSection = com.github.wikibot.parsing.Section.create("bez szablonu", 3);
		List<String> tempList = new ArrayList<>(listOnlyDefinitions.size());
		
		for (String entry : listOnlyDefinitions) {
			String formatted = String.format("# [[%s]] ([[%s]])", entry, list.getOrDefault(entry, ""));
			tempList.add(formatted);
		}
		
		Misc.sortList(tempList, "pl");
		onlyDefinitionSection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");
		
		// Only template
		
		com.github.wikibot.parsing.Section onlyTemplateSection = com.github.wikibot.parsing.Section.create("brakujące definicje", 3);
		tempList = new ArrayList<>(listOnlyTemplates.size());
		
		for (String entry : listOnlyTemplates) {
			String formatted = String.format("# [[%s]] ([http://sjp.pwn.pl/szukaj/%s SJP])", entry, entry);
			tempList.add(formatted);
		}
		
		Misc.sortList(tempList, "pl");
		onlyTemplateSection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");
		
		// No dictionary entry
		
		com.github.wikibot.parsing.Section noDictEntrySection = com.github.wikibot.parsing.Section.create("niewystępujące w słowniku", 3);
		tempList = listNoDictEntry.stream()
			.map(entry -> String.format("# [[%s]]", entry))
			.collect(Collectors.toList());
		
		Misc.sortList(tempList, "pl");
		noDictEntrySection.setIntro("{{columns|\n" + String.join("\n", tempList) + "\n}}");
		
		// Possible errors
		
		com.github.wikibot.parsing.Section possibleErrors = com.github.wikibot.parsing.Section.create("możliwe błędy", 3);
		
		tempList = Stream.of(
				Files.readAllLines(Paths.get(location_old + "errores.txt")),
				Files.readAllLines(Paths.get(location + "errores.txt"))
			)
			.flatMap(Collection::stream)
			.map(line -> String.format("# [[%s]] – %s", (Object[])line.split(" - ")))
			.collect(Collectors.toList());
		
		Misc.sortList(tempList, "pl");
		possibleErrors.setIntro(String.join("\n", tempList));
		
		// Reflexive verbs
		
		com.github.wikibot.parsing.Section reflexiveVerbs = com.github.wikibot.parsing.Section.create("czasowniki zwrotne", 3);
		
		tempList = Files.lines(Paths.get(location_old + "reflexivos.txt"))
			.map(line -> String.format("[[%s]]", line.substring(0, line.indexOf(" - "))))
			.collect(Collectors.toList());
		
		Misc.sortList(tempList, "pl");
		reflexiveVerbs.setIntro(String.join(", ", tempList));
		
		// Append all sections
		
		page.appendSections(onlyDefinitionSection, onlyTemplateSection, noDictEntrySection, possibleErrors, reflexiveVerbs);
		
		String pageContent = wb.getPageText(wikipage);
		pageContent = pageContent.substring(0, pageContent.indexOf("-->") + 3);
		page.setIntro(pageContent + "\n" + page.getIntro());
		
		wb.edit(wikipage, page.toString(), "aktualizacja", false, false, -2, null);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new PolishGerundsList());
	}
}