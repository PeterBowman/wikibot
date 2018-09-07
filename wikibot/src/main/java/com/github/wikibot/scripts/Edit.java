package com.github.wikibot.scripts;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class Edit implements Selectorizable {
	private static Wikibot wb;
	private static final Domains domain = Domains.PLWIKT;
	private static final String location = "./data/scripts/Edit/";
	private static final String locationser = location + "ser/";
	private static final String difflist = location + "difflist.txt";
	private static final String worklist = location + "worklist.txt";
	private static final String info = locationser + "info.ser";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				getList();
				break;
			case '2':
				wb = Login.createSession(domain.getDomain());
				makeList();
				break;
			case '3':
				makeList2();
				break;
			case '4':
				wb = Login.createSession(domain.getDomain());
				makeList3();
				break;
			case 'd':
				wb = Login.createSession(domain.getDomain());
				getDiffs();
				break;
			case 'g':
				wb = Login.createSession(domain.getDomain());
				getContents();
				break;
			case 'e':
				wb = Login.createSession(domain.getDomain());
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	@Deprecated
	public static void getList() throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(location + "worklist.txt"));
		Map<String, String> pages = new LinkedHashMap<>();
		String line = null;
		String title = null;
		StringBuilder sb = new StringBuilder(200);
		
		while ((line = br.readLine()) != null) {
			if (line.startsWith("*****")) {
				pages.put(title, sb.toString());
				title = null;
				sb = new StringBuilder(200);
			} else if (title == null) {
				title = line;
			} else {
				sb.append(line + "\n");
			}
		}
		
		br.close();
		int listsize = pages.size();
		System.out.println("Tamaño de la lista: " + listsize);
		
		if (listsize == 0)
			return;
		
		Misc.serialize(pages, locationser + "pages.ser");
		PrintWriter pw = new PrintWriter(new File(location + "preview.txt"));
		
		for (Entry<String, String> entry : pages.entrySet()) {
			pw.println(entry.getKey());
	    	pw.println("");
	    	pw.println(entry.getValue());
	    	pw.println("");
	    	pw.println("-------------------------------------------------------------------------");
	    	pw.println("");
		}
		
		pw.close();
	}
	
	public static void makeList() throws IOException {
		String category = "Kategoria:Części mowy wg języków";
		String boilerplate = "__HIDDENCAT__\n{{indeks|$1}}\n\n[[Przyimki wg języków|$1]]\n[[Kategoria:Części mowy języka $1ego| ]]";
		//String nametmpl = "Kategoria:$1 - frazy $2";
		String nametmpl = "Kategoria:$1 - przyimki";
		
		//String[] phrases = new String[]{"rzeczownikowe", "przymiotnikowe", "czasownikowe", "przysłówkowe"};
		//String[] types = new String[]{"rzeczowniki", "przymiotniki", "czasowniki", "przysłówki"};
		
		String[] pages = wb.getCategoryMembers(category, 14);
		
		PrintWriter pw = new PrintWriter(new File(location + "template.txt"));
		
		for (String page : pages) {
			String intro = page.substring(page.indexOf(":Części mowy ") + 13);
			String replacement = null;
			
			if (intro.contains("języka ")) {
				replacement = intro.replace("języka ", "");
				replacement = replacement.substring(0, replacement.length() - 3);
				intro = intro.replace("języka ", "Język ").replace("ego", "");
			} else {
				replacement = intro.toLowerCase();
				intro = intro.substring(0,1).toUpperCase() + intro.substring(1);
			}
			
			/*for (int i = 0; i < phrases.length; i++) {
				pw.println(nametmpl.replace("$1", intro).replace("$2", phrases[i]));
				pw.println(boilerplate.replace("$1", replacement).replace("$2", phrases[i]).replace("$3", types[i]));
				pw.println("*****************************");
			}*/
			
			pw.println(nametmpl.replace("$1", intro));
			pw.println(boilerplate.replace("$1", replacement));
			pw.println("*****************************");
		}
		
		pw.close();
	}
	
	public static void makeList2() throws IOException {
		//String boilerplate = "__HIDDENCAT__\n{{indeks|$1}}\n\n[[Kategoria:$2 wg języków|$1]]\n[[Kategoria:Części mowy języka $1ego| ]]";
		String boilerplate = "__HIDDENCAT__\n{{indeks|$1}}\n\n[[Kategoria:Nazwy własne wg języków|$1]]\n[[Kategoria:Język $1 - rzeczowniki| ]]";
		//String nametmpl = "Kategoria:Język $1 - $2";
		String nametmpl = "Kategoria:Język $1 - nazwy własne";
		
		//String[] types = new String[]{"rzeczowniki", "przymiotniki", "czasowniki", "przysłówki"};
		String[] langs = new String[]{"adygejski", "afrykanerski", "bengalski", "chiński standardowy", "górnołużycki", "hawajski", "indonezyjski", "krymskotatarski", "kurdyjski", "macedoński", "manx", "nawaho", "pruski", "północnolapoński", "serbski", "staroangielski", "staro-cerkiewno-słowiański", "starofrancuski", "starogrecki", "sycylijski", "tetum", "tybetański", "ujgurski", "volapük", "walijski", "wenecki"};
		
		PrintWriter pw = new PrintWriter(new File(location + "template.txt"));
		
		for (String lang : langs) {
			//for (int i = 0; i < types.length; i++) {
				pw.println(nametmpl.replace("$1", lang)/*.replace("$2", types[i])*/);
				pw.println(boilerplate.replace("$1", lang)/*.replace("$2", types[i].substring(0, 1).toUpperCase() + types[i].substring(1))*/);
				pw.println("*****************************");
			//}
		}
		
		pw.close();
	}
	
	public static void makeList3() throws IOException {
		String boilerplate = "__HIDDENCAT__\n{{indeks|$1}}\n\n[[Kategoria:Zaimki wg języków|$1]]\n[[Kategoria:Części mowy $2| ]]";
		String nametmpl = "Kategoria:$1 - zaimki";
		
		String category = "Kategoria:Części mowy wg języków";
		String[] langs = wb.getCategoryMembers(category, 14);
		
		PrintWriter pw = new PrintWriter(new File(location + "template.txt"));
		
		for (String lang : langs) {
			String longname = lang = lang.substring(lang.indexOf(":Części mowy ") + 13);
			String shortname = null;
			
			if (longname.contains("języka ")) {
				shortname = longname.replace("języka ", "");
				shortname = shortname.substring(0, shortname.length() - 3);
				longname = longname.replace("języka ", "Język ").replace("ego", "");
			} else {
				shortname = longname.toLowerCase();
				longname = longname.substring(0,1).toUpperCase() + longname.substring(1);
			}
			
			pw.println(nametmpl.replace("$1", longname));
			pw.println(boilerplate.replace("$1", shortname).replace("$2", lang));
			pw.println("*****************************");
		}
		
		pw.close();
	}
	
	public static void getContents() throws UnsupportedEncodingException, IOException {
		String category = "Kategoria:Formy czasownikowe wg języków";
		PrintWriter pw = new PrintWriter(new File(location + "template.txt"));
		String[] titles = wb.getCategoryMembers(category, 14);
		PageContainer[] pages = wb.getContentOfPages(titles);
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			String content = page.getText();

        	int index = content.indexOf("wg języków|") + 11;
        	String lang = content.substring(index, content.indexOf("]]", index));
        	
        	index = content.indexOf("]]\n[[Kategoria:") + 3;
        	content = content.substring(0, index) +
    			"[[Kategoria:Język " + lang + " - czasowniki| ]]\n" +
    			content.substring(index);
        	
        	pw.println(title);
			pw.println(content);
			pw.println("*****************************");
		}
				
		pw.close();
	}
	
	public static void getDiffs() throws IOException {
		String[] titles = Files.lines(Paths.get(difflist))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.distinct()
			.toArray(String[]::new);
		
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		if (titles.length == 0) {
			return;
		}

		PageContainer[] pages = wb.getContentOfPages(titles);
		
		Map<String, String> map = Stream.of(pages)
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getText,
				(a, b) -> a,
				LinkedHashMap::new
			));
		
		Files.write(Paths.get(worklist), Arrays.asList(Misc.makeList(map)));
		
		Map<String, OffsetDateTime> timestamps = Stream.of(pages)
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Misc.serialize(timestamps, info);
	}
	
	public static void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		Map<String, String> map = Misc.readList(Files.lines(Paths.get(worklist)).toArray(String[]::new));
		Map<String, OffsetDateTime> timestamps = Misc.deserialize(info);
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		wb.setThrottle(4000);
		List<String> errors = new ArrayList<>();
		
		String summary = "tworzenie kategorii";
		//String summary = "recuperando la versión anterior a la edición de Thegastiinthedark";
		//String summary = "eliminando transclusión de archivo inexistente";
		//String summary = "ujednolicanie podstron archiwum";
		//String summary = "usunięcie linkowania z parametrów {{strona skrót}}";
		//String summary = "odstęp";
		//String summary = "naprawa parametrów odmiany";
		//String summary = "{{SDU}} ([[Wikisłownik:Strony do usunięcia/tytuły ksiąg biblijnych]])";
		//String summary = "usunięcie strony z kategorii kontrolnej (odwołanie do nieistniejącego pliku)";
		//String summary = "usunięcie strony z kategorii kontrolnej (powtórzony parametr)";
		//String summary = "naprawa zerwanego przekierowania";
		//String summary = "dodanie arabskiego tłumaczenia (1.9)";
		//String summary = "formatowanie, weryfikacja ([[User:Peter Bowman]])";
		//String summary = "uproszczenie wywołania [[Szablon:kończące się na]]";
		//String summary = "corrijo enlaces a espacio de nombres Apéndice:";
		//String summary = "[[Wikisłownik:Strony do usunięcia/zawody nauczyciela w j. polskim]]";
		//String summary = "naprawa parametru szablonu";
		//String summary = "zamiana substa na parametr $1";
		//String summary = "aktualizacja parametru \"id\" w szablonie {{Obcy język polski}}";
		//String summary = "użycie szablonu {{Judachin1957}}";
		//String summary = "parametr \'ims=nie\' w szablonie odmiany przymiotnika";
		//String summary = "użycie pełnej nazwy nagłówka znaczeń zamiast kwalifikatora";
		//String summary = "{{diccionari.cat}}";
		//String summary = "aktualizacja parametrów szablonu";
		//String summary = "usunięcie rodzajnika z tytułu sekcji";
		//String summary = "usunięcie rzeczowników odczas. zaprzeczonych z listy pokrewnych";
		//String summary = "usunięcie [[propheto]] z listy rzeczowników pokrewnych";
		//String summary = "naprawa linku ze znakiem 'combining acute accent'";
		//String summary = "{{skrócenie od}}";
		//String summary = "nagłówek sekcji rumuńskiej";
		//String summary = "rzeczownik pospolity";
		//String summary = "dodanie indeksu";
		//String summary = "zduplikowany parametr \"rzepka\"";
		//String summary = "kategoryzacja";
		//String summary = "zbędny zakres w pokrewnych";
		//String summary = "sortowanie";
		//String summary = "{{język linków}}";
		//String summary = "{{pismoRTL}}";
		//String summary = "zbedny odstęp";
		//String summary = "półautomatyczna zamiana tyldy; wer.: [[User:Peter Bowman|Peter Bowman]]";
		//String summary = "nazwa własna";
		//String summary = "podział kurdyjskiego na dialekty";
		
		boolean minor = false;
		boolean checkErrors = true;
		
		for (Entry<String, String> entry : map.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			OffsetDateTime timestamp = timestamps.get(title);
			
			if (!checkErrors) {
				wb.edit(title, text, summary, minor, true, -2, null);
			} else {
				try {
					wb.edit(title, text, summary, minor, true, -2, timestamp);
				} catch (Exception e) {
	    			errors.add(title);
	    			System.out.printf("Error en \"%s\", abortando edición...", title);
	    			continue;
	    		}
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errores en: %s%n", errors.size(), errors.toString());
		}
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		(new File(worklist)).renameTo(f);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new Edit());
	}
}