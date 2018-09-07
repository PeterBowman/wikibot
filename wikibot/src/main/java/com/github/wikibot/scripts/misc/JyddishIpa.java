package com.github.wikibot.scripts.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import javax.security.auth.login.LoginException;

import org.wikipedia.ArrayUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class JyddishIpa implements Selectorizable {
	private static final String location = "./data/scripts.misc/JyddishIpa/";
	private static Wikibot wb;
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				getList(false, false);
				Login.saveSession(wb);
				break;
			case '2':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				getList(true, false);
				Login.saveSession(wb);
				break;
			case '3':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				getList(true, true);
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList(boolean getPagesList, boolean edit) throws IOException, LoginException {
		String chars = new String(new char[]{ 'ŋ' });
		
		File f = new File(location + "full_list.ser");
		String[] int_list = null;
		
		if (f.exists()) {
			try {
				ObjectInputStream in = new ObjectInputStream(new FileInputStream(f));
				int_list = (String[]) in.readObject();
				System.out.println("Objeto extraído del archivo \"full_list.ser\".");
				in.close();
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
				return;
			}
		} else {
			String[] catmembers_list = wb.getCategoryMembers("jidysz (indeks)", 0);
			String[] IPA_list  = wb.whatTranscludesHere("Szablon:IPA", 0);
			String[] IPA2_list = wb.whatTranscludesHere("Szablon:IPA2", 0);
						
			ArrayList<String> list = new ArrayList<>(IPA_list.length + IPA2_list.length);
			list.addAll(Arrays.asList(IPA_list));
			
			outer_loop:
			for (String page : IPA2_list) {
				for (String list_page : list) {
					if (list_page.equals(page)) {
						continue outer_loop;
					}
				}
				list.add(page);
			}
			
			int_list = ArrayUtils.intersection(catmembers_list, list.toArray(new String[list.size()]));
			
			ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f));
			out.writeObject(int_list);
			System.out.println("Objeto guardado en el archivo \"full_list.ser\".");
			out.close();
		}
		
		if (!getPagesList) {
			System.out.println(int_list.length + " páginas extraídas" + (f.exists() ? " de un objeto deserializado existente" : ""));
			return;
		}
		
		PrintWriter pw_list = null, pw_errors = null;
		
		// open files
		try {
			pw_list = new PrintWriter(new File(location + "list.txt"));
			pw_errors = new PrintWriter(new File(location + "errors.txt"));
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
		}
		
		int size = int_list.length;
		int count = 0;
		int errors = 0;
		StringBuilder sb_list = new StringBuilder(size);
		
		PageContainer[] pages = wb.getContentOfPages(int_list);
		
		for (PageContainer page : pages) {
		//for (Entry<String, String> entry : contenmap.entrySet()) {
			String pronunciationText = Optional.of(Page.wrap(page))
				.flatMap(p -> p.getSection("jidysz"))
				.flatMap(s -> s.getField(FieldTypes.PRONUNCIATION))
				.map(Field::getContent)
				.orElse("");
			
			if (ParseUtils.getTemplates("wymowa", pronunciationText).isEmpty()) {
				pw_errors.println(++errors + ". Sin sección de pronunciación: " + page.getTitle());
				continue;
			}
			
			/*if (section.matches(".*\\{\\{IPA2?\\|.*[" + new String(chars) + "].*\\}\\}.*\\s$") && section.contains("{{IPA3")) {
				int a = section.indexOf("{{IPA3|") + 7;
				int b = section.indexOf("}}", a);
				
				if (!section.substring(a, b).contains(new String(chars))) {
					pw_list.println(++count + " - " + title);
					sb_list.append("# [[" + title + "]]\n");
				}
			}*/
			
			if ((pronunciationText.contains("{{IPA|") || pronunciationText.contains("{{IPA2|")) && pronunciationText.contains("{{IPA3|")) {
				boolean inIPA = false;
				int a = pronunciationText.indexOf("{{IPA|") + 6;
				int b = pronunciationText.indexOf("}}", a);
				
				if (b != -1) inIPA = pronunciationText.substring(a, b).matches(".*[" + chars + "].*");
				
				if (!inIPA) {
					a = pronunciationText.indexOf("{{IPA2|") + 7;
					b = pronunciationText.indexOf("}}", a);
					
					if (b != -1) inIPA = pronunciationText.substring(a, b).matches(".*[" + chars + "].*");
				}
					
				if (inIPA) {
					a = pronunciationText.indexOf("{{IPA3|") + 7;
					b = pronunciationText.indexOf("}}", a);
					
					if (!pronunciationText.substring(a, b).matches(".*[" + chars + "].*")) {
						pw_list.println(++count + " - " + page.getTitle());
						sb_list.append("# [[" + page.getTitle() + "]]\n");
					}
				}
				
			}
		}
		
		pw_list.close();
		pw_errors.close();
		
		if (!edit) {
			System.out.println("Encontrados: " + count + ", errores: " + errors);
			return;
		}
		
		String page = "Wikipedysta:Ksymil/jidysz - błędy w IPA";
		String intro = "Wykryte znaki: ''" + new String(chars) + "'' (aktualizacja: ~~~~~).\n\n";
		
		wb.edit(page, intro + sb_list.toString(), "aktualizacja", false, false, -2, null);
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new JyddishIpa());
	}
}
