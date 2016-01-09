package com.github.wikibot.scripts.misc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import javax.security.auth.login.LoginException;

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

public final class CyryllicAccentsInTranslations implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.misc/CyryllicAccentsInTranslations/";
	private static final String locationser = location + "ser/";
	private static final String contentlist = locationser + "contents.ser";
	private static final String targetlist = locationser + "targetlist.ser";
	private static final String analyzedlist = locationser + "analyzedlist.ser";
	private static final String botpage = "Wikipedysta:PBbot/akcenty w tłumaczeniach";

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getContents();
				Login.saveSession(wb);
				break;
			case '2':
				getList();
				break;
			case '3':
				analyzeLists();
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
	
	public static void getContents() throws IOException {
		PageContainer[] pages = wb.getContentOfCategorymembers("polski (indeks)", PLWikt.MAIN_NAMESPACE);
		Misc.serialize(pages, contentlist);
	}
	
	public static void getList() throws IOException, ClassNotFoundException {
		PageContainer[] pages = Misc.deserialize(contentlist);
		Map<String, String> querymap = new HashMap<>(500);
		
		//final String pattern = "\\[\\[[^\\|].*?́[^\\|].*?\\]\\]";
		//final String pattern2 = ".*?\\[\\[[ \u0400-\uFFFF]+\\|[ \u0400-\uFFFF]*?\u0301[ \u0400-\uFFFF]*?\\]\\].*";
		//final String pattern3 = ".*?\\[\\[[\u0400-\uFFFF]*?\u0301[\u0400-\uFFFF]*?\\|[\u0400-\uFFFF]+\\]\\].*";
		//final String pattern4 = ".*?\\[\\[[\u0400-\uFFFF]+\u0301[\u0400-\uFFFF]*?\\]\\].*";
		//final String pattern5 = ".*?\\[\\[[^\\|\\]\u0301]*?\u0301[^\\|\\]]*?\\]\\].*";
		final String pattern6 = ".*?\\[\\[[ \u0400-\uFFFF]+\\|[^\\]\u0301]*?\u0301[^\\|\\]]*?\\]\\].*";
		
		for (PageContainer page : pages) {
			String translations = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.TRANSLATIONS))
				.map(Field::getContent)
				.orElse("");
			
			if (translations.contains("\u0301") && translations.replace("\n", "").matches(pattern6)) {
				querymap.put(page.getTitle(), translations);
			}
		}
		
		System.out.println("Tamaño de la lista: " + querymap.size());
		
		Misc.serialize(querymap, targetlist);
	}
	
	public static void analyzeLists() throws FileNotFoundException, ClassNotFoundException, IOException {
		//Map<String, String> listA = Misc.deserialize(locationser + "targetlist - 372.ser");
		//Map<String, String> listB = Misc.deserialize(locationser + "targetlist - 321.ser");
		Map<String, String> listX = Misc.deserialize(locationser + "targetlist.ser");
		Map<String, String> listC = new HashMap<>(100);
		
		StringBuilder sb = new StringBuilder(5000);
		
		for (Entry<String, String> entry : listX.entrySet()) {
			String page = entry.getKey();
			String content = entry.getValue();
			
			//final String pattern = ".*?\\[\\[[^\u0301]*?\u0301[^\\]]*?\\]\\].*";
			final String pattern5 = ".*?\\[\\[[^\\|\\]\u0301]*?\u0301[^\\|\\]]*?\\]\\].*";
			
			String translations = entry.getValue();
			String[] lines = translations.split("\n");
			List<String> list = new ArrayList<>();
			
			
			for (String line : lines) {
				if (line.matches(pattern5)) {
					list.add(line);
				}
			}
			
			if (list.size() == 0) {
				System.out.println("Error en: " + page);
			}
			
			sb.append(String.format(";%s%n%s%n", page, String.join("\n", list)));
			
			listC.put(page, content);
		}
		
		System.out.println("Tamaño de la lista: " + listC.size());
		Misc.serialize(sb.toString(), analyzedlist);
		PrintWriter pw = new PrintWriter(new File(location + "output.txt"));
		pw.print(sb.toString());
		pw.close();
	}
	
	public static void edit() throws FileNotFoundException, ClassNotFoundException, IOException, LoginException {
		String text = Misc.deserialize(analyzedlist);
		wb.edit(botpage, text, "lista");
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new CyryllicAccentsInTranslations());
	}
}