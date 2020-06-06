package com.github.wikibot.scripts.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Stream;

import org.wikipedia.ArrayUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;

public final class PolishVerbsInsertInflection implements Selectorizable {
	private static Wikibot wb;
	private static Map<String, Map<String, String>> models = new HashMap<>();
	private static final Path LOCATION = Paths.get("./data/scripts.plwikt/PolishVerbsInsertInflection/");
	private static final Path SERIALIZED = LOCATION.resolve("targets.ser");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getLists();
				break;
			case '2':
				wb = Login.createSession("pl.wiktionary.org");
				analyzeConjugationTemplate();
				break;
			case '3':
				wb = Login.createSession("pl.wiktionary.org");
				getModelVc();
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
		Map<String, String> model = models.get("IV");
		List<String> verbs = wb.getCategoryMembers("Język polski - czasowniki", Wiki.MAIN_NAMESPACE);
		List<String> phrases = wb.getCategoryMembers("Język polski - frazy czasownikowe", Wiki.MAIN_NAMESPACE);
		String[] targets = ArrayUtils.relativeComplement(verbs.toArray(String[]::new), phrases.toArray(String[]::new));
		
		targets = Stream.of(targets)
			.filter(verb -> !verb.contains(" ") || verb.endsWith(" się"))
			.filter(verb -> verb.endsWith(model.get("robić")))
			.toArray(String[]::new);
		
		List<String> inflections = wb.whatTranscludesHere(List.of("Szablon:odmiana-czasownik-polski"), Wiki.MAIN_NAMESPACE).get(0);
		targets = ArrayUtils.relativeComplement(targets, inflections.toArray(String[]::new));
		
		List<PageContainer> pages = wb.getContentOfPages(Arrays.asList(targets));
		List<PageContainer> serialized = new ArrayList<>();
		Map<String, Collection<String>> map = new HashMap<>(pages.size());
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section s = p.getPolishSection().get();
			
			if (!s.getField(FieldTypes.INFLECTION).get().getContent().isEmpty()) {
				continue;
			}
			
			String definitions = s.getField(FieldTypes.DEFINITIONS).get().getContent();
			
			Map<String, String> forms = constructForms(title, model);
			boolean isPerfective = definitions.contains(" dokonany") || definitions.contains("{{dokonany od|");
			boolean isReflexive = title.endsWith(" się") || definitions.contains(" zwrotny");
			String template = null;
			
			if (isReflexive) {
				template = String.format("%s%n%s",
					generateTemplate(forms, isPerfective, false),
					generateTemplate(forms, isPerfective, true)
				);
			} else {
				template = generateTemplate(forms, isPerfective, false);
			}
			
