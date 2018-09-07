package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class ShortCommas implements Selectorizable {
	private static Wikibot wb;
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
			Set<String> tempSet = new HashSet<>(Arrays.asList(shorts));
			tempSet.addAll(Files.readAllLines(Paths.get(f.getPath())));
			shorts = tempSet.toArray(new String[tempSet.size()]);
		} catch (IOException e) {
			System.out.printf("No se ha encontrado el archivo shorts.txt");
		}
		
		System.out.printf("Tamaño de la lista de abreviaturas: %d%n", shorts.length);
		String shortlist = String.join("|", shorts);
		patt = Pattern.compile(".*?\\{\\{ *?(?:" + shortlist + ") *?\\}\\}(?:(;) \\{\\{ *?zob *?\\||(,) (?:\\{\\{ *?(?:" + shortlist + ") *?(?:\\}|\\|)|'')).*", Pattern.DOTALL);
	}

	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession(Domains.PLWIKT.getDomain());
				getList();
				break;
			case '2':
				stripCommas();
				break;
			case 's':
				wb = Login.createSession(Domains.PLWIKT.getDomain());
				getShorts();
				break;
			case 'e':
				wb = Login.createSession(Domains.PLWIKT.getDomain());
				edit();
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getShorts() throws IOException {
		List<String> templates = Stream.of(wb.getCategoryMembers("Szablony skrótów", 10))
			.map(template -> template.replace("Szablon:", ""))
			.collect(Collectors.toList());
		
		Files.write(Paths.get(shorts), templates);
	}
	
	public static void getList() throws IOException {
		Set<String> wlh = new HashSet<>(Arrays.asList(wb.whatTranscludesHere("Szablon:skrót", 0)));
		List<PageContainer> pages = Collections.synchronizedList(new ArrayList<>(250));
		XMLDumpReader dumpReader = new XMLDumpReader(Domains.PLWIKT);
		int size = wb.getSiteStatistics().get("pages");
		
		try (Stream<XMLRevision> stream = dumpReader.getStAXReader(size).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> wlh.contains(rev.getTitle()))
				.filter(rev -> patt.matcher(rev.getText()).matches())
				.map(XMLRevision::toPageContainer)
				.forEach(pages::add);
		}
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		Misc.serialize(pages, info);
	}
	
	public static void stripCommas() throws FileNotFoundException, ClassNotFoundException, IOException {
		List<PageContainer> pages = Misc.deserialize(info);
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		
		if (pages.isEmpty()) {
			return;
		}
		
		Map<String, Collection<String>> map = new HashMap<>(pages.size());
		ListIterator<PageContainer> lt = pages.listIterator();
		
		while (lt.hasNext()) {
			PageContainer page = lt.next();
			String text = page.getText();
			String[] lines = text.split("\n");
			List<String> newLines = new ArrayList<>();
			List<String> targets = new ArrayList<>();
			
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
				.map(PageContainer::getTitle)
				.toArray(String[]::new);
			
			System.out.printf("%d errores: %s%n", errors.length, errors.toString());
		}
		
		Files.write(Paths.get(worklist), Arrays.asList(Misc.makeMultiList(map, "\n\n")));
		Misc.serialize(pages, info);
	}
	
	public static void edit() throws FileNotFoundException, ClassNotFoundException, IOException {
		List<PageContainer> pages = Misc.deserialize(info);
		String[] lines = Files.lines(Paths.get(worklist)).toArray(String[]::new);
		Map<String, String[]> map = Misc.readMultiList(lines, "\n\n");
		List<String> errors = new ArrayList<>();
		
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
