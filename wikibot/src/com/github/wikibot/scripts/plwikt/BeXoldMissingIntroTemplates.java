package com.github.wikibot.scripts.plwikt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class BeXoldMissingIntroTemplates implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/BeXoldMissingIntroTemplates/";
	private static final String locationser = location + "ser/";
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = new PLWikt();
				Login.login(wb, false);
				getList();
				wb.logout();
				break;
			case '2':
				makePreview();
				break;
			case 'e':
				wb = new PLWikt();
				Login.login(wb, true);
				edit();
				wb.logout();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList() throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers("białoruski (taraszkiewica) (indeks)", PLWikt.MAIN_NAMESPACE);
		Map<String, Calendar> info = wb.getTimestamps(pages);
		PrintWriter pw = new PrintWriter(new File(location + "worklist.txt"));
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section s = p.getSection("język białoruski (taraszkiewica)");
			String content = s.toString();

        	int a = content.indexOf("\n") + 1;
        	int b = content.indexOf("\n{{wymowa}}", a);
        	
        	String intro = content.substring(a, b);
        	
        	if (!intro.startsWith("{{ortografieBE") && !intro.contains("\n{{ortografieBE"))
        		continue;
        	        	
        	pw.println(title);
        	pw.println(intro);
        	pw.println("");
		}
		
		pw.close();
		Misc.serialize(info, locationser + "info.ser");
	}
	
	public static void makePreview() throws IOException, ClassNotFoundException {
		BufferedReader br = new BufferedReader(new FileReader(location + "worklist.txt"));
		Map<String, String> pages = new LinkedHashMap<String, String>();
		String line = null;
		String title = null;
		StringBuilder sb = new StringBuilder(500);
		
		while ((line = br.readLine()) != null) {
			if (line.equals("")) {
				pages.put(title, sb.toString().trim());
				title = null;
				sb = new StringBuilder(500);
				continue;
			}
			
			if (title == null) {
				title = line;
			} else {
				sb.append(line + "\n");
			}
		}
		
		br.close();
		System.out.println("Tamaño de la lista: " + pages.size());
		
		if (pages.size() == 0)
			return;
		
		Misc.serialize(pages, locationser + "preview.ser");
	}
	
	public static void edit() throws FileNotFoundException, IOException, ClassNotFoundException, LoginException {
		Map<String, String> pages = new LinkedHashMap<String, String>();
		Map<String, Calendar> info = null;
		File f1 = new File(locationser + "preview.ser");
		File f2 = new File(locationser + "info.ser");
		
		pages = Misc.deserialize(f1);
		info = Misc.deserialize(f2);
		
		int listsize = pages.size();
		System.out.println("Tamaño de la lista: " + listsize);
		
		if (listsize == 0)
			return;
		
		wb.setThrottle(5000);
		ArrayList<String> errors = new ArrayList<String>();
		ArrayList<String> conflicts = new ArrayList<String>();
		ArrayList<String> edited = new ArrayList<String>();
		
		//String summary = "uzupełnienie sekcji początkowych";
		//String summary = "konwersja zapisu ręcznego na wywoł//anie szablonu {{ortografieBE}}";
		String summary = "sekcje początkowe";
		//String summary = "{{ortografieBE|obcy=tak}}";
		
		for (Entry<String, String> entry : pages.entrySet()) {
			String page = entry.getKey();
			String data = entry.getValue();
			
			Map<String, String> lhm = wb.getSectionMap(page);
			String header = page + " (<span>język białoruski (taraszkiewica)</span>)";
			
			if (!lhm.containsValue(header)) {
    			System.out.println("No se ha encontrado la sección correspondiente de la página " + page);
    			errors.add(page);
    			continue;
    		}
			
			int section = 0;
    		
    		for (Entry<String, String> entryh : lhm.entrySet()) {
    			if (entryh.getValue().equals(header)) {
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
    		
    		int a = content.indexOf(" ==\n") + 4;
    		int b = content.indexOf("\n{{wymowa}}", a);
    		
    		content = content.substring(0, a) + data + content.substring(b);
    		
    		Calendar cal = info.get(page);
    		
    		try {
				wb.edit(page, content, summary, true, true, section, cal);
				edited.add(page);
			} catch (UnknownError | UnsupportedOperationException e) {
    			conflicts.add(page);
    			System.out.println("Error, abortando edición...");
    			continue;
    		}
		}
		
		f1.delete();
		f2.delete();
		
		if (conflicts.size() != 0) {
			System.out.println("Conflictos en: " + conflicts.toString());
		}
		
		System.out.println("Editados: " + edited.size() + ", errores: " + errors);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new BeXoldMissingIntroTemplates());
	}
}