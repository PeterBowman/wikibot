package com.github.wikibot.scripts.misc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.DefinitionsField;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

class RussianAdjectivesInflection implements Selectorizable {
	private static final String location = "./data/scripts.misc/RussianAdjectivesInflection/";
	private static final String RU_CATEGORY = "Język rosyjski - przymiotniki";
	private static final String RU_LANG = "język rosyjski";
	private static Wikibot wb;
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				extract_adj();
				break;
			case '2':
				analyze_adj(false);
				break;
			case '3':
				wb = Login.createSession("pl.wiktionary.org");
				analyze_adj(true);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void extract_adj() throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers(RU_CATEGORY, Wiki.MAIN_NAMESPACE);
		List<String> list = new ArrayList<>(pages.length);
		
		for (PageContainer page : pages) {
			Section section = Page.wrap(page).getSection(RU_LANG).get();
			DefinitionsField definitions = (DefinitionsField) section.getField(FieldTypes.DEFINITIONS).get();
			
			if (definitions.getContent().contains("\'\'przymiotnik\'\'")) {
				Field inflection = section.getField(FieldTypes.INFLECTION).get();
				String content = inflection.getContent();
				
				if (
					!content.isEmpty() &&
					!content.contains("{{nieodm}}") &&
					!content.contains("{{odmiana-przymiotnik-rosyjski") //&&
					//!content.contains("\n") &&
					//content.contains("~") &&
					//content.substring(0, 3).equals(": (")
				) {
					list.add(String.format("%s || %s", page.getTitle(), content));
				}
			}
		}
		
		System.out.printf("Tamaño de la lista: %d\n", list.size());
		Files.write(Paths.get(location + "adjetivos.txt"), list);
	}
	
	public static void analyze_adj(boolean edit) throws IOException, LoginException {
		PrintWriter pw_analized;
		
		// open files
		try {
			pw_analized = new PrintWriter(new File(location + "analizados.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
			throw e;
		}
		
		//String check = "^\\d+\\s[\u0000-\uFFFF]+\\s\\|\\|\\s:\\s\\(\\d\\.\\d-?\\d?\\)\\s\\{\\{lp\\}\\}\\s[\u0000-\uFFFF]+\\|[\u0000-\uFFFF]{2,3},\\s~[\u0000-\uFFFF]{2,3},\\s~[\u0000-\uFFFF]{2,3};\\s\\{\\{lm\\}\\}\\s~[\u0000-\uFFFF]+;\\s\'\'krótka\\sforma\'\'.*";
		//String check = "^\\d+\\s[\u0000-\uFFFF]+\\s\\|\\|\\s:\\s\\(\\d\\.\\d-?\\d?\\)\\s\\{\\{lp\\}\\}\\s[\u0000-\uFFFF]+\\|[\u0000-\uFFFF]{2,3},\\s~[\u0000-\uFFFF]{2,3},\\s~[\u0000-\uFFFF]{2,3};\\s\\{\\{lm\\}\\}\\s~[\u0000-\uFFFF]+;\\s\'\'krótka\\sforma\'\'\\s\\{\\{lp\\}\\}\\s[\u0000-\uFFFF]+,\\s[\u0000-\uFFFF]+,\\s[\u0000-\uFFFF]+;\\s\\{\\{lm\\}\\}\\s[\u0000-\uFFFF]+";
		//String check = "^\\d+.*\\}\\}$";
		
		int counter = 0;
		boolean first = true;
		
	    try (pw_analized; BufferedReader br = new BufferedReader(new FileReader(location + "adjetivos.txt"));) {
	        String line = null;

	        while ((line = br.readLine()) != null) {
	        	if (first) {
	        		first = false;
	        		continue;
	        	}
	        	
	        	if (line.substring(0, 3).equals("***"))
	        		continue;
	            
	            /*if (!line.matches(check)) {
	            	System.out.println(line);
	            	//pw_analized.println(line);
	            }*/
	        	
	        	int lbrace = line.indexOf("(");
	        	int rbrace = line.indexOf(")", lbrace) + 1;
	        	
	        	String braces = line.substring(lbrace, rbrace);
	        	String intro = ": " + braces + " ";
	        	String declension = intro + (new RussianAdjectivesRegex(line)).run();
	        	
	        	String page = line.substring(line.indexOf(" ") + 1, line.indexOf(" ", 5));
	        	String number = line.substring(0, line.indexOf(" "));
	        		        	
	        	System.out.println(line + "\n");
	        	System.out.println(page + "\n");
	        	System.out.println(declension + "\n");
	        	
	        	if (edit) {
	        		// editing page
	        		//Calendar basetime = wb.makeCalendar();
	        		LinkedHashMap<String, String> lhm = wb.getSectionMap(page);
	        		String header = page + " (<span>" + RU_LANG + "</span>)";
	        		
	        		if (!lhm.containsValue(header)) {
	        			System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
	        			continue;
	        		}
	        		
	        		int section = 0;
	        		
	        		for (Entry<String, String> entry : lhm.entrySet()) {
	        			if (entry.getValue().equals(header)) {
	        				section = Integer.parseInt(entry.getKey());
	        				break;
	        			}
	        		}
	        		
	        		if (section == 0) {
	        			System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
	        			continue;
	        		}
	        		
	        		String content = wb.getSectionText(page, section);
	        		StringBuilder sb = new StringBuilder(2000);
	        		
	        		int a = content.indexOf("{{odmiana}}");
	        		
	        		sb.append(content.substring(0, a + 12));
	        		sb.append(declension + "\n");
	        		sb.append(content.substring(content.indexOf("{{przykłady}}", a)));
	        			        		
	        		try {
	        			wb.edit(page, sb.toString(), "szablon odmiany; wer.: [[User:Peter Bowman]]", false, true, section, null);
	        		} catch (IOException e) {
	        			System.out.println("ERROR, IOException - no se ha podido editar la página " + page + " (" + number + "), con el id " + counter);
	        		} finally {
	        			counter++;
	        		}
	        		
	        		if (counter % 5 == 0) {
		        		System.out.print("Pausa tras " + counter + " ciclos, pulsa una tecla para continuar...");
		        		System.in.read();
	        		}
	        	} else {
	        		pw_analized.println(line + "\n");
		        	pw_analized.println(intro + (new RussianAdjectivesRegex(line)).run() + "\n");
	        	}
	        }
	    }
	}
	
	public static void main(String args[]) {
		Misc.runTimerWithSelector(new RussianAdjectivesInflection());
	}
}
