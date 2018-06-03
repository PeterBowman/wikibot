package com.github.wikibot.scripts.misc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class GermanInflectedFormsTemplates implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.misc/GermanInflectedFormsTemplates/";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getLists();
				Login.saveSession(wb);
				break;
			case '2':
				makeLists();
				break;
			case '3':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				findWrongHeaders();
				Login.saveSession(wb);
				break;
			case 'e':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				//edit();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		PrintWriter pw = null;
		
		try {
			pw = new PrintWriter(new File(location + "work_list.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
			return;
		}
		
		int count = 0;
		
		PageContainer[] pages = wb.getContentOfCategorymembers("Formy czasowników niemieckich", PLWikt.MAIN_NAMESPACE);
		List<String> list = new ArrayList<>(250);
		
		for (PageContainer page : pages) {
			String content = Optional.of(Page.wrap(page))
				.flatMap(p -> p.getSection("język niemiecki"))
				.flatMap(s -> s.getField(FieldTypes.DEFINITIONS))
				.map(Field::getContent)
				.orElse("");
			
			int start = content.indexOf("{{forma czasownika|de}}");
        	String verbform = content.substring(start);
        	String verbformdef = verbform.substring(verbform.indexOf("\n") + 1);
        	
        	if (verbformdef.contains("{{zob") || !verbformdef.contains("{{")) {
        		list.add(page.getTitle());
        		pw.println(++count + " - " + page.getTitle() + "\n" + verbform);
        	}
		}
		
		pw.close();
		System.out.println("Tamaño de la lista: " + list.size());
	}
	
	public static void makeLists() throws IOException {				
		PrintWriter pw = new PrintWriter(new File(location + "write_list.txt"));
		BufferedReader br = new BufferedReader(new FileReader(location + "work_list.txt"));
		//BufferedReader br = new BufferedReader(new FileReader(location + "work_list_headers.txt"));
		String line = null;
		
		ArrayList<String[]> list = new ArrayList<>(250);
		String title = null;
		boolean jumptonext = false;
		int i = -1;
		
		String p_pp1 = "^: (\\(\\d\\.\\d\\)) ''\\[\\[Partizip Perfekt\\|Part\\. Perf\\.\\]\\] od'' \\[\\[([^\\]]+)\\]\\]$";
		String p_pp2 = "^: (\\(\\d\\.\\d\\)) ''imiesłów czasu przeszłego czasownika \\[\\[([^#]+)#.*?\\]\\]''$";
		String p_i1  = "^: (\\(\\d\\.\\d\\)) ''\\[\\[Imperfekt\\|Imperf\\.\\]\\] od'' \\[\\[([^\\]]+)\\]\\]$";
		String p_i2  = "^: (\\(\\d\\.\\d\\)) ''\\[\\[Präteritum(?:#de)?\\|Prät\\.\\]\\] od'' \\[\\[([^\\]]+)\\]\\]$";
		String p_i3  = "^: (\\(\\d\\.\\d\\)) ''forma czasu przeszłego \\(\\[\\[Präteritum#de\\|Präteritum\\]\\]\\) czasownika:? \\[\\[([^#]+)#.*?\\]\\]''$";
		
		while ((line = br.readLine()) != null) {
			if (line.startsWith("*")) {
				jumptonext = true;
				continue;
			} else if (line.matches("^\\d+ - .*")) {
				jumptonext = false;
				title = line.substring(line.indexOf(" - ") + 3);
				pw.println(line);
				list.add(new String[]{title, ""});
				i++;
				continue;
			} else if (jumptonext) {
				continue;
			} else if (line.equals("")) {
				pw.println("");
				continue;
			} else if (line.startsWith(": ")) {
				line = line
						.replaceFirst(p_pp1, ": $1 {{niem-imprzesz|$2}}")
						.replaceFirst(p_pp2, ": $1 {{niem-imprzesz|$2}}")
						.replaceFirst(p_i1,  ": $1 {{niem-praet|$2}}")
						.replaceFirst(p_i2,  ": $1 {{niem-praet|$2}}")
						.replaceFirst(p_i3,  ": $1 {{niem-praet|$2}}");
			}
			
			pw.println(line);
			list.get(i)[1] += line + "\n";
		}
		
		br.close();
		pw.close();
		
		
		Misc.serialize(list, location + "write_list.ser");
		System.out.println("Tamaño de la lista: " + list.size());
	}
	
	public static void findWrongHeaders() throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers("Język niemiecki - czasowniki", PLWikt.MAIN_NAMESPACE);
		List<String> list = new ArrayList<>(250);
		
		for (PageContainer page : pages) {
			String definitionsText = Optional.of(Page.wrap(page))
				.flatMap(p -> p.getSection("język niemiecki"))
				.flatMap(s -> s.getField(FieldTypes.DEFINITIONS))
				.map(Field::getContent)
				.orElse("");
			
			String fulldef = definitionsText.substring(definitionsText.indexOf("''czasownik"));
			String def = fulldef.substring(fulldef.indexOf("\n") + 1);
			
			if (def.contains("forma fleksyjna")) {
				int newline = fulldef.indexOf("\n");
				fulldef = "''{{forma czasownika|de}}''" + fulldef.substring(newline);
				list.add((list.size() + 1) + " - " + page.getTitle() + "\n" + fulldef);
			}
		}
		
		IOUtils.writeToFile(String.join("\n\n", list), location + "work_list_headers.txt");
		System.out.println("Tamaño de la lista: " + list.size());
	}
	
	public static void edit() throws FileNotFoundException, IOException, LoginException, ClassNotFoundException {
		File f = new File(location + "write_list.ser");
		List<String[]> list = Misc.deserialize(f);
		
		if (list.size() == 0) {
			System.out.println("Lista vacía");
			return;
		}
		
		wb.setThrottle(1000);
		
		System.out.println("Tamaño de la lista: " + list.size());
		
		int conflicts = 0;
		List<String> problems = new ArrayList<>();
		
		for (String[] decl_entry : list) {
			String page = decl_entry[0];
			String deftext = decl_entry[1];
			
			Map<String, String> lhm = wb.getSectionMap(page);
			String header = page + " (<span>język niemiecki</span>)";
			
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
    		Calendar cal = wb.getTopRevision(page).getTimestamp();
    		StringBuilder sb = new StringBuilder(2000);
			
    		int a = content.indexOf("{{znaczenia}}");
        	a = content.indexOf("''{{forma czasownika|de}}", a);
    		//a = content.indexOf("''czasownik", a);
    		
    		sb.append(content.substring(0, a));
    		sb.append(deftext);
    		sb.append(content.substring(content.indexOf("{{odmiana}}", a)));
    		
    		String replaced = null;
    		if (deftext.contains("{{niem-imprzesz|"))
    			replaced = "{{niem-imprzesz}}";
    		else if (deftext.contains("{{niem-imteraz|"))
    			replaced = "{{niem-imteraz}}";
    		else if (deftext.contains("{{niem-praet|"))
    			replaced = "{{niem-praet}}";
    		else {
    			problems.add(page);
    			continue;
    		}
    		    		
    		try {
    			wb.edit(page, sb.toString(), replaced, false, true, section, cal);
    		} catch (ConcurrentModificationException e) {
    			conflicts++;
    			continue;
    		}
		}
		
		f.delete();
		
		System.out.println("Conflictos de edición: " + conflicts + ", problemas: " + problems.size());
		System.out.println(problems.toString());
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new GermanInflectedFormsTemplates());
	}
}