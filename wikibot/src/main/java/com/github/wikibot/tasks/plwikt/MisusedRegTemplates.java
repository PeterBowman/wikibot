package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public final class MisusedRegTemplates {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MisusedRegTemplates/");
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/kategoryzacja regionalizmów";
	private static final String PAGE_INTRO;
	private static final Plural PLURAL_PL;
	private static final LocalizedNumberFormatter NUMBER_FORMAT_PL;
	
	private static final List<String> TEMPLATES = List.of(
		// Polish
		"reg-pl", "gw-pl",
		// Polish (deprecated)
		"białystok", "częstochowa", "góry", "kielce", "kraków", "kresy", "kujawy", "łódź",
		"lwów", "mazowsze", "podhale", "poznań", "roztoczański", "sącz", "śląsk", "warmia",
		"warszawa", "zaol",
		// English
		"szkocang",
		// Arabic
		"algierarab", "egiparab", "hasarab", "lewantarab", "libijarab", "marokarab", "tunezarab",
		// French
		"akadfranc", "belgfranc", "kanadfranc", "szwajcfranc",
		// Spanish
		"reg-es",
		// Dutch
		"belghol", "surinhol",
		// Korean
		"korpłd", "korpłn",
		// German
		"austr", "płdniem", "płnniem", "szwajcniem",
		// Portuguese
		"brazport", "eurport",
		// Italian
		"szwajcwł", "tosk"
	);
	
	private static final List<String> TEMPLATES_NEW_GEN = List.of(
		"reg-pl", "gw-pl", "reg-es"
	);
	
	private static Wikibot wb;
	
	static {
		String templateList = TEMPLATES.stream()
			.map(template -> String.format("{{s|%s}}", template))
			.collect(Collectors.joining(", "));
		
		PAGE_INTRO = String.format("""
			Lista nieprawidłowo użytych [[:Kategoria:Szablony dialektów i gwar|szablonów regionalizmów]].
			Wskazane tutaj wystąpienia skutkują zwykle niezamierzonym umieszczeniem strony w kategorii,
			często w wyniku pominięcia pierwszego parametru w polu innym niż „znaczenia”
			(zasady działania szablonów mogą się różnić, zapoznaj się z instrukcją na ich stronie opisu).
			
			Rozpoznawane szablony: %s.
			
			Dane na podstawie zrzutu z bazy danych z dnia $1. Znaleziono $2 na $3. Aktualizacja: ~~~~~.
			----
			""", templateList);
		
		WordForms[] polishWords = new WordForms[] {
			new WordForms(new String[] {"wystąpienie", "wystąpienia", "wystąpień"}),
			new WordForms(new String[] {"stronie", "stronach", "stronach"})
		};
		
		PLURAL_PL = new Plural(PluralRules.POLISH, polishWords);
		
		NUMBER_FORMAT_PL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");
		
		XMLDumpReader reader = getXMLReader(args);
		List<Item> list = analyzeDump(reader);
		
		if (!checkAndUpdateStoredData(list)) {
			System.out.println("No changes detected, aborting.");
			return;
		}
		
		String timestamp = extractTimestamp(reader.getPathToDump());
		String pageText = makePageText(list, timestamp);
		
		wb.setMarkBot(false);
		wb.edit(TARGET_PAGE, pageText, "aktualizacja");
	}
	
	private static XMLDumpReader getXMLReader(String[] args) throws ParseException, IOException {
		if (args.length != 0) {
			Options options = new Options();
			options.addOption("d", "dump", true, "read from dump file");
			
			CommandLineParser parser = new DefaultParser();
			CommandLine line = parser.parse(options, args);
			
			if (line.hasOption("dump")) {
				String pathToFile = line.getOptionValue("dump");
				return new XMLDumpReader(Paths.get(pathToFile));
			} else {
				new HelpFormatter().printHelp(MisusedRegTemplates.class.getName(), options);
				throw new IllegalArgumentException();
			}
		} else {
			return new XMLDumpReader("plwiktionary");
		}
	}
	
	private static List<Item> analyzeDump(XMLDumpReader reader) throws IOException {
		List<Item> list;
		
		try (Stream<XMLRevision> stream = reader.getStAXReaderStream()) {
			list = stream
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
			.filter(field -> !field.isEmpty())
			.flatMap(field -> TEMPLATES.stream()
				.map(template -> ParseUtils.getTemplates(template, field.getContent()))
				.flatMap(Collection::stream)
				.map(ParseUtils::getTemplateParametersWithValue)
				.filter(params -> filterTemplates(params, field.getFieldType() == FieldTypes.DEFINITIONS))
				.map(params -> Item.constructNewItem(field, params))
			);
	}
	
	private static boolean filterTemplates(Map<String, String> params, boolean isDefinition) {
		if (TEMPLATES_NEW_GEN.contains(params.get("templateName"))) {
			return isDefinition ^ params.getOrDefault("ParamWithoutName2", "").isEmpty();
		}
		
		return isDefinition ^ params.entrySet().stream()
			.filter(entry -> !entry.getKey().equals("templateName"))
			.filter(entry -> !TEMPLATES.contains(entry.getValue()))
			.count() == 0;
	}
	
	private static boolean checkAndUpdateStoredData(List<Item> list) throws IOException {
		int newHashCode = list.hashCode();
		int storedHashCode;
		
		Path fHash = LOCATION.resolve("hash.txt");
		Path fList = LOCATION.resolve("list.xml");
		
		try {
			storedHashCode = Integer.parseInt(Files.readString(fHash));
		} catch (IOException | NumberFormatException e) {
			e.printStackTrace();
			storedHashCode = 0;
		}
		
		XStream xstream = new XStream();
		xstream.processAnnotations(Item.class);
		
		if (storedHashCode != newHashCode) {
			Files.writeString(fHash, Integer.toString(newHashCode));
			Files.writeString(fList, xstream.toXML(list));
			return true;
		} else {
			return false;
		}
	}
	
	private static String extractTimestamp(Path path) {
		String fileName = path.getFileName().toString();
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
					Item::buildEntry,
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
		
		String itemCount = String.format("%s %s", NUMBER_FORMAT_PL.format(list.size()), PLURAL_PL.pl(list.size(), "wystąpienie"));
		String pageCount = String.format("%s %s", NUMBER_FORMAT_PL.format(groupedMap.size()), PLURAL_PL.pl(groupedMap.size(), "stronie"));
		
		String intro = PAGE_INTRO.replace("$1", timestamp).replace("$2", itemCount).replace("$3", pageCount);
		
		return intro + elements;
	}
	
	@XStreamAlias("item")
	private static class Item implements Comparable<Item> {
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
		
		public String buildEntry() {
			String format = "&#123;{%s}} (%s, %s)";
			
			if (fieldType == FieldTypes.DEFINITIONS) {
				format = "&#123;{%s}} (%s, '''%s''')";
			}
			
			return String.format(format, templateName, langName, fieldType.localised());
		}
		
		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			} else if (o instanceof Item i) {
				return
					pageTitle.equals(i.pageTitle) && langName.equals(i.langName) &&
					fieldType == i.fieldType && templateName.equals(i.templateName);
			} else {
				return false;
			}
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