			serialized.add(page);
			String[] coll = new String[]{definitions, template};
			map.put(title, Arrays.asList(coll));
		}
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		Misc.serialize(serialized, SERIALIZED);
		Files.write(WORKLIST, List.of(Misc.makeMultiList(map)));
	}
	
	public static void analyzeConjugationTemplate() throws IOException {
		List<PageContainer> pages = wb.getContentOfTransclusions("Szablon:koniugacjaPL", Wiki.MAIN_NAMESPACE);
		List<PageContainer> serialized = new ArrayList<>();
		Map<String, Collection<String>> map = new HashMap<>(pages.size());
		
		for (PageContainer page : pages) {
			String title = page.getTitle();
			Page p = Page.wrap(page);
			Section s = p.getPolishSection().get();
			
			String inflectionText = s.getField(FieldTypes.INFLECTION).get().getContent();
			List<String> templates = ParseUtils.getTemplates("koniugacjaPL", inflectionText);
			
			if (templates.isEmpty()) {
				templates = ParseUtils.getTemplates("KoniugacjaPL", inflectionText);
			}
						
			String param = ParseUtils.getTemplateParam(templates.get(0), 1);
			
			if (!models.containsKey(param)) {
				continue;
			}
			
			String definitions = s.getField(FieldTypes.DEFINITIONS).get().getContent();
			
			Map<String, String> forms = null;
			
			try {
				forms = constructForms(title, models.get(param));
			} catch (Exception e) {
				continue;
			}
			
			boolean isPerfective = definitions.contains(" dokonany") || definitions.contains("{{dokonany od|");
			boolean isReflexive = title.endsWith(" się") || definitions.contains(" zwrotny");
			String template = null;
			
			if (isReflexive) {
				template = String.format("%s%n%s",
					generateTemplate(forms, isPerfective, false),
					generateTemplate(forms, isPerfective, true)
				);
			} else {
				template = generateTemplate(forms, isPerfective, false);
			}
			
			serialized.add(page);
			String[] coll = new String[]{definitions, inflectionText, template};
			map.put(title, Arrays.asList(coll));
		}
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		Misc.serialize(serialized, SERIALIZED);
		Files.write(WORKLIST, List.of(Misc.makeMultiList(map)));
	}
	
	public static void getModelVc() throws IOException {
		List<PageContainer> pages = wb.getContentOfTransclusions("Szablon:odmiana-czasownik-polski", Wiki.MAIN_NAMESPACE);
		Map<String, String> map = new HashMap<>();
		
		System.out.printf("Tamaño de la lista: %d%n", pages.size());
		
		for (PageContainer page : pages) {
			String inflectionText = Optional.of(Page.wrap(page))
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.INFLECTION))
				.map(Field::getContent)
				.orElse("");
			
			List<String> templates = ParseUtils.getTemplates("odmiana-czasownik-polski", inflectionText);
			boolean found = false;
			
			for (String template : templates) {
				String param = ParseUtils.getTemplateParam(template, "koniugacja", true);
				
				if (param == null) {
					System.out.printf("Sin parámetro en: %s%n", page.getTitle());
					continue;
				}
				
				if (param.equals("Vc")) {
					found = true;
					break;
				}
			}
			
			if (found) {
				map.put(page.getTitle(), inflectionText);
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		Files.write(Paths.get(LOCATION + "Vc.txt"), List.of(Misc.makeList(map)));
	}

	public static void edit() throws ClassNotFoundException, IOException {
		List<PageContainer> pages = Misc.deserialize(SERIALIZED);
		String[] lines = Files.lines(WORKLIST).toArray(String[]::new);
		Map<String, String[]> map = Misc.readMultiList(lines);
		List<String> errors = new ArrayList<>();
		
		System.out.printf("Tamaño de la lista: %d%n", map.size());
		wb.setThrottle(2000);
		
		for (Entry<String, String[]> entry : map.entrySet()) {
			String title = entry.getKey();
			String[] contents = entry.getValue();
			
			PageContainer page = Misc.retrievePage(pages, title);
			
			if (page == null) {
				System.out.printf("Error en \"%s\"%n", title);
				continue;
			}
			
			Page p = Page.wrap(page);
			String newInflection = contents[contents.length - 1];
			
			Optional.of(p)
				.flatMap(Page::getPolishSection)
				.flatMap(s -> s.getField(FieldTypes.INFLECTION))
				.ifPresent(f -> f.editContent(newInflection, true));
			
			String summary = "wstawienie odmiany; wer.: [[User:Peter Bowman]]";
			
			try {
				wb.edit(title, p.toString(), summary, false, true, -2, page.getTimestamp());
			} catch (Exception e) {
				errors.add(title);
			}
		}
		
		System.out.printf("Errores: %d - %s%n", errors.size(), errors.toString());
	}
	
	private static String generateTemplate(Map<String, String> forms, boolean isPerfective, boolean isReflexive) {
		StringBuilder sb = new StringBuilder();
		
		sb.append(": () {{odmiana-czasownik-polski\n");
		
		if (isPerfective) {
			sb.append("| dokonany = tak\n");
			
			if (isReflexive) {
				sb.append("| się = się\n");
				sb.append(String.format("| zrobić = %s%n", forms.get("robić")));
			}
			
			sb.append(String.format("| koniugacja = %s%n", forms.get("model")));
			sb.append(String.format("| zrobię = %s%n", forms.get("robię")));
			sb.append(String.format("| zrobi = %s%n", forms.get("robi")));
			sb.append(String.format("| zrobią = %s%n", forms.get("robią")));
			sb.append(String.format("| zrobiłem = %s%n", forms.get("robiłem")));
			sb.append(String.format("| zrobił = %s%n", forms.get("robił")));
			sb.append(String.format("| zrobiła = %s%n", forms.get("robiła")));
			sb.append(String.format("| zrobili = %s%n", forms.get("robili")));
			sb.append(String.format("| zrobiono = %s%n", forms.get("robiono")));
			sb.append(String.format("| zrób = %s%n", forms.get("rób")));
			sb.append(String.format("| zrobiwszy = %s%n", forms.get("zrobiwszy")));
			
			if (!isReflexive) {
				sb.append(String.format("| zrobiony = %s%n", forms.get("robiony")));
				sb.append(String.format("| zrobieni = %s%n", forms.get("robieni")));				
			}
			
			sb.append(String.format("| zrobienie = %s%n", forms.get("robienie")));
		} else {
			sb.append("| dokonany = nie\n");
			
			if (isReflexive) {
				sb.append("| się = się\n");
				sb.append(String.format("| robić = %s%n", forms.get("robić")));
			}
			
			sb.append(String.format("| koniugacja = %s%n", forms.get("model")));
			sb.append(String.format("| robię = %s%n", forms.get("robię")));
			sb.append(String.format("| robi = %s%n", forms.get("robi")));
			sb.append(String.format("| robią = %s%n", forms.get("robią")));
			sb.append(String.format("| robiłem = %s%n", forms.get("robiłem")));
			sb.append(String.format("| robił = %s%n", forms.get("robił")));
			sb.append(String.format("| robiła = %s%n", forms.get("robiła")));
			sb.append(String.format("| robili = %s%n", forms.get("robili")));
			sb.append(String.format("| robiono = %s%n", forms.get("robiono")));
			sb.append(String.format("| rób = %s%n", forms.get("rób")));
			sb.append(String.format("| robiąc = %s%n", forms.get("robiąc")));
			
			if (!isReflexive) {
				sb.append(String.format("| robiony = %s%n", forms.get("robiony")));
				sb.append(String.format("| robieni = %s%n", forms.get("robieni")));				
			}
			
			sb.append(String.format("| robienie = %s%n", forms.get("robienie")));
		}
		
		sb.append("}}");
		
		return sb.toString();
	}
	
	private static Map<String, String> constructForms(String verb, Map<String, String> model) {
		String lemma = verb.replace(" się", "").substring(0, verb.lastIndexOf(model.get("robić")));
		Map<String, String> map = new HashMap<>();
		
		map.put("model", model.get("model"));
		map.put("robić", String.format("%s%s", lemma, model.get("robić")));
		map.put("robię", String.format("%s%s", lemma, model.get("robię")));
		map.put("robi", String.format("%s%s", lemma, model.get("robi")));
		map.put("robią", String.format("%s%s", lemma, model.get("robią")));
		map.put("robiłem", String.format("%s%s", lemma, model.get("robiłem")));
		map.put("robił", String.format("%s%s", lemma, model.get("robił")));
		map.put("robiła", String.format("%s%s", lemma, model.get("robiła")));
		map.put("robili", String.format("%s%s", lemma, model.get("robili")));
		map.put("robiono", String.format("%s%s", lemma, model.get("robiono")));
		map.put("rób", String.format("%s%s", lemma, model.get("rób")));
		map.put("robiąc", String.format("%s%s", lemma, model.get("robiąc")));
		map.put("zrobiwszy", String.format("%s%s", lemma, model.get("zrobiwszy")));
		map.put("robiony", String.format("%s%s", lemma, model.get("robiony")));
		map.put("robieni", String.format("%s%s", lemma, model.get("robieni")));
		map.put("robienie", String.format("%s%s", lemma, model.get("robienie")));
		
		return map;
	}
	
	public static void main(String[] args) {
		Misc.runTimerWithSelector(new PolishVerbsInsertInflection());
	}
	
	static {
		Map<String, String> I = new HashMap<>();
		
		I.put("model", "I");
		I.put("robić", "ać");
		I.put("robię", "am");
		I.put("robi", "a");
		I.put("robią", "ają");
		I.put("robiłem", "ałem");
		I.put("robił", "ał");
		I.put("robiła", "ała");
		I.put("robili", "ali");
		I.put("robiono", "ano");
		I.put("rób", "aj");
		I.put("robiąc", "ając");
		I.put("zrobiwszy", "awszy");
		I.put("robiony", "any");
		I.put("robieni", "ani");
		I.put("robienie", "anie");
		
		Map<String, String> III = new HashMap<>();
		
		III.put("model", "III");
		III.put("robić", "ieć");
		III.put("robię", "ieję");
		III.put("robi", "ieje");
		III.put("robią", "ieją");
		III.put("robiłem", "iałem");
		III.put("robił", "iał");
		III.put("robiła", "iała");
		III.put("robili", "ieli");
		III.put("robiono", "iano");
		III.put("rób", "iej");
		III.put("robiąc", "iejąc");
		III.put("zrobiwszy", "iawszy");
		III.put("robiony", "iały");
		III.put("robieni", "iali");
		III.put("robienie", "ienie");
		
		Map<String, String> IV = new HashMap<>();
		
		IV.put("model", "IV");
		IV.put("robić", "ować");
		IV.put("robię", "uję");
		IV.put("robi", "uje");
		IV.put("robią", "ują");
		IV.put("robiłem", "owałem");
		IV.put("robił", "ował");
		IV.put("robiła", "owała");
		IV.put("robili", "owali");
		IV.put("robiono", "owano");
		IV.put("rób", "uj");
		IV.put("robiąc", "ując");
		IV.put("zrobiwszy", "owawszy");
		IV.put("robiony", "owany");
		IV.put("robieni", "owani");
		IV.put("robienie", "owanie");
		
		Map<String, String> Va = new HashMap<>();
		
		Va.put("model", "Va");
		Va.put("robić", "nąć");
		Va.put("robię", "nę");
		Va.put("robi", "nie");
		Va.put("robią", "ną");
		Va.put("robiłem", "nąłem");
		Va.put("robił", "nął");
		Va.put("robiła", "nęła");
		Va.put("robili", "nęli");
		Va.put("robiono", "nięto");
		Va.put("rób", "ń");
		Va.put("robiąc", "nąc");
		Va.put("zrobiwszy", "nąwszy");
		Va.put("robiony", "nięty");
		Va.put("robieni", "nięci");
		Va.put("robienie", "nięcie");
		
		Map<String, String> VIaL = new HashMap<>();
		
		VIaL.put("model", "VIa");
		VIaL.put("robić", "lić");
		VIaL.put("robię", "lę");
		VIaL.put("robi", "li");
		VIaL.put("robią", "lą");
		VIaL.put("robiłem", "liłem");
		VIaL.put("robił", "lił");
		VIaL.put("robiła", "liła");
		VIaL.put("robili", "lili");
		VIaL.put("robiono", "lono");
		VIaL.put("rób", "l");
		VIaL.put("robiąc", "ląc");
		VIaL.put("zrobiwszy", "liwszy");
		VIaL.put("robiony", "lony");
		VIaL.put("robieni", "leni");
		VIaL.put("robienie", "lenie");
		
		Map<String, String> VIaSC = new HashMap<>();
		
		VIaSC.put("model", "VIa");
		VIaSC.put("robić", "ścić");
		VIaSC.put("robię", "szczę");
		VIaSC.put("robi", "ści");
		VIaSC.put("robią", "szczą");
		VIaSC.put("robiłem", "ściłem");
		VIaSC.put("robił", "ścił");
		VIaSC.put("robiła", "ściła");
		VIaSC.put("robili", "ścili");
		VIaSC.put("robiono", "szczono");
		VIaSC.put("rób", "ść");
		VIaSC.put("robiąc", "szcząc");
		VIaSC.put("zrobiwszy", "ściwszy");
		VIaSC.put("robiony", "szczony");
		VIaSC.put("robieni", "szczeni");
		VIaSC.put("robienie", "szczenie");
		
		Map<String, String> VIaC = new HashMap<>();
		
		VIaC.put("model", "VIa");
		VIaC.put("robić", "cić");
		VIaC.put("robię", "cę");
		VIaC.put("robi", "ci");
		VIaC.put("robią", "cą");
		VIaC.put("robiłem", "ciłem");
		VIaC.put("robił", "cił");
		VIaC.put("robiła", "ciła");
		VIaC.put("robili", "cili");
		VIaC.put("robiono", "cono");
		VIaC.put("rób", "ć");
		VIaC.put("robiąc", "cąc");
		VIaC.put("zrobiwszy", "ciwszy");
		VIaC.put("robiony", "cony");
		VIaC.put("robieni", "ceni");
		VIaC.put("robienie", "cenie");
		
		Map<String, String> VIaDZ = new HashMap<>();
		
		VIaDZ.put("model", "VIa");
		VIaDZ.put("robić", "dzić");
		VIaDZ.put("robię", "dzę");
		VIaDZ.put("robi", "dzi");
		VIaDZ.put("robią", "dzą");
		VIaDZ.put("robiłem", "dziłem");
		VIaDZ.put("robił", "dził");
		VIaDZ.put("robiła", "dziła");
		VIaDZ.put("robili", "dzili");
		VIaDZ.put("robiono", "dzono");
		VIaDZ.put("rób", "dź");
		VIaDZ.put("robiąc", "dząc");
		VIaDZ.put("zrobiwszy", "dziwszy");
		VIaDZ.put("robiony", "dzony");
		VIaDZ.put("robieni", "dzeni");
		VIaDZ.put("robienie", "dzenie");
		
		Map<String, String> VIaZ = new HashMap<>();
		
		VIaZ.put("model", "VIa");
		VIaZ.put("robić", "zić");
		VIaZ.put("robię", "żę");
		VIaZ.put("robi", "zi");
		VIaZ.put("robią", "żą");
		VIaZ.put("robiłem", "ziłem");
		VIaZ.put("robił", "ził");
		VIaZ.put("robiła", "ziła");
		VIaZ.put("robili", "zili");
		VIaZ.put("robiono", "żono");
		VIaZ.put("rób", "ź");
		VIaZ.put("robiąc", "żąc");
		VIaZ.put("zrobiwszy", "ziwszy");
		VIaZ.put("robiony", "żony");
		VIaZ.put("robieni", "żeni");
		VIaZ.put("robienie", "żenie");
		
		Map<String, String> VIaS = new HashMap<>();
		
		VIaS.put("model", "VIa");
		VIaS.put("robić", "sić");
		VIaS.put("robię", "szę");
		VIaS.put("robi", "si");
		VIaS.put("robią", "szą");
		VIaS.put("robiłem", "siłem");
		VIaS.put("robił", "sił");
		VIaS.put("robiła", "siła");
		VIaS.put("robili", "sili");
		VIaS.put("robiono", "szono");
		VIaS.put("rób", "ś");
		VIaS.put("robiąc", "sząc");
		VIaS.put("zrobiwszy", "siwszy");
		VIaS.put("robiony", "szony");
		VIaS.put("robieni", "szeni");
		VIaS.put("robienie", "szenie");
		
		Map<String, String> VIaN = new HashMap<>();
		
		VIaN.put("model", "VIa");
		VIaN.put("robić", "nić");
		VIaN.put("robię", "nię");
		VIaN.put("robi", "ni");
		VIaN.put("robią", "nią");
		VIaN.put("robiłem", "niłem");
		VIaN.put("robił", "nił");
		VIaN.put("robiła", "niła");
		VIaN.put("robili", "nili");
		VIaN.put("robiono", "niono");
		VIaN.put("rób", "ń");
		VIaN.put("robiąc", "niąc");
		VIaN.put("zrobiwszy", "niwszy");
		VIaN.put("robiony", "niony");
		VIaN.put("robieni", "nieni");
		VIaN.put("robienie", "nienie");
		
		Map<String, String> VIaO = new HashMap<>();
		
		VIaO.put("model", "VIa");
		VIaO.put("robić", "oić");
		VIaO.put("robię", "oję");
		VIaO.put("robi", "oi");
		VIaO.put("robią", "oją");
		VIaO.put("robiłem", "oiłem");
		VIaO.put("robił", "oił");
		VIaO.put("robiła", "oiła");
		VIaO.put("robili", "oili");
		VIaO.put("robiono", "ojono");
		VIaO.put("rób", "ój");
		VIaO.put("robiąc", "ojąc");
		VIaO.put("zrobiwszy", "oiwszy");
		VIaO.put("robiony", "ojony");
		VIaO.put("robieni", "ojeni");
		VIaO.put("robienie", "ojenie");
		
		Map<String, String> VIa = new HashMap<>();
		
		VIa.put("model", "VIa");
		VIa.put("robić", "ić");
		VIa.put("robię", "ię");
		VIa.put("robi", "i");
		VIa.put("robią", "ią");
		VIa.put("robiłem", "iłem");
		VIa.put("robił", "ił");
		VIa.put("robiła", "iła");
		VIa.put("robili", "ili");
		VIa.put("robiono", "iono");
		VIa.put("rób", "");
		VIa.put("robiąc", "iąc");
		VIa.put("zrobiwszy", "iwszy");
		VIa.put("robiony", "iony");
		VIa.put("robieni", "ieni");
		VIa.put("robienie", "ienie");
		
		
		Map<String, String> VIb = new HashMap<>();
		
		VIb.put("model", "VIb");
		VIb.put("robić", "yć");
		VIb.put("robię", "ę");
		VIb.put("robi", "y");
		VIb.put("robią", "ą");
		VIb.put("robiłem", "yłem");
		VIb.put("robił", "ył");
		VIb.put("robiła", "yła");
		VIb.put("robili", "yli");
		VIb.put("robiono", "ono");
		VIb.put("rób", "");
		VIb.put("robiąc", "ąc");
		VIb.put("zrobiwszy", "ywszy");
		VIb.put("robiony", "ony");
		VIb.put("robieni", "eni");
		VIb.put("robienie", "enie");
		
		Map<String, String> VIIIa = new HashMap<>();
		
		VIIIa.put("model", "VIIIa");
		VIIIa.put("robić", "ywać");
		VIIIa.put("robię", "uję");
		VIIIa.put("robi", "uje");
		VIIIa.put("robią", "ują");
		VIIIa.put("robiłem", "ywałem");
		VIIIa.put("robił", "ywał");
		VIIIa.put("robiła", "ywała");
		VIIIa.put("robili", "ywali");
		VIIIa.put("robiono", "ywano");
		VIIIa.put("rób", "uj");
		VIIIa.put("robiąc", "ując");
		VIIIa.put("zrobiwszy", "awszy");
		VIIIa.put("robiony", "ywany");
		VIIIa.put("robieni", "ywani");
		VIIIa.put("robienie", "ywanie");
		
		Map<String, String> VIIIb = new HashMap<>();
		
		VIIIb.put("model", "VIIIb");
		VIIIb.put("robić", "iwać");
		VIIIb.put("robię", "uję");
		VIIIb.put("robi", "uje");
		VIIIb.put("robią", "ują");
		VIIIb.put("robiłem", "iwałem");
		VIIIb.put("robił", "iwał");
		VIIIb.put("robiła", "iwała");
		VIIIb.put("robili", "iwali");
		VIIIb.put("robiono", "iwano");
		VIIIb.put("rób", "uj");
		VIIIb.put("robiąc", "ując");
		VIIIb.put("zrobiwszy", "awszy");
		VIIIb.put("robiony", "iwany");
		VIIIb.put("robieni", "iwani");
		VIIIb.put("robienie", "iwanie");
		
		models.put("I", I);
		models.put("III", III);
		models.put("IV", IV);
		models.put("Va", Va);
		models.put("Vb", Va);
		models.put("Vc", Va);
		models.put("VIaL", VIaL);
		models.put("VIaSC", VIaSC);
		models.put("VIaC", VIaC);
		models.put("VIaDZ", VIaDZ);
		models.put("VIaZ", VIaZ);
		models.put("VIaS", VIaS);
		models.put("VIaN", VIaN);
		models.put("VIaO", VIaO);
		models.put("VIa", VIa);
		models.put("VIb", VIb);
		models.put("VIIIa", VIIIa);
		models.put("VIIIb", VIIIb);
	}
}
