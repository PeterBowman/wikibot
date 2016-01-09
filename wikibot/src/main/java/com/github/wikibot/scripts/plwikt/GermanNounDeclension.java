package com.github.wikibot.scripts.plwikt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki.Revision;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class GermanNounDeclension implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/GermanNounDeclension/";
	
	private static final Map<String, String[]> det;
	
	static {
		det = new HashMap<>();
		det.put("m",  new String[]{"der", "des", "dem", "den"});
		det.put("f",  new String[]{"die", "der", "der", "die"});
		det.put("n",  new String[]{"das", "des", "dem", "das"});
		det.put("lm", new String[]{"die", "der", "den", "die"});
	}

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
				wb = new PLWikt();
				checkErrors();
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
	
	public static void getLists() throws IOException, ClassNotFoundException {
		PrintWriter pw = null, pw_excl = null;
		
		try {
			pw = new PrintWriter(new File(location + "work_list.txt"));
			pw_excl = new PrintWriter(new File(location + "excluded.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
			return;
		}
		
		File f = new File(location + "excluded.ser");
		List<String[]> excluded = null;
		
		if (f.exists()) {
			excluded = Misc.deserialize(f);
		} else {
			excluded = new ArrayList<>(200);
		}
		
		int excludesize = 0;
		int count = 0;
		
		PageContainer[] pages = wb.getContentOfCategorymembers("Język niemiecki - rzeczowniki", 0);
		List<String> list = new ArrayList<>(pages.length);
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section s = p.getSection("język niemiecki").get();
			Field definitions = s.getField(FieldTypes.DEFINITIONS).get();
        	String definitionsText = definitions.getContent();
        	int a = definitionsText.indexOf("''rzeczownik, rodzaj ");
        	
        	if (a == -1) {
        		excluded.add(new String[]{title, "¿¿¿PART OF SPEECH???"});
        		excludesize++;
        		continue;
        	}
        	
        	String gender = null;
        	
        	try {
        		gender = definitionsText.substring(a + 21, definitionsText.indexOf("''\n", a + 1));
        	} catch (StringIndexOutOfBoundsException e) {
        		excluded.add(new String[]{title, "¿¿¿PART OF SPEECH???"});
        		excludesize++;
        		continue;
        	}
        	
        	if (gender.contains(", nazwa własna")) {
        		excluded.add(new String[]{title, "¿¿¿PROPER NOUN???"});
        		excludesize++;
        		continue;
        	}
        		        	
        	switch (gender) {
        		case "męski":
        			gender = "m";
        			break;
        		case "żeński":
        			gender = "f";
        			break;
        		case "nijaki":
        			gender = "n";
        			break;
        		default:
        			excluded.add(new String[]{title, "¿¿¿GENDER???"});
        			excludesize++;
	        		continue;
        	}
        	
        	Field inflection = s.getField(FieldTypes.INFLECTION).get();
        	String inflectionText = inflection.getContent();
        	
        	if (!inflectionText.isEmpty() && !inflectionText.contains("{{odmiana-rzeczownik-niemiecki")) {
        		for (String[] ex : excluded) {
        			if (ex[0].equals(title)) {
        				continue;
        			}
        		}
        		
        		int n = inflectionText.indexOf("\n");
        		
        		if (
        				inflectionText.indexOf("\n", n + 1) != -1 ||
        				inflectionText.contains("&lt;") ||
        				inflectionText.contains("{{nieodm}}") ||
        				inflectionText.contains("{{zob}}")
        		) {
        			excluded.add(new String[]{title, inflectionText});
        			excludesize++;
        			continue;
        		}
        		
        		list.add(title);
        		pw.println(++count + ". " + title + " || " + gender + " || " + inflectionText);
        	}
		}
		
		pw.close();
		
		for (int i = 0; i < excluded.size(); i++) {
			pw_excl.println((i+1) + ". " + excluded.get(i)[0] + " || " + excluded.get(i)[1]);
			pw_excl.println("\n");
		}
		
		pw_excl.close();
		
		try {
	        ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f));
			out.writeObject(excluded);
			System.out.println("Serialización realizada con éxito - excluded");
			out.close();
	    } catch (IOException i) {
	    	i.printStackTrace();
	    }
		
		System.out.println("Tamaño de la lista: " + list.size() + ", excluidos: " + excluded.size() + " (+" + excludesize + ")");
	}
	
	public static void makeLists() throws IOException, ClassNotFoundException {		
		File f = new File(location + "excluded.ser");
		List<String[]> excluded = null;
		
		if (f.exists()) {
			excluded = Misc.deserialize(f);
		} else {
			excluded = new ArrayList<>(100);
		}
		
		int excluded_size = excluded.size();
		
		PrintWriter pw_decl = new PrintWriter(new File(location + "decl_list.txt"));
		BufferedReader br = new BufferedReader(new FileReader(location + "work_list.txt"));
		String line = null;
		
		List<String[]> decl = new ArrayList<>(500);
		
		outer:
		while ((line = br.readLine()) != null) {
			if (line.isEmpty()) continue;
			
			int sep1 = line.indexOf(" || ");
			String title = line.substring(line.indexOf(". ") + 2, sep1);
			
			int sep2 = line.indexOf(" || ", sep1 + 1);
			String gender = line.substring(sep1 + 4, sep2);
			
			String declension = line.substring(sep2 + 4);
			
			if (line.startsWith("*")) {
				for (String[] excl : excluded) {
					if (excl[0].equals(title))
						continue outer;
				}
				
				excluded.add(new String[]{title, declension});
				continue outer;
			}
			
			String generated_decl = null;
			
			try {
				generated_decl = getDeclension(title, declension, gender);
			} catch (ArrayIndexOutOfBoundsException | UnsupportedOperationException e) {
				for (String[] excl : excluded) {
					if (excl[0].equals(title))
						continue outer;
				}
				
				excluded.add(new String[]{title, declension});
				continue outer;
			}
			
			pw_decl.println(line);
			pw_decl.println(generated_decl);
			pw_decl.println("\n");
			
			decl.add(new String[]{title, generated_decl});
		}
		
		br.close();
		pw_decl.close();
		
		if (excluded_size != excluded.size()) {
			PrintWriter pw_excl = new PrintWriter(new File(location + "excluded.txt"));
			for (int i = 0; i < excluded.size(); i++) {
				pw_excl.println((i+1) + ". " + excluded.get(i)[0] + " || " + excluded.get(i)[1]);
				pw_excl.println("\n");
			}
			pw_excl.close();
			
			ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f));
			out.writeObject(excluded);
			System.out.println("Serialización realizada con éxito - excluded");
			out.close();
		}
		
		Misc.serialize(decl, location + "decl_list.ser");
				
		System.out.println("Tamaño de la lista de excluidos: " + excluded.size() + " (+ " + (excluded.size() - excluded_size) + ")");
		System.out.println("Tamaño de la lista de conjugados: " + decl.size());
	}
	
	public static void edit() throws FileNotFoundException, IOException, LoginException, ClassNotFoundException {
		
		int newcount = 0;
		int conflicts = 0;
		
		File f = new File(location + "editcount.ser");
		File f_decl = new File(location + "decl_list.ser");
		
		List<String[]> decl_list = Misc.deserialize(f_decl);
		
		final int editcount = f.exists()
			? Misc.deserialize(f)
			: 0;
		
		if (decl_list.isEmpty()) {
			System.out.println("Lista vacía");
			return;
		}
		
		wb.setThrottle(500);
		
		System.out.println("Tamaño de la lista: " + decl_list.size());
		
		for (String[] decl_entry : decl_list) {
			String page = decl_entry[0];
			String declension = decl_entry[1];
			
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
			
    		int a = content.indexOf("{{odmiana}}");
    		
    		sb.append(content.substring(0, a + 12));
    		sb.append(declension + "\n");
    		sb.append(content.substring(content.indexOf("{{przykłady}}", a)));
    		    		
    		try {
    			wb.edit(page, sb.toString(), "stabelkowanie odmiany", false, true, section, cal);
    		} catch (UnsupportedOperationException e) {
    			conflicts++;
    			continue;
    		}
    		System.out.println((++newcount) + editcount);
		}
		
		f_decl.delete();
		
		Misc.serialize(editcount + newcount, f);
		
		System.out.println("Editados: " + newcount + " (total: " + (editcount + newcount) + ")");
		System.out.println("Conflictos de edición: " + conflicts);
	}
	
	public static String getDeclension(String title, String declension, String gender) {
		String decl = declension
				.replace(" der ", " ")
				.replace(" des ", " ")
				.replace(" dem ", " ")
				.replace(" den ", " ")
				.replace(" die ", " ")
				.replace(" das ", " ")/*
				.replace("-", "~")*/;
		
		if (decl.contains("(der)") || decl.contains("(die)") || decl.contains("(das)")) {
			throw new UnsupportedOperationException();
		}
		
		boolean blm = decl.contains("{{blm}}");
		boolean no_lm = false;
		
		int lm_i = decl.indexOf("lm}}");
		
		if (lm_i == -1) {
			no_lm = true;
		}
		
		int lp_i = decl.indexOf("{{lp}}");
		
		if (lp_i == -1) {
			return null;
		}
		
		String start = decl.substring(0, lp_i);
		String lp = decl.substring(decl.indexOf("{{lp}}") + 6, (lm_i == -1) ? decl.length() : lm_i - 3).trim().replace(";", ",").replace(", ", ",");
		
		if (lp.endsWith(",")) {
			lp = lp.substring(0, lp.length() - 1);
		}
		
		if (lp.indexOf(",") == -1) {
			return null;
		}
		
		String canon = lp.substring(0, lp.indexOf(","));
		
		if (canon.contains("|")) {
			canon = canon.substring(0, canon.indexOf("|"));
		}
		
		boolean useTitle = false;
		
		if (canon.equals("~")) {
			canon = title;
			useTitle = true;
		}
		
		lp = lp.replace("~", canon).replace("|", "");
		
		String[] lp_forms = lp.split(",");
		
		if (!useTitle && !lp_forms[0].equals(title)) {
			throw new UnsupportedOperationException();
		}
		
		analyzeForms(lp_forms, gender);
		
		String output =
			"{{odmiana-rzeczownik-niemiecki" +
			"|rodzaj = " + gender + "\n" +
			"|Mianownik lp = " + lp_forms[0] + "\n" +
			"|Dopełniacz lp = " + lp_forms[1] +  "\n" +
			"|Celownik lp = " + lp_forms[2] + "\n" +
			"|Biernik lp = " + lp_forms[3] + "\n";
		
		if (!blm && !no_lm) {
			String lm = decl.substring(lm_i + 4).trim().replace(";", ",").replace(", ", ",").replace("|", "");
			
			if (lm.indexOf(",") == -1) {
				return null;
			}
			
			lm = lm.replace("~", canon);
			String[] lm_forms = lm.split(",");
			
			analyzeForms(lm_forms, "lm");
			
			output = start + output + 
				"|Mianownik lm = " + lm_forms[0] + "\n" +
				"|Dopełniacz lm = " + lm_forms[1] +  "\n" +
				"|Celownik lm = " + lm_forms[2] + "\n" +
				"|Biernik lm = " + lm_forms[3] + "\n";
		} else if (no_lm) {
			output = start + output;
		} else {
			output = start + "{{blm}}, " + output;
		}
		
		output += "}}";
		
		return output;
	}
	
	private static void analyzeForms(String[] forms, String gender) {
		for (int i = 0; i < forms.length; i++) {
			String form = forms[i];
			if (form.contains("/"))
				forms[i] = forms[i].replace("/", "<br>" + det.get(gender)[i] + " ");
			else if (form.contains(" ("))
				forms[i] = forms[i].replace(" (", "<br>" + det.get(gender)[i] + " ").replace(")", "");
			else if (form.contains("(")) {
				String pre   = form.substring(0, form.indexOf("("));
				String post  = form.substring(form.indexOf(")") + 1, form.length());
				String inner = form.substring(form.indexOf("(") + 1, form.indexOf(")"));
				forms[i] = forms[i] = pre + post + "<br>" + det.get(gender)[i] + " " + pre + inner + post;
			}
			forms[i] = forms[i].replace(",", "");
		}
	}
	
	public static void checkErrors() throws IOException {
		Revision[] revs = wb.contribs("PBbot", 0);
		//Calendar end = wb.getRevision(4116714).getTimestamp();
		//Calendar start = wb.getRevision(4111867).getTimestamp();
		//Revision[] revs = wb.contribs("PBbot", "", end, start, 0);
		
		List<String> list = new ArrayList<>(100);
		
		for (Revision rev : revs) {
			if (rev.getPage().equals("Actinium")) break;
			String diff = rev.diff(PLWikt.PREVIOUS_REVISION);
			//int lm = diff.indexOf(" lm = ");
			
			/*if (lm != -1 && diff.substring(lm).contains("&lt;br&gt;"))
				System.out.println(rev.getPage() + " - <br>");
			else if (diff.contains("&lt;ref&gt;"))
				System.out.println(rev.getPage() + " - <ref>");
			else if (rev.getSizeDiff() < 90)
				System.out.println(rev.getPage() + " - size");*/
			
			for (int lm = diff.indexOf(" lm = "); lm != -1; lm = diff.indexOf(" lm = ", ++lm)) {
				int nline = diff.indexOf("\n", lm);
				if (nline != -1) {
					if (diff.substring(lm, nline).contains("|")) {
						System.out.println(rev.getPage());
						list.add(rev.getPage());
					}
				}
			}
		}
		
		for (String page : list) {
			System.out.println(page);
		}
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new GermanNounDeclension());
	}
}
