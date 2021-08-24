package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public final class BoldedSelflinks {
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/BoldedSelflinks/");
	private static final String TARGET_PAGE = "Wikipedysta:PBbot/pogrubione selflinki";
	
	// https://pl.wiktionary.org/wiki/MediaWiki:Gadget-section-links.js
	private static final List<String> IGNORED_SELFLINKS = List.of(
		"znak chiński", "chiński standardowy", "minnan", "japoński", "gan", "ajnoski", "kantoński"
	);
	
	private static final List<String> IGNORED_LANGS = List.of("niemiecki");
	
	private static final Map<FieldTypes, List<String>> IGNORED_FIELDS;
	
	// from Linker::formatLinksInComment in Linker.php
	private static final Pattern P_LINK = Pattern.compile("\\[\\[:?([^\\]|]+)(?:\\|((?:]?[^\\]|])*+))*\\]\\]([^\\[]*)");
	private static final Pattern P_BOLD = Pattern.compile("'{3}([^\\[\\]\\{\\}]+?)'{3}");
	private static final Pattern P_TRANSL = Pattern.compile("→[^•;]+");
	
	private static final Collator COLL_PL = Collator.getInstance(new Locale("pl"));
	
	private static final String PAGE_INTRO;
	
	private static Wikibot wb;
	
	static {
		Map<FieldTypes, List<String>> ignoredFields = new LinkedHashMap<>();
		ignoredFields.put(FieldTypes.EXAMPLES, Collections.emptyList());
		ignoredFields.put(FieldTypes.TRANSLATIONS, Collections.emptyList());
		ignoredFields.put(FieldTypes.ETYMOLOGY, Collections.emptyList());
		ignoredFields.put(FieldTypes.DERIVED_TERMS, Collections.emptyList());
		ignoredFields.put(FieldTypes.NOTES, Collections.emptyList());
		ignoredFields.put(FieldTypes.DEFINITIONS, List.of("polski"));
		
		IGNORED_FIELDS = Collections.unmodifiableMap(ignoredFields);
		
		String excludedSelflinks = IGNORED_SELFLINKS.stream().collect(Collectors.joining(", "));
		String excludedLangs = IGNORED_LANGS.stream().collect(Collectors.joining(", "));
		
		String excludedFields = IGNORED_FIELDS.entrySet().stream()
			.map(e -> !e.getValue().isEmpty()
				? String.format(
					"%s (wyjątki: %s)",
					e.getKey().localised(),
					e.getValue().stream().collect(Collectors.joining(", "))
				)
				: e.getKey().localised())
			.collect(Collectors.joining(", "));
		
		PAGE_INTRO =
			"Zestawienie wystąpień pogrubionych selflinków, czyli linków prowadzących do strony, w której " +
			"się znajdują. Automatyczne pogrubianie takich linków, wymuszone przez oprogramowanie MediaWiki, " +
			"jest zazwyczaj tłumione za sprawą lokalnego [[MediaWiki:Gadget-section-links.js|skryptu JS]], " +
			"o ile dany link nie prowadzi do tej samej sekcji językowej. Wykluczenia:\n" +
			"* języki z wyłączoną obsługą selflinków przez JS – " + excludedSelflinks + ";\n" +
			"* języki źle współpracujące z mechanizmem selflinków – " + excludedLangs + ";\n" +
			"* pola – " + excludedFields + ".\n" +
			"Lista uwzględnia również zwykłe pogrubienia (tekst owinięty znakami <code><nowiki>'''</nowiki></code>, " +
			"niezawierający <code><nowiki>[]{}</nowiki></code>) na powyższych zasadach.\n" +
			"\n" +
			"Dane na podstawie zrzutu z bazy danych z dnia $1. Aktualizacja: ~~~~~.";;
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
				return new XMLDumpReader(pathToFile);
			} else {
				new HelpFormatter().printHelp(BoldedSelflinks.class.getName(), options);
				throw new IllegalArgumentException();
			}
		} else {
			return new XMLDumpReader("plwiktionary");
		}
	}
	
	private static List<Item> analyzeDump(XMLDumpReader reader) throws IOException {
		final Pattern pNewline = Pattern.compile("\n");
		
		try (Stream<XMLRevision> stream = reader.getStAXReader().stream()) {
			return stream
				.filter(XMLRevision::nonRedirect)
				.filter(XMLRevision::isMainNamespace)
				.map(rev -> Page.store(rev.getTitle(), rev.getText()))
				.flatMap(p -> p.getAllSections().stream()
					.filter(s -> !IGNORED_LANGS.contains(s.getLangShort()))
					.flatMap(s -> s.getAllFields().stream()
						.filter(f -> !f.isEmpty())
						.filter(f -> IGNORED_FIELDS.entrySet().stream()
							.allMatch(e -> f.getFieldType() != e.getKey() || e.getValue().contains(s.getLangShort()))
						)
						.flatMap(f -> pNewline.splitAsStream(f.getContent())
							.filter(line -> filterLines(line, p.getTitle(), s.getLangShort()))
							.map(line ->
								new Item(p.getTitle(), s.getLangShort(), f.getFieldType().localised(), line)
							)
						)
					)
				)
				.sorted((i1, i2) -> COLL_PL.compare(i1.title, i2.title))
				.collect(Collectors.toList());
		}
	}
	
	private static boolean filterLines(String line, String title, String lang) {
		if (line.contains("→")) {
			line = P_TRANSL.matcher(line).replaceAll("");
		}
		
		if (!IGNORED_SELFLINKS.contains(lang)) {
			Matcher m = P_LINK.matcher(line);
			
			while (m.find()) {
				String target = m.group(1).trim().replaceFirst("#.*", ""); // ignore URL fragments
				
				if (target.equals(title)) {
					return true;
				}
			}
		}
		
		Matcher m = P_BOLD.matcher(line);
		
		while (m.find()) {
			String target = m.group(1).trim();
			
			if (target.equals(title)) {
				return true;
			}
		}
		
		return false;
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
		String output = list.stream()
			.collect(Collectors.groupingBy(
				item -> item.title,
				() -> new TreeMap<>(COLL_PL),
				Collectors.mapping(
					item -> String.format(
						"#:(%s, %s) <nowiki>%s</nowiki>",
						item.language, item.fieldType, item.line
					),
					Collectors.toList()
				)
			))
			.entrySet().stream()
			.map(entry -> String.format("#[[%s]]%n%s", entry.getKey(), String.join("\n", entry.getValue())))
			.collect(Collectors.joining("\n"));
		
		return PAGE_INTRO.replace("$1", timestamp) + "\n\n" + output;
	}
	
	@XStreamAlias("item")
	private static class Item {
		String title;
		String language;
		String fieldType;
		String line;
		
		Item(String title, String language, String fieldType, String line) {
			this.title = title;
			this.language = language;
			this.fieldType = fieldType;
			this.line = line;
		}
		
		@Override
		public String toString() {
			return String.format("[%s,%s,%s,%s]", title, language, fieldType, line);
		}
		
		@Override
		public int hashCode() {
			return
				title.hashCode() + language.hashCode() +
				fieldType.hashCode() + line.hashCode();
		}
	}
}
