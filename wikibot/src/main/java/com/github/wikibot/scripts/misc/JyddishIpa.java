package com.github.wikibot.scripts.misc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.security.auth.login.LoginException;

import org.wikipedia.ArrayUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public class JyddishIpa implements Selectorizable {
	private static final Path LOCATION = Paths.get("./data/scripts.misc/JyddishIpa/");
	private static Wikibot wb;
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getList(false, false);
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				getList(true, false);
				break;
			case '3':
				wb = Login.createSession("pl.wiktionary.org");
				getList(true, true);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getList(boolean getPagesList, boolean edit) throws IOException, LoginException {
		String chars = new String(new char[]{ 'ŋ' });
		
		Path f = LOCATION.resolve("full_list.ser");
		String[] int_list = null;
		
		if (Files.exists(f)) {
			try {
				ObjectInputStream in = new ObjectInputStream(Files.newInputStream(f));
				int_list = (String[]) in.readObject();
				System.out.println("Objeto extraído del archivo \"full_list.ser\".");
				in.close();
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
				return;
			}
		} else {
			List<String> catmembers_list = wb.getCategoryMembers("jidysz (indeks)", Wiki.MAIN_NAMESPACE);
			List<String> IPA_list  = wb.whatTranscludesHere(List.of("Szablon:IPA"), Wiki.MAIN_NAMESPACE).get(0);
			List<String> IPA2_list = wb.whatTranscludesHere(List.of("Szablon:IPA2"), Wiki.MAIN_NAMESPACE).get(0);
						
			ArrayList<String> list = new ArrayList<>(IPA_list.size() + IPA2_list.size());
			list.addAll(IPA_list);
			
			outer_loop:
			for (String page : IPA2_list) {
				for (String list_page : list) {
					if (list_page.equals(page)) {
						continue outer_loop;
					}
				}
				list.add(page);
			}
			
			int_list = ArrayUtils.intersection(catmembers_list.toArray(String[]::new), list.toArray(String[]::new));
			
			ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(f));
			out.writeObject(int_list);
			System.out.println("Objeto guardado en el archivo \"full_list.ser\".");
			out.close();
		}
		
		if (!getPagesList) {
			System.out.println(int_list.length + " páginas extraídas" + (Files.exists(f) ? " de un objeto deserializado existente" : ""));
			return;
		}
		
		PrintWriter pw_list = null, pw_errors = null;
		
		// open files
		try {
			pw_list = new PrintWriter(LOCATION.resolve("list.txt").toFile());
			pw_errors = new PrintWriter(LOCATION.resolve("errors.txt").toFile());
		} catch (FileNotFoundException e) {
			System.out.println("Fallo al crear los ficheros de destino.");
		}
		
		int size = int_list.length;
		int count = 0;
		int errors = 0;
		StringBuilder sb_list = new StringBuilder(size);
		
		List<PageContainer> pages = wb.getContentOfPages(Arrays.asList(int_list));
		
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
