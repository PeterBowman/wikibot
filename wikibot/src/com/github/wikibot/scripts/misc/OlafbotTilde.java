package com.github.wikibot.scripts.misc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.Users;

public final class OlafbotTilde implements Selectorizable {
	private static Wikibot wb;
	private static final String location = "./data/scripts.misc/OlafbotTilde/";
	private static final String locationser = location + "ser/";
	private static final int LIMIT = 100;
	private static final boolean ignoreLang = false;
	private static final List<String> langs;
	
	static {
		langs = new ArrayList<String>();
		langs.add("islandzki");
		//langs.add("niemiecki");
	}
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
				//getList();
				Login.saveSession(wb);
				break;
			case '2':
				getEntries();
				break;
			case '3':
				makePreview();
				break;
			case '4':
				int stats = Misc.deserialize(locationser + "stats.ser");
				System.out.println("Total editado: " + stats);
				break;
			case '5':
				LinkedHashMap<String, String> all = Misc.deserialize(locationser + "all.ser");
				System.out.println(all.remove("koszyczki-opałeczki~polski"));
				System.out.println(all.remove("carte blanche~polski"));
				System.out.println(all.remove("odpieprzać‎~polski"));
				System.out.println(all.remove("fotograficzny~polski"));
				Misc.serialize(all, locationser + "all.ser");
				break;
			case '6':
				Misc.serialize(471, locationser + "stats.ser");
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
	
	public static void getList() throws IOException {
		String cnt = wb.getPageText("Wikipedysta:Olafbot/SK/tyldy");
		LinkedHashMap<String, String> pages = new LinkedHashMap<String, String>(1200);
		PrintWriter pw = new PrintWriter(new File(location + "all.txt"));
		
		for (int i = cnt.indexOf("\n# "); i != -1; i = cnt.indexOf("\n# ", ++i)) {
			int end = cnt.indexOf("\n# ", i + 1);
			
			String line = cnt.substring(i + 1, end != -1 ? end + 1 : cnt.length());
			
			int start = line.indexOf("[[") + 2;
			end = line.indexOf("]]", start);
			
			String page = line.substring(start, end);
			
			start = line.indexOf(", ", end) + 2;
			end = line.indexOf("\t", start);
			
			String lang = line.substring(start, end);
			
			start = line.indexOf("\t((", end + 1) + 3;
			end = line.indexOf("))", start);
			
			String field = line.substring(start, end);
			String data = line.substring(end + 2);
			
			data = data.replace("<nowiki>", "").replace("</nowiki>", "");
			
			pages.put(page + "~" + lang, "{{" + field + "}}" + data);
			pw.print(page + " || " + lang + " || " + "{{" + field + "}}" + data);
		}
		
		System.out.println("Lista escrita con éxito en \"all.txt\", tamaño: " + pages.size());
		pw.close();
		
		Misc.serialize(pages, locationser + "all.ser");
	}
	
	public static void getEntries() throws UnsupportedEncodingException, IOException, ClassNotFoundException {
		LinkedHashMap<String, String> pages = new LinkedHashMap<String, String>();
		LinkedHashMap<String, String[]> worklist = new LinkedHashMap<String, String[]>();
		
		pages = Misc.deserialize(locationser + "all.ser");
		PrintWriter pw = new PrintWriter(new File(location + "worklist.txt"));
		
		for (Entry<String, String> entry : pages.entrySet()) {
			String key = entry.getKey();
			
			String page = key.substring(0, key.indexOf("~"));
			String lang = key.substring(key.indexOf("~") + 1);
			
			if (!ignoreLang && !langs.contains(lang))
				continue;
			
			String val = entry.getValue();
			
			pw.println(page + " || " + lang + " || " + val.replace("]]/[[", "]] / [[").replace("; ", " • "));
			worklist.put(key, new String[]{val, null});
			
			if (worklist.size() >= LIMIT)
				break;
		}
		
		pw.close();
		System.out.println("Tamaño de la lista: " + worklist.size());
		
		Misc.serialize(worklist, locationser + "preview.ser");
	}
	
