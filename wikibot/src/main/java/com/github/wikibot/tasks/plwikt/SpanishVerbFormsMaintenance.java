package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.github.wikibot.utils.RAE;

final class SpanishVerbFormsMaintenance implements Selectorizable {
	private static Wikibot wb;
	private static final Plural PLURAL_PL;
	private static final String location = "./data/tasks.plwikt/SpanishVerbFormsMaintenance/";
	private static final String locationser = location + "ser/";
	private static final String RAEurl = "http://lema.rae.es/drae/srv/search?val=";
	private static final String wikipage = "Wikipedysta:PBbot/formy czasowników hiszpańskich";
	
	static {
		WordForms[] polishWords = new WordForms[] {
			new WordForms(new String[] {"forma fleksyjna", "formy fleksyjne", "form fleksyjnych"}),
			new WordForms(new String[] {"jednakowa", "jednakowe", "jednakowych"}),
			new WordForms(new String[] {"tabelka", "tabelki", "tabelek"})
		};
		
		PLURAL_PL = new Plural(PluralRules.POLISH, polishWords);
	}
	
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
			case '2':
			case '3':
				wb = Login.createSession("pl.wiktionary.org");
				process(op == '1', op != '3');
				break;
			case '4':
				String source = locationser + "hashmap.ser";
				Map<String, Boolean> map = Misc.deserialize(source);
				map.remove("dice");
				Misc.serialize(map, source);
				break;
			default:
				throw new UnsupportedOperationException();
		}
	}
	
	public static void process(boolean onlyGetData, boolean noWrite) throws IOException, LoginException, InterruptedException, ExecutionException, ClassNotFoundException {
		PageContainer[] pages = wb.getContentOfTransclusions("odmiana-czasownik-hiszpański", Wiki.MAIN_NAMESPACE);
		int verb_count = pages.length;
		
		List<String> verb_templates = Stream.of(pages).map(Page::wrap)
			.flatMap((Page page) -> ParseUtils.getTemplates(
					"odmiana-czasownik-hiszpański",
					Optional.of(page)
						.flatMap(p -> p.getSection("język hiszpański"))
						.flatMap(s -> s.getField(FieldTypes.INFLECTION))
						.map(Field::getContent)
						.orElse("")
				)
				.stream()
				.map((String template) -> ParseUtils.setTemplateParam(template, "czasownik", page.getTitle(), false))
			)
			.collect(Collectors.toList());
		
		String[] verbs = Stream.of(pages).map(PageContainer::getTitle).toArray(String[]::new);
		Files.write(Paths.get(location + "verbos.txt"), Arrays.asList(verbs));
		System.out.printf("Se han extraído %d plantillas de conjugación de %d verbos\n", verb_templates.size(), verb_count);
	    
	    List<String> verb_forms = new ArrayList<>(verb_templates.size() * 51);
	    StringBuilder urlB = new StringBuilder();
	    Pattern p = Pattern.compile("<td( colspan=\"2\")?>[\\*]*(\\[\\[\\w+#es\\|\\w+\\]\\]&nbsp;)?\\[\\[([a-záéíóúüñ]+)");
	    	
	    int size = verb_templates.size();
	    
	    for (int i = 1; i <= size; i++) {
	    	String template = verb_templates.get(i - 1);
	    	
	    	if (ParseUtils.getTemplateParam(template, "zaimek", true) != null) {
	    		if (i != size) {
	    			continue;
	    		}
	    	} else {
	    		template = ParseUtils.setTemplateParam(template, "widoczny", "tak", false);
	    		urlB.append("\n" + template);
	    	}
	    		
	    	if (i % 50 == 0 || i == size) {
	    		String expanded = wb.expandTemplates(urlB.toString());
	    		System.out.printf("Expansión de plantillas %d/%d\n", i, size);
		    	Matcher m = p.matcher(expanded);
		    		
		   		while (m.find()) {
		   			String aux = m.group(3);
		    			
		           	if (!"haber".equals(aux)) {
		           		verb_forms.add(aux);
		           	}
		        }
		    		
		   		urlB = new StringBuilder();
	    	}
	    }
	    	
	    int form_count = verb_forms.size();
	    System.out.printf("Se han extraído %d formas verbales\n", form_count);
	    
	    Files.write(Paths.get(location + "formas.txt"), verb_forms);
	    System.out.println("Archivo \"formas.txt\" actualizado");
	    
	    // exit program
	    if (onlyGetData) {
	    	System.out.println("Finalizando programa - sumario: FALSE, escritura: FALSE.");
	    	return;
	    }
	    
	 	Map<String, Boolean> dictionary;
	 	File f_hm = new File(locationser + "hashmap.ser");
	 	
		if (f_hm.exists()) {
			dictionary = Misc.deserialize(f_hm);
			System.out.println("Tamaño de la lista extraída: " + dictionary.size());
		} else {
			dictionary = new HashMap<>(form_count);
		}
	 	
		PageContainer[] pages2 = wb.getContentOfPages(verb_forms.toArray(new String[verb_forms.size()]));
		Map<String, Integer> cached = new HashMap<>(dictionary.size());
		Map<RAE, Integer> live = new HashMap<>();
		
		for (String form : verb_forms) {
			String content = Stream.of(pages2)
				.filter(page -> page.getTitle().equals(form))
				.map(PageContainer::getText)
				.findAny()
				.orElse(null);

		    int op = -1;
		    						
			if (content != null) {
				Section section = Page.store(form, content).getSection("język hiszpański").orElse(null);
					
				if (section != null) {
					String definitions = section.getField(FieldTypes.DEFINITIONS).get().getContent();
					
					List<String> meanings = Stream.of(definitions.split("\n"))
						.filter(line -> !line.startsWith(":"))
						.collect(Collectors.toList());
					
					boolean isFlex = false;
					boolean isNotFlex = false;
						
					for (String meaning : meanings) {
						if (meaning.contains("{{forma czasownika")) {
							isFlex = true;
						} else {
							isNotFlex = true;
						}
					}
						
					if (isFlex && !isNotFlex) {
						op = 3; // only verb forms
					} else if (!isFlex && isNotFlex) {
						op = 2; // only stand-alone meanings 
					}
				} else {
					op = 1; // no Spanish section
				}
			} else {
				op = 0; // page doesn't exist
			}
			
			if (dictionary.containsKey(form)) {
				cached.put(form, op);
			} else {
				live.put(new RAE(form), op);
			}
		}
		
		System.out.printf("Cacheados: %d/%d (%d sin cachear)\n", cached.size(), verb_forms.size(), live.size());
		System.out.println("Analizando RAE...");
		
		final int BATCH = 25;
		int c = 0;
		int hashsize = 0;
		Set<String> verb_summary = new HashSet<>(500);
		
		ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 5);
		Map<Future<RAE>, Integer> rae_tasks = new HashMap<>(BATCH);
		
		for (Entry<RAE, Integer> entry : live.entrySet()) {
			rae_tasks.put(executor.submit(entry.getKey()), entry.getValue());
			c++;
			
			if (c % BATCH == 0 || c == live.size() - 1) {
				for (Entry<Future<RAE>, Integer> entry2 : rae_tasks.entrySet()) {
					RAE rae = entry2.getKey().get();
					boolean found = rae.exists() && rae.isStandAloneVerbForm();
					String page = String.format("# [[%s]]", rae.getEntry());
					String link = found ? String.format(" ([%s DRAE])", rae.getLink()) : "";
					
					switch (entry2.getValue()) {
						// page doesn't exist
						case 0:
						// only verb forms
						case 3:
							if (!link.isEmpty()) {
								verb_summary.add(page + link);
							}
							break;
						// no Spanish section
						case 1:
							verb_summary.add(page + link);
							break;
						// only stand-alone meanings
						case 2:
							verb_summary.add(page);
							break;
					}
					
					if (!rae.isSerial && !dictionary.containsKey(rae.getEntry())) {
						dictionary.put(rae.getEntry(), found);
						hashsize++;
					}
				}
				
				executor.shutdown();
				Thread.sleep(10000);
				
				System.out.printf(
					"Finalizada iteración %d de %d\n",
					(int) Math.ceil(c / BATCH),
					(int) Math.ceil(live.size() / BATCH)
				);
				
				if (c != live.size() - 1) {
					executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 10);
					rae_tasks = new HashMap<>(BATCH);
				}
			}
		}
		
		int errors = 0;
		
		for (Entry<String, Integer> entry : cached.entrySet()) {
			String page = entry.getKey();
			boolean found = dictionary.get(page);
			String shortlink = String.format("# [[%s]]", page);
			String fulllink = shortlink + (found ? String.format(" ([%s%s DRAE])", RAEurl, page) : "");
			
			switch (entry.getValue()) {
				// page doesn't exist
				case 0:
				// only verb forms
				case 3:
					if (found)
						verb_summary.add(fulllink);
					break;
				// no Spanish section
				case 1:
					verb_summary.add(fulllink);
					break;
				// only stand-alone meanings
				case 2:
					verb_summary.add(shortlink);
					break;
			}
		}
		
		if (errors > 0) {
			System.out.printf("Errores: %d\n", errors);
		}
		
		// deleting repeated entries
		/*for (int i = 0; i < verb_summary.size(); i++) {
			String aux = verb_summary.get(i);
			summ_bld.append(aux + "\n");
			pw_summ.println(aux);
			
			for (int j = i + 1; j < verb_summary.size(); j++) {
				if (verb_summary.get(j).equals(aux)) {
					verb_summary.remove(j--);
				}
			}
		}*/
		
		int summ_count = verb_summary.size();
		List<String> output_list = new ArrayList<>(verb_summary);
		Misc.sortList(output_list, "es");
		String output = String.join("\n", output_list);
		
		Files.write(Paths.get(location + "resumen.txt"), Arrays.asList(String.format(
				"Analizowano %d form fleksyjnych z %d czasowników.\n%s",
				form_count, verb_count, output
			)));
			
		System.out.printf("%d verbos analizados, %d formas extraídas\n", verb_count, form_count);
		System.out.printf("Tamaño de la lista obtenida: %d\n", summ_count);
		
		Misc.serialize(dictionary, f_hm);
		System.out.printf("Nuevo tamaño: %d (+%d)\n", dictionary.size(), hashsize);
		
		// exit program
		if (noWrite) {
	    	System.out.println("Finalizando programa - sumario: TRUE, escritura: FALSE.");
	    	return;
	    }
		
		// editing page
		String wikipage_content = wb.getPageText(wikipage);
			
		StringBuilder content = new StringBuilder(wikipage_content.substring(0, wikipage_content.indexOf("----")));
		content.append("----\n");
		content.append(String.format(
				"Analizowano %s (%s) z %d czasowników (%s odmiany).",
				PLURAL_PL.npl(form_count, " forma fleksyjna"),
				PLURAL_PL.npl(dictionary.size(), " jednakowa"),
				verb_count,
				PLURAL_PL.npl(size, " tabelka")
			));
		content.append("Aktualizacja: ~~~~~.\n");
		content.append("{{język linków|hiszpański}}\n");
			
		wb.edit(wikipage, content.toString() + output, "aktualizacja", false, true, -2, null);
		
		System.out.println("Finalizando programa - sumario: TRUE, escritura: TRUE.");
	}
	
	public static void main(String args[]) {
		Misc.runTimerWithSelector(new SpanishVerbFormsMaintenance());
	}
}

class MyRAE extends RAE {
	Map<String, Boolean> hashmap;
	
	public MyRAE(String entry, Map<String, Boolean> hashmap) {
		super(entry);
		this.hashmap = hashmap;
		this.content = "";
	}
	
	@Override
    public RAE call() throws IOException {
		if (hashmap.containsKey(entry)) {
			isSerial = true;
			isStandAloneVerbForm = hashmap.get(entry);
			exists = isStandAloneVerbForm;
		} else {
			fetchEntry();
		}
		return this;
	}
}