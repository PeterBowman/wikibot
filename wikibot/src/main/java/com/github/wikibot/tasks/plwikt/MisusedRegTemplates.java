package com.github.wikibot.tasks.plwikt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikiutils.ParseUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.Users;

public final class MisusedRegTemplates {
	private static final String LOCATION = "./data/tasks.plwikt/MisusedRegTemplates/";
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/kategoryzacja regionalizmów";
	private static final String PAGE_INTRO;
	
	private static final List<String> TEMPLATES = Arrays.asList(
		// Polish
		"białystok", "częstochowa", "góry", "kielce", "kraków", "kresy", "kujawy", "łódź",
		"lwów", "mazowsze", "podhale", "poznań", "śląsk", "warszawa",
		// English
		"szkocang",
		// Arabic
		"algierarab", "egiparab", "lewantarab", "libijarab", "marokarab", "tunezarab",
		// French
		"akadfranc", "belgfranc", "kanadfranc", "szwajcfranc",
		// Spanish
		"reg-es",
		// Dutch
		"belghol", "surinhol",
		// Korean
		"korpłd", "korpłn",
		// German
		"szwajcniem",
		// Portuguese
		"brazport",
		// Italian
		"szwajcwł", "tosk"
	);
	
	private static PLWikt wb;
	
	static {
		String templateList = TEMPLATES.stream()
			.map(template -> String.format("{{s|%s}}", template))
			.collect(Collectors.joining(", "));
		
		PAGE_INTRO =
			"Lista nieprawidłowo użytych [[:Kategoria:Szablony dialektów i gwar|szablonów regionalizmów]]. " +
			"Wskazane tutaj wystąpienia skutkują zwykle niezamierzonym umieszczeniem strony w kategorii, " +
			"często w wyniku pominięcia pierwszego parametru w polu innym niż „znaczenia” " +
			"(zasady działania szablonów mogą się różnić, zapoznaj się z instrukcją na ich stronie opisu)." +
			"\n\n" +
			"Rozpoznawane szablony: " + templateList + "." +
			"\n\n" +
			"Dane na podstawie zrzutu z bazy danych z dnia $1. Znaleziono $2 na $3. Aktualizacja: ~~~~~";
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		XMLDumpReader reader = getXMLReader(args);
		List<Item> list = analyzeDump(reader);
		
		if (!checkAndUpdateStoredData(list)) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		String timestamp = extractTimestamp(reader.getFile());
		String pageText = makePageText(list, timestamp);
		
		wb.setMarkBot(false);
		wb.edit(TARGET_PAGE, pageText, "aktualizacja");
	}
	
	private static XMLDumpReader getXMLReader(String[] args) throws ParseException, FileNotFoundException {
		if (args.length != 0) {
			Options options = new Options();
			options.addOption("d", "dump", true, "read from dump file");
			
			CommandLineParser parser = new DefaultParser();
			CommandLine line = parser.parse(options, args);
			
			if (line.hasOption("dump")) {
				File f = new File(line.getOptionValue("dump"));
				return new XMLDumpReader(f);
			} else {
				new HelpFormatter().printHelp(MisusedRegTemplates.class.getName(), options);
				throw new IllegalArgumentException();
			}
		} else {
			return new XMLDumpReader(Domains.PLWIKT);
		}
	}
	
	private static List<Item> analyzeDump(XMLDumpReader reader) throws IOException {
		int size = wb.getSiteStatistics().get("pages");
		List<Item> list;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(size).stream()) {
			list = stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(MisusedRegTemplates::containsTemplates)
				.map(Page::wrap)
				.flatMap(MisusedRegTemplates::extractItemsFromPage)
				.sorted()
				.collect(Collectors.toList());
		}
		
		System.out.printf("%d items found%n", list.size());
		
