package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.ArrayUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class ReplaceEnDash implements Selectorizable {
	private static Wikibot wb;
	private static final String location = "./data/scripts.plwikt/ReplaceEnDash/";
	private static final String renameList = location + "rename.txt";
	private static final String editList = location + "edit.txt";
	private static final String reviewedList = location + "reviewed.txt";
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getLists();
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				getEditTargets();
				break;
			case 'm':
				wb = Login.createSession("pl.wiktionary.org");
				rename();
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getLists() throws IOException {
		Map<String, Integer> namespaceIds = wb.getNamespaces();
		Integer[] namespaces = new Integer[]{namespaceIds.get("Aneks"), namespaceIds.get("Indeks"), Wiki.CATEGORY_NAMESPACE};
		List<String> titles = new ArrayList<>();
		
		for (Integer namespace : namespaces) {
			// FIXME: limited to 5000 pages
			List<String> temp = wb.listPages("", null, namespace, -1, -1, false);
			titles.addAll(temp);
		}
		
		String[] targets = titles.stream().filter(title -> title.contains(" – ")).toArray(String[]::new);
		
		System.out.printf("Páginas existentes detectadas: %d%n", targets.length);
		Files.write(Paths.get(renameList), Arrays.asList(targets));
		
		titles = new ArrayList<>();
		
		for (Integer namespace : namespaces) {
			String[] temp = wb.allLinks("", namespace);
			titles.addAll(Arrays.asList(temp));
		}
		
		targets = titles.stream().filter(title -> title.contains(" – ")).toArray(String[]::new);
		
		System.out.printf("Enlaces encontrados: %d%n", targets.length);
		Files.write(Paths.get(editList), Arrays.asList(targets));
	}
	
	public static void getEditTargets() throws IOException {
		String[] titles = Files.lines(Paths.get(editList)).toArray(String[]::new);
		Map<String, Collection<String>> map = new HashMap<>();
		
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		for (String title : titles) {
			String[] backlinks = wb.whatLinksHere(title);
			
			if (backlinks.length != 0) {
				for (String backlink : backlinks) {
					if (map.containsKey(backlink)) {
						Collection<String> list = map.get(backlink);
						list.add(title);
					} else {
						List<String> list = new ArrayList<>();
						list.add(title);
						map.put(backlink, list);
					}
				}
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		Files.write(Paths.get(reviewedList), List.of(Misc.makeMultiList(map, "\n")));
	}
	
	public static void rename() throws LoginException, IOException {
		String[] titles = Files.lines(Paths.get(renameList)).toArray(String[]::new);
		
		System.out.printf("Tamaño de la lista: %d%n", titles.length);
		
		for (String title : titles) {
			String newTitle = title.replace(" – ", " - ");
			String reason = "zamiana półpauzy na dywiz";
			wb.move(title, newTitle, reason);
		}
		
		File f = new File(location + "rename - done.txt");
		
		if (f.exists()) {
			f.delete();
		}
		
		new File(renameList).renameTo(f);
	}
	
	public static void edit() throws IOException, LoginException {
		String[] lines = Files.lines(Paths.get(reviewedList)).toArray(String[]::new);
		Map<String, String[]> map = Misc.readMultiList(lines, "\n");
		List<String> errors = new ArrayList<>();
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			String[] backlinks = entry.getValue();
			String text = wb.getPageText(List.of(title)).get(0);
			List<String> missing = new ArrayList<>(); 
			
			for (String backlink : backlinks) {
				String target = String.format("[[%s", backlink);
				
				if (!text.contains(target)) {
					missing.add(backlink);
				} else {
					String replacement = target.replace(" – ", " - ");
					text = text.replace(target, replacement);
				}
			}
			
			if (!missing.isEmpty()) {
				errors.add(String.format("%s (%s)", title, String.join(", ", missing)));
			}
			
			if (missing.size() == backlinks.length) {
				continue;
			} else {
				String[] targets = ArrayUtils.relativeComplement(backlinks, missing.toArray(String[]::new));
				targets = Stream.of(targets).map(link -> String.format("[[%s]]", link.replace(" – ", " - "))).toArray(String[]::new);
				String links = String.join(", ", targets);
				String summary = "zamiana półpauzy na dywiz: " + links;
				wb.edit(title, text, summary, true, true, -2, null);
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errores: %s%n", errors.size(), errors.toString());
		}
		
		File f = new File(location + "edit - done.txt");
		f.delete();
		new File(editList).renameTo(f);
	}

	public static void main(String[] args) {
		Misc.runTimerWithSelector(new ReplaceEnDash());

	}
}
