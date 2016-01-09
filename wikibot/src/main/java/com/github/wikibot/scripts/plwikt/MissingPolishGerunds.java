package com.github.wikibot.scripts.plwikt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.security.auth.login.LoginException;

import org.wikipedia.ParserUtils;
import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

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

public final class MissingPolishGerunds implements Selectorizable {
	private static PLWikt wb;
	public static final String location = "./data/scripts.plwikt/MissingPolishGerunds/";
	private static final String f_list = location + "lista.txt";
	private static final String f_errors = location + "errores.txt";
	private static final String f_refl = location + "reflexivos.txt";
	private static final String f_miss_aff = location + "missing aff.txt";
	private static final String f_miss_neg = location + "missing neg.txt";
	private static final String f_list_ser = location + "list.ser";
	private static final String f_miss_aff_ser = location + "aff_worklist.ser";
	private static final String f_miss_neg_ser = location + "neg_worklist.ser";
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				checkGerunds();
				Login.saveSession(wb);
				break;
			case '2':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
				getMissing();
				Login.saveSession(wb);
				break;
			case '3':
				makeArrayLists();
				break;
			case '8':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				writeAff();
				Login.saveSession(wb);
				break;
			case '9':
				wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
				writeNeg();
				Login.saveSession(wb);
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void checkGerunds() throws IOException, LoginException {
		PageContainer[] pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", PLWikt.MAIN_NAMESPACE);
		
		List<String> errors = new ArrayList<>(100);
		List<String> refl = new ArrayList<>(100);
		List<String> gerunds = new ArrayList<>(2000);
		Map<String, String> list = new HashMap<>(2000);
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			
			String inflection = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.INFLECTION))
				.map(Field::getContent)
				.orElse("");
			
			List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", inflection);
			List<String> temp = new ArrayList<>();
			
			for (String template : templates) {
				Map<String, String> map = ParseUtils.getTemplateParametersWithValue(template);
				
				String gerund = map.keySet().stream()
					.filter(item -> item.trim().matches("z?robienie2?"))
					.map(key -> map.get(key).trim())
					.findFirst()
					.orElse(null);
				
				if (gerund == null) {
					errors.add(title + " - brak parametru");
				} else if (gerund.isEmpty()) {
					errors.add(title + " - niewypełniony parametr");
				} else if (!gerund.endsWith("ie")) {
					errors.add(title + " - " + ParserUtils.recode(gerund));
				} else if (title.endsWith(" się") || title.endsWith(" sobie")) {
					refl.add(title + " - " + gerund);
				} else if (!temp.contains(gerund)) {
					temp.add(gerund);
					gerunds.add(title + " - " + gerund);
					
					if (list.containsKey(gerund)) {
						String formatString = String.format(
							"%s - duplikat ([[%s]], [[%s]])",
							title, list.get(gerund), gerund
						);
						errors.add(formatString);
					} else {
						list.put(gerund, title);
					}
				}
			}
		}
		
		IOUtils.writeToFile(String.join("\n", errors), f_errors);
		IOUtils.writeToFile(String.join("\n", refl), f_refl);
		IOUtils.writeToFile(String.join("\n", gerunds), f_list);
		
		Misc.serialize(list, f_list_ser);
		
		System.out.printf("Verbos escaneados: %d\n", pages.length);
		System.out.printf("Encontrados: %d\n", gerunds.size());
		System.out.printf("Reflexivos: %d\n", refl.size());
		System.out.printf("Errores: %d\n", errors.size());
	}
	
	@SuppressWarnings("rawtypes")
	public static void getMissing() throws IOException, LoginException, ClassNotFoundException {
		List<String> aff = new ArrayList<>(500);
		List<String> neg = new ArrayList<>(500);
		
		Map<String, String> list = Misc.deserialize(f_list_ser);
		
		Set<String> set_aff = list.keySet();
		Set<String> set_neg = set_aff.stream()
			.map(gerund -> "nie" + gerund)
			.collect(Collectors.toSet());
		
		Map[] infos_aff = wb.getPageInfo(set_aff.toArray(new String[set_aff.size()]));
		Map[] infos_neg = wb.getPageInfo(set_neg.toArray(new String[set_neg.size()]));
		
		for (Map info : infos_aff) {
			if (info != null && !(boolean)info.get("exists")) {
				String gerund = (String)info.get("displaytitle");
				String verb = list.get(gerund);
				aff.add(verb + " - " + gerund);
			}
		}
		
		for (Map info : infos_neg) {
			if (info != null && !(boolean)info.get("exists")) {
				String gerund = (String)info.get("displaytitle");
				String verb = list.get(gerund.substring(3));
				neg.add(verb + " - " + gerund);
			}
		}
		
		Misc.sortList(aff, "pl");
		Misc.sortList(neg, "pl");
		
		IOUtils.writeToFile(String.join("\n", aff), f_miss_aff);
		IOUtils.writeToFile(String.join("\n", neg), f_miss_neg);
		
		System.out.printf("Sustantivos faltantes: afirmativos - %d, negativos - %d%n", aff.size(), neg.size());
	}
	
	public static void makeArrayLists() throws IOException {
		String[] aff = IOUtils.loadFromFile(f_miss_aff, "", "UTF8");
		String[] neg = IOUtils.loadFromFile(f_miss_neg, "", "UTF8");
		
		List<String[]> list_aff = Arrays.asList(aff).stream().map(line -> new String[]{
			line.substring(0, line.indexOf(" - ")),
			line.substring(line.indexOf(" - ") + 3)
    	}).collect(Collectors.toList());
		
		List<String[]> list_neg = Arrays.asList(neg).stream().map(line -> new String[]{
			line.substring(0, line.indexOf(" - ")),
			line.substring(line.indexOf(" - ") + 3)
    	}).collect(Collectors.toList());
		
		Misc.serialize(list_aff, f_miss_aff_ser);
		Misc.serialize(list_neg, f_miss_neg_ser);
		
		System.out.printf("Formas extraídas: %d (aff), %d (neg)\n", list_aff.size(), list_neg.size());
	}
	
	public static void writeAff() throws LoginException, IOException {
		List<String[]> list;
		File f = new File(f_miss_aff_ser);
		
		try {
			list = Misc.deserialize(f);
		}
		catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
			return;
		}
		
		wb.setThrottle(2500);
		
		for (String[] entry : list) {
			String content = makePage(entry[0], entry[1], false);
			String summary = String.format("odczasownikowy od [[%s]]", entry[0]);
			wb.edit(entry[1], content, summary, false, true, -2, null);
		}
		
		f.delete();
	}
	
	public static void writeNeg() throws LoginException, IOException {
		List<String[]> list;
		File f = new File(f_miss_neg_ser);
		
		try {
			list = Misc.deserialize(f);
		}
		catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
			return;
		}
		
		wb.setThrottle(2500);
		
		for (String[] entry : list) {
			String content = makePage(entry[0], entry[1], true);
			String summary = String.format("odczasownikowy zaprzeczony od [[%s]]", entry[0]);
			wb.edit(entry[1], content, summary, false, true, -2, null);
		}
		
		f.delete();
	}
	
	public static String makePage(String verb, String gerund, boolean isNegate) {
		String aff = (isNegate ? gerund.substring(3) : gerund);
		String header = isNegate ? String.format("[[nie-|nie]][[%s]]", aff) : aff;
		String definition = String.format(
			"''rzeczownik, rodzaj nijaki''%n: (1.1) {{odczasownikowy od|%s%s}}",
			(isNegate ? "nie|" : ""), verb
		);
		String inflection = makeTemplate(gerund);
		String antonym = String.format("(1.1) [[%s%s]]", isNegate ? "" : "nie", aff);
		String relatedTerms = isNegate ? "" : String.format("{{czas}} [[%s]]", verb);
		
		Page page = Page.create(gerund, "język polski");
		Section section = page.getPolishSection().get();
		
		section.setHeaderTitle(header);
		section.getField(FieldTypes.DEFINITIONS).get().editContent(definition, true);
		section.getField(FieldTypes.INFLECTION).get().editContent(inflection, true);
		section.getField(FieldTypes.ANTONYMS).get().editContent(antonym, true);
		section.getField(FieldTypes.RELATED_TERMS).get().editContent(relatedTerms, true);
		
		return page.toString();
	}
	
	private static String makeTemplate(String gerund) {
		String stem = gerund.substring(0, gerund.length() - 1);
		StringBuilder sb = new StringBuilder();
		
		sb.append("(1.1) {{blm}}, {{odmiana-rzeczownik-polski\n");
		sb.append("|Mianownik lp = " + gerund + "\n");
		sb.append("|Dopełniacz lp = " + stem + "a\n");
		sb.append("|Celownik lp = " + stem + "u\n");
		sb.append("|Biernik lp = " + gerund + "\n");
		sb.append("|Narzędnik lp = " + gerund + "m\n");
		sb.append("|Miejscownik lp = " + stem + "u\n");
		sb.append("|Wołacz lp = " + gerund + "\n");
		sb.append("}}");
		
		return sb.toString();
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new MissingPolishGerunds());
	}
}