		return list;
	}
	
	private static boolean containsTemplates(XMLRevision rev) {
		return TEMPLATES.stream()
			.anyMatch(template -> !ParseUtils.getTemplates(template, rev.getText()).isEmpty());
	}
	
	private static Stream<Item> extractItemsFromPage(Page page) {
		return page.getAllSections().stream()
			.map(Section::getAllFields)
			.flatMap(Collection::stream)
			.filter(field -> field.getFieldType() != FieldTypes.DEFINITIONS)
			.filter(field -> !field.isEmpty())
			.flatMap(field -> TEMPLATES.stream()
				.map(template -> ParseUtils.getTemplates(template, field.getContent()))
				.flatMap(Collection::stream)
				.map(ParseUtils::getTemplateParametersWithValue)
				.filter(MisusedRegTemplates::filterTemplates)
				.map(params -> Item.constructNewItem(field, params))
			);
	}
	
	private static boolean filterTemplates(Map<String, String> params) {
		if (params.get("templateName").equals("reg-es")) {
			return params.getOrDefault("ParamWithoutName2", "").isEmpty();
		}
		
		return params.entrySet().stream()
			.filter(entry -> !entry.getKey().equals("templateName"))
			.filter(entry -> !TEMPLATES.contains(entry.getValue()))
			.count() == 0;
	}
	
	private static boolean checkAndUpdateStoredData(List<Item> list) throws FileNotFoundException, IOException {
		int newHashCode = list.hashCode();
		int storedHashCode;
		
		File fHash = new File(LOCATION + "hash.ser");
		
		try {
			storedHashCode = Misc.deserialize(fHash);
		} catch (ClassNotFoundException | IOException e) {
			storedHashCode = 0;
		}
		
		if (storedHashCode != newHashCode) {
			Misc.serialize(newHashCode, fHash);
			Misc.serialize(list, LOCATION + "list.ser");
			return true;
		} else {
			return false;
		}
	}
	
	private static String extractTimestamp(File f) {
		String fileName = f.getName();
		Pattern patt = Pattern.compile("^[a-z]+-(\\d+)-.+");
		String errorString = String.format("(błąd odczytu sygnatury czasowej, plik ''%s'')", fileName);
		
		Matcher m = patt.matcher(fileName);
		
		if (!m.matches()) {
			return errorString;
		}
		
		String canonicalTimestamp = m.group(1);
		
		try {
			SimpleDateFormat originalDateFormat = new SimpleDateFormat("yyyyMMdd");
			Date date = originalDateFormat.parse(canonicalTimestamp);
			SimpleDateFormat desiredDateFormat = new SimpleDateFormat("dd/MM/yyyy");
			return desiredDateFormat.format(date);
		} catch (java.text.ParseException e) {
			return errorString;
		}
	}
	
	private static String makePageText(List<Item> list, String timestamp) {
		Map<String, List<String>> groupedMap = list.stream()
			.collect(Collectors.groupingBy(
				item -> item.pageTitle,
				LinkedHashMap::new,
				Collectors.mapping(
					item -> String.format(
						"&#123;{%s}} (%s, %s)",
						item.templateName, item.langName, item.fieldType.localised
					),
					Collectors.toList()
				)
			));
		
		String elements = groupedMap.entrySet().stream()
			.collect(Collectors.mapping(
				entry -> String.format(
					"# [[%s]]: %s",
					entry.getKey(), String.join(" • ", entry.getValue())
				),
				Collectors.joining("\n")
			));
		
		String itemCount = Misc.makePluralPL(list.size(), "wystąpienie", "wystąpienia", "wystąpień");
		String pageCount = Misc.makePluralPL(groupedMap.size(), "stronie", "stronach", "stronach");
		
		String intro = PAGE_INTRO.replace("$1", timestamp).replace("$2", itemCount).replace("$3", pageCount);
		
		return intro + "\n\n" + elements;
	}
	
	private static class Item implements Serializable, Comparable<Item> {
		private static final long serialVersionUID = -8690746740605131323L;
		
		String pageTitle;
		String langName;
		FieldTypes fieldType;
		String templateName;
		
		Item(String pageTitle, String langName, FieldTypes fieldType, String templateName) {
			this.pageTitle = pageTitle;
			this.langName = langName;
			this.fieldType = fieldType;
			this.templateName = templateName;
		}
		
		static Item constructNewItem(Field field, Map<String, String> params) {
			Section s = field.getContainingSection().get();
			String pageTitle = s.getContainingPage().get().getTitle();
			String langName = s.getLang();
			FieldTypes fieldType = field.getFieldType();
			String templateName = params.get("templateName");
			return new Item(pageTitle, langName, fieldType, templateName);
		}
		
		@Override
		public String toString() {
			return String.format("[%s,%s,%s,%s]", pageTitle, langName, fieldType, templateName);
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			
			if (!(o instanceof Item)) {
				return false;
			}
			
			Item i = (Item) o;
			
			return
				pageTitle.equals(i.pageTitle) && langName.equals(i.langName) &&
				fieldType == i.fieldType && templateName.equals(i.templateName);
		}
		
		@Override
		public int hashCode() {
			return
				pageTitle.hashCode() + langName.hashCode() +
				fieldType.hashCode() + templateName.hashCode();
		}
		
		@Override
		public int compareTo(Item i) {
			if (!pageTitle.equals(i.pageTitle)) {
				return pageTitle.compareTo(i.pageTitle);
			}
			
			if (!langName.equals(i.langName)) {
				return langName.compareTo(i.langName);
			}
			
			if (fieldType != i.fieldType) {
				return fieldType.compareTo(i.fieldType);
			}
			
			if (!templateName.equals(i.templateName)) {
				return templateName.compareTo(i.templateName);
			}
			
			return 0;
		}
	}
}