	public static void makePreview() throws IOException, ClassNotFoundException {
		BufferedReader br = new BufferedReader(new FileReader(location + "worklist.txt"));
		LinkedHashMap<String, String[]> pages = new LinkedHashMap<String, String[]>();
		String line = null;
		
		pages = Misc.deserialize(locationser + "preview.ser");
		
		while ((line = br.readLine()) != null) {
			boolean omit = false;
			
			if (line.equals(""))
				continue;
			
			if (line.startsWith("*"))
				omit = true;
			
			int start = line.indexOf(" || ");
			
			String page = line.substring(omit ? 1 : 0, start);
			
			int end = line.indexOf(" || ", start + 1);
			
			String lang = line.substring(start + 4, end);
			String data = line.substring(end + 4);
			
			if (omit) {
				pages.remove(page + "~" + lang);
			} else {
				String[] entry = pages.get(page + "~" + lang);
				entry[0] = entry[0].trim().replace("\\n", "\n");
				entry[1] = data.trim().replace("\\n", "\n");
				pages.put(page + "~" + lang, entry);
			}
		}
		
		br.close();
		System.out.println("Tamaño de la lista: " + pages.size());
		
		if (pages.size() == 0)
			return;
		
		Misc.serialize(pages, locationser + "preview.ser");
		PrintWriter pw = new PrintWriter(new File(location + "preview.txt"));
		
		for (Entry<String, String[]> entry : pages.entrySet()) {
			String key = entry.getKey();
			String page = key.substring(0, key.indexOf("~"));
			String lang = key.substring(key.indexOf("~") + 1);
			
			pw.println(page + " || " + lang);
			pw.println("");
			pw.println(entry.getValue()[0]);
			pw.println(entry.getValue()[1]);
			pw.println("------------------------------");
		}
		
		pw.close();
	}
	
	public static void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		LinkedHashMap<String, String[]> pages = new LinkedHashMap<String, String[]>();
		File f1 = new File(locationser + "preview.ser");
		File f2 = new File(locationser + "stats.ser");
		File f3 = new File(locationser + "all.ser");
		
		pages = Misc.deserialize(f1);
		
		int listsize = pages.size();
		System.out.println("Tamaño de la lista: " + listsize);
		
		if (listsize == 0)
			return;
		
		wb.setThrottle(3000);
		ArrayList<String> errors = new ArrayList<String>();
		ArrayList<String> excluded = new ArrayList<String>();
		ArrayList<String> edited = new ArrayList<String>();
		
		String summary = "półautomatyczna zamiana tyldy; wer.: [[User:Peter Bowman|Peter Bowman]]";
		
		for (Entry<String, String[]> entry : pages.entrySet()) {
			String key = entry.getKey();
			String page = key.substring(0, key.indexOf("~"));
			String lang = key.substring(key.indexOf("~") + 1);
			
			String olddata = entry.getValue()[0];
			String newdata = entry.getValue()[1];
			
			if (newdata == null)
				continue;
			
			olddata = olddata.trim();
			newdata = newdata.trim();
			
			LinkedHashMap<String, String> lhm = wb.getSectionMap(page);
			String header1 = page + " (<span>język " + lang + "</span>)";
			String header2 = page + " (<span>" + lang + "</span>)";
			
			if (!lhm.containsValue(header1) && !lhm.containsValue(header2)) {
    			System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
    			errors.add(page);
    			continue;
    		}
			
			int section = 0;
    		
    		for (Entry<String, String> entryh : lhm.entrySet()) {
    			if (entryh.getValue().equals(header1) || entryh.getValue().equals(header2)) {
    				section = Integer.parseInt(entryh.getKey());
    				break;
    			}
    		}
    		
    		if (section == 0) {
    			System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
    			errors.add(page);
    			continue;
    		}
    		
    		String content = wb.getSectionText(page, section);
    		
    		if (!content.contains(olddata)) {
    			System.out.println("No se ha encontrado la cadena buscada en la página " + page);
    			excluded.add(page + "~" + lang);
    			continue;
    		}
    		
    		content = content.replace(olddata, newdata);
    		
			wb.edit(page, content, summary, true, true, section, null);
			edited.add(page + " || " + lang);
		}
		
		f1.delete();
		
		if (errors.size() != 0) {
			System.out.println("Errores en: " + errors.toString());
		}
		
		if (excluded.size() != 0) {
			System.out.println("Páginas excluidas: " + excluded.toString());
		}
		
		System.out.println("Editados: " + edited.size());
		
		if (edited.size() == 0 && excluded.size() == 0)
			return;
		
		BufferedReader br = new BufferedReader(new FileReader(location + "all.txt"));
		StringBuilder sb = new StringBuilder(25000000);
		String line = null;
		
		while ((line = br.readLine()) != null) {
			boolean found = false;
			
			for (String page : edited) {
				if (line.contains(page)) {
					found = true;
					break;
				}
			}
			
			for (String page : excluded) {
				if (line.contains(page.replace("~", " || "))) {
					found = true;
					break;
				}
			}
			
			if (!found)
				sb.append(line + "\n");
		}
		
		br.close();
		
		PrintWriter pw = new PrintWriter(new File(location + "all.txt"));
		pw.print(sb.toString());
		pw.close();
		
		LinkedHashMap<String, String> all = Misc.deserialize(f3);
		
		for (String page : edited) {
			all.remove(page.replace(" || ", "~"));
		}
		
		for (String page : excluded) {
			all.remove(page);
		}
		
		Misc.serialize(all, f3);
		System.out.println("Lista actualizada");
		
		int stats = 0;
		
		stats = Misc.deserialize(f2);
		stats += edited.size();
		
		Misc.serialize(stats, f2);
		System.out.println("Estadísticas actualizadas (editados en total: " + stats + ")");
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new OlafbotTilde());
	}
}