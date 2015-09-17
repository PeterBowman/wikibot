package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.wikiutils.IOUtils;
import org.xml.sax.SAXException;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class ShortCommas implements Selectorizable {
	private static PLWikt wb;
	private static final String location = "./data/scripts.plwikt/ShortCommas/";
	private static final String locationser = location + "ser/";
	private static final String worklist = location + "worklist.txt";
	private static final String shorts = location + "shorts.txt";
	private static final String info = locationser + "info.ser";
	private static Pattern patt;
	
	static {
		String[] shorts = new String[] {
			"starop", "akadfranc", "algierarab", "arabhiszp", "belgfranc", "belghol", "brazport", "egiparab", "eurport",
			"francmetr", "hiszpam", "kanadang", "kanadfranc", "korpłd", "korpłn", "lewantarab", "libijarab",
			"marokarab", "nidhol", "niemdial", "niemrfn", "surinhol", "szkocang", "szwajcfranc", "szwajcniem",
			"szwajcwł", "tunezarab", "nłac", "płac", "stłac",
			// templates
			"skrócenie od", "odczasownikowy od", "zob", "hiszp-pis", "niem-pis", "dokonany od", "niedokonany od"
		};
		
		File f = new File(ShortCommas.shorts);
		
		try {
			String[] lines = IOUtils.loadFromFile(f.getPath(), "", "UTF8");
			Set<String> tempSet = new HashSet<String>(Arrays.asList(shorts));
			tempSet.addAll(Arrays.asList(lines));
			shorts = tempSet.toArray(new String[tempSet.size()]);
		} catch (FileNotFoundException e) {
			System.out.printf("No se ha encontrado el archivo shorts.txt");
		}
		
		System.out.printf("Tamaño de la lista de abreviaturas: %d%n", shorts.length);
		String shortlist = String.join("|", shorts);
		patt = Pattern.compile(".*?\\{\\{ *?(?:" + shortlist + ") *?\\}\\}(?:(;) \\{\\{ *?zob *?\\||(,) (?:\\{\\{ *?(?:" + shortlist + ") *?(?:\\}|\\|)|'')).*", Pattern.DOTALL);
	}

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
				getList();
				Login.saveSession(wb);
				break;
			case '2':
				stripCommas();
				break;
			case 's':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.User1);
				getShorts();
				Login.saveSession(wb);
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
	
	public static void getShorts() throws IOException {
		String[] templates = Stream.of(wb.getCategoryMembers("Szablony skrótów", 10))
			.map(template -> template.replace("Szablon:", ""))
			.toArray(String[]::new);
		
		IOUtils.writeToFile(String.join("\n", templates), shorts);
	}
	
	public static void getList() throws IOException, SAXException {
		Set<String> wlh = new HashSet<String>(Arrays.asList(wb.whatTranscludesHere("Szablon:skrót", 0)));
		List<PageContainer> pages = new ArrayList<PageContainer>(250);
		
		wb.readXmlDump(page -> {
			if (wlh.contains(page.getTitle()) && patt.matcher(page.getText()).matches()) {
				pages.add(page);
			}
		});
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		Misc.serialize(pages, info);
	}
	
	public static void stripCommas() throws FileNotFoundException, ClassNotFoundException, IOException {
		List<PageContainer> pages = Misc.deserialize(info);
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		
		if (pages.isEmpty()) {
			return;
		}
		
		Map<String, Collection<String>> map = new HashMap<String, Collection<String>>(pages.size());
		ListIterator<PageContainer> lt = pages.listIterator();
		
		while (lt.hasNext()) {
			PageContainer page = lt.next();
			String text = page.getText();
			String[] lines = text.split("\n");
			List<String> newLines = new ArrayList<String>();
			List<String> targets = new ArrayList<String>();
			
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				Matcher m = patt.matcher(line);
				
				if (m.matches()) {
					StringBuilder sb = new StringBuilder();
					sb.append(String.format("#%d%n", i + 1));
					sb.append(line);
					
					m.reset();
					
					while (m.find()) {
						int group = m.group(1) == null ? 2 : 1;
						line = line.substring(0, m.start(group)) + line.substring(m.end(group));
						m = patt.matcher(line);
					}
					
					sb.append("\n");
					sb.append(line);
					targets.add(sb.toString());
				}
				
				newLines.add(line);
			}
			
			if (!targets.isEmpty()) {
				map.put(page.getTitle(), targets);
				lt.set(new PageContainer(page.getTitle(), String.join("\n", newLines), page.getTimestamp()));
			}
		}
		
		if (map.size() != pages.size()) {
			String[] errors = pages.stream()
				.filter(page -> !map.containsKey(page.getTitle()))
				.map(page -> page.getTitle())
				.toArray(String[]::new);
			
			System.out.printf("%d errores: %s%n", errors.length, errors.toString());
		}
		
		IOUtils.writeToFile(Misc.makeMultiList(map, "\n\n"), worklist);
		Misc.serialize(pages, info);
	}
	
	public static void edit() throws FileNotFoundException, ClassNotFoundException, IOException {
		List<PageContainer> pages = Misc.deserialize(info);
		String[] lines = IOUtils.loadFromFile(worklist, "", "UTF8");
		Map<String, String[]> map = Misc.readMultiList(lines, "\n\n");
		List<String> errors = new ArrayList<String>();
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		wb.setThrottle(3500);
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			PageContainer page = Misc.retrievePage(pages, title);
			
			if (page == null) {
				System.out.printf("Error en \"%s\"%n", title);
				errors.add(title);
				continue;
			}
			
			try {
				String summary = "usunięcie znaku oddzielającego między kwalifikatorami";
				wb.edit(title, page.getText(), summary, true, true, -2, page.getTimestamp());
			} catch (Exception e) {
				System.out.printf("Error en \"%s\"%n", title);
				errors.add(title);
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errores en: \"%s\"%n", errors.toString());
		}
		
		File f = new File(location + "worklist - done.txt");
		f.delete();
		new File(worklist).renameTo(f);
	}

	public static void main(String[] args) throws FileNotFoundException, ClassNotFoundException, IOException {
		Misc.runTimerWithSelector(new ShortCommas());
	}
}
