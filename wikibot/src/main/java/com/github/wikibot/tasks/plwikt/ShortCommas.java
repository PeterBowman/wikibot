package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.LoginException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.wikiutils.IOUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class ShortCommas {
	private static PLWikt wb;
	
	private static final String LOCATION = "./data/tasks.plwikt/ShortCommas/";
	private static final Set<String> SHORTS_SET;
	
	private static final Pattern P_SHORT_SHORT;
	private static final Pattern P_SHORT_ANNOTATION;
	private static final Pattern P_SHORT_SEMICOLON;
	
	private static final String SHORT_SHORT_REPL = "$1{{$2}} {{$3$4";
	private static final String SHORT_ANNOTATION_REPL = "$1{{$2}} ''$3";
	private static final String SHORT_SEMICOLON_REPL = "$1{{$2}} {{zob|$3";
	
	private static final List<String> SHORT_TEMPLATES_CATS = Arrays.asList(
		"Szablony skrótów", "Szablony skrótów - obcojęzyczne", "Szablony skrótów nazw języków",
		"Szablony niejednoznacznych skrótów nazw języków"
	);
	
	private static final List<String> SHORT_TEMPLATES_IGNORED_CATS = Arrays.asList(
		"Szablony skrótów - gramatyka", "Szablony skrótów - deklinacja", "Szablony skrótów - obcojęzyczne"
	);
	
	static {
		SHORTS_SET = Utils.readLinesFromResource("/plwikt/short-templates.txt", ShortCommas.class)
			.collect(Collectors.toSet());
		
		System.out.printf("%d values extracted from resource%n", SHORTS_SET.size());
		
		final String joined = String.join("|", SHORTS_SET);
		
		P_SHORT_SHORT = Pattern.compile("^(.*)\\{\\{ *(" + joined + ") *\\}\\} *, *\\{\\{ *(" + joined + ") *?([}|].*)$", Pattern.MULTILINE);
		P_SHORT_ANNOTATION = Pattern.compile("^(.*)\\{\\{ *(" + joined + ") *\\}\\} *, *'{2}([^'].*)$", Pattern.MULTILINE);
		P_SHORT_SEMICOLON = Pattern.compile("^(.*)\\{\\{ *(" + joined + ") *\\}\\} *; *\\{\\{ *zob *\\|(.*)$", Pattern.MULTILINE);
	}
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		CommandLine line = readOptions(args);
		
		if (line == null) {
			return;
		} else if (line.hasOption("analyze")) {
			XMLDumpReader reader = new XMLDumpReader(Domains.PLWIKT);
			analyzePages(reader);
		} else if (line.hasOption("edit")) {
			List<PageContainer> list = Misc.deserialize(LOCATION + "worklist.ser");
			editPages(list, line.hasOption("verify"));
		} else if (line.hasOption("check")) {
			checkStoredTemplates();
		} else if (line.hasOption("dump")) {
			XMLDumpReader reader = new XMLDumpReader(line.getOptionValue("dump"));
			List<PageContainer> list = analyzePages(reader);
			editPages(list, false);
		} else {
			System.out.println("No options supplied");
		}
	}
	
	private static CommandLine readOptions(String[] args) {
		Options options = new Options();
		
		options.addOption("a", "analyze", false, "retrieve list of affected pages");
		options.addOption("e", "edit", false, "apply changes");
		options.addOption("c", "check", false, "check stored templates");
		options.addOption("v", "verify", false, "verify diff list");
		options.addOption("d", "dump", true, "read from dump file");
		
		if (args.length == 0) {
			System.out.print("Option: ");
			String input = Misc.readLine();
			args = input.split(" ");
		}
		
		CommandLineParser parser = new DefaultParser();
		
		try {
			return parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e);
			HelpFormatter help = new HelpFormatter();
			help.printHelp(InconsistentHeaderTitles.class.getName(), options);
			return null;
		}
	}
	
	private static List<PageContainer> analyzePages(XMLDumpReader reader) throws IOException {
		Set<String> wth = new HashSet<>(Arrays.asList(wb.whatTranscludesHere("Szablon:skrót", 0)));
		int size = wb.getSiteStatistics().get("pages");
		String[] titles;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(size).stream()) {
			titles = stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.filter(rev -> wth.contains(rev.getTitle()))
				.filter(ShortCommas::filterPages)
				.map(XMLRevision::getTitle)
				.toArray(String[]::new);
		}
		
		System.out.printf("%d result(s) found in dump file%n", titles.length);
		
		PageContainer[] pages = wb.getContentOfPages(titles, 150);
		Map<String, Collection<String>> map = new ConcurrentSkipListMap<>();
		
		List<PageContainer> processedPages = Stream.of(pages).parallel()
			.map(pc -> processPage(pc, map))
			.filter(Objects::nonNull)
			.sorted((pc1, pc2) -> pc1.getTitle().compareTo(pc2.getTitle()))
			.collect(Collectors.toList());
		
		System.out.printf("%d page(s) filtered and processed%n", processedPages.size());
		
		String[] targetTitles = processedPages.stream().map(PageContainer::getTitle).toArray(String[]::new);
		final String timestamp = makeTimestamp();
		
		IOUtils.writeToFile(String.join("\n", targetTitles), LOCATION + timestamp + "-titles.txt");
		IOUtils.writeToFile(Misc.makeMultiList(map), LOCATION + timestamp + "-diffs.txt");
		
		Misc.serialize(processedPages, LOCATION + "worklist.ser");
		
		return processedPages;
	}
	
	private static boolean filterPages(XMLRevision xml) {
		return
			P_SHORT_SHORT.matcher(xml.getText()).find() ||
			P_SHORT_ANNOTATION.matcher(xml.getText()).find() ||
			P_SHORT_SEMICOLON.matcher(xml.getText()).find();
	}
	
	private static PageContainer processPage(PageContainer pc, Map<String, Collection<String>> map) {
		String text = pc.getText();
		List<String> diffs = new ArrayList<>();
		String temp = text;
		
		// Must loop this due to the presence of template chains: {{A}}, {{B}}, {{C}}...
		while (true) {
			text = applyReplacement(text, SHORT_SHORT_REPL, P_SHORT_SHORT, diffs);
			
			if (temp.equals(text)) {
				break;
			} else {
				temp = text;
			}
		}
		
		text = applyReplacement(text, SHORT_ANNOTATION_REPL, P_SHORT_ANNOTATION, diffs);
		text = applyReplacement(text, SHORT_SEMICOLON_REPL, P_SHORT_SEMICOLON, diffs);
		
		if (diffs.isEmpty()) {
			return null;
		} else {
			map.put(pc.getTitle(), diffs);
			text = Utils.sanitizeWhitespaces(text);
			return new PageContainer(pc.getTitle(), text, pc.getTimestamp());
		}
	}
	
	private static String applyReplacement(String text, String replacement, Pattern patt, List<String> diffs) {
		StringBuffer sb = new StringBuffer(text.length());
		Matcher m = patt.matcher(text);
		
		while (m.find()) {
			String original = m.group();
			String replaced = patt.matcher(original).replaceFirst(replacement);
			diffs.add(original + "\n" + replaced);
			m.appendReplacement(sb, "");
			sb.append(replaced);
		}
		
		return m.appendTail(sb).toString();
	}
	
	private static String makeTimestamp() {
		Date date = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		return dateFormat.format(date);
	}
	
	private static void editPages(List<PageContainer> pages, boolean verify) throws LoginException, IOException {
		System.out.printf("Worklist size: %d%n", pages.size());
		
		if (verify) {
			try {
				String[] lines = IOUtils.loadFromFile(LOCATION + "verified.txt", "", "UTF8");
				Map<String, String[]> map = Misc.readMultiList(lines);
				pages.removeIf(pc -> !map.containsKey(pc.getTitle()));
				System.out.printf("Worklist size after manual verification: %d%n", pages.size());
				
				if (pages.isEmpty()) {
					return;
				}
			} catch (FileNotFoundException e) {
				System.out.println(e);
				return;
			}
		}
		
		final String summary = "usunięcie znaku oddzielającego między kwalifikatorami";
		List<String> errors = new ArrayList<>();
		
		wb.setThrottle(5000);
		wb.setMarkMinor(true);
		wb.setMarkBot(true);
		
		for (PageContainer page : pages) {
			try {
				wb.edit(page.getTitle(), page.getText(), summary, page.getTimestamp());
			} catch (CredentialException e) {
				errors.add(page.getTitle());
				continue;
			} catch (Throwable t) {
				break;
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d error(s):%n%s%n", errors.size(), errors);
			Misc.serialize(errors, LOCATION + "errors.ser");
		}
	}
	
	private static void checkStoredTemplates() throws IOException {
		String namespaceIdentifier = wb.namespaceIdentifier(PLWikt.TEMPLATE_NAMESPACE) + ":";
		UnaryOperator<String> stripPrefix = template -> template.replace(namespaceIdentifier, "");
		Set<String> checked = new HashSet<>(600, 1);
		
		// TODO: find redirects, too
		for (String category : SHORT_TEMPLATES_CATS) {
			String[] templates = wb.getCategoryMembers(category, PLWikt.TEMPLATE_NAMESPACE);
			Stream.of(templates).map(stripPrefix).forEach(checked::add);
		}
		
		for (String category : SHORT_TEMPLATES_IGNORED_CATS) {
			String[] templates = wb.getCategoryMembers(category, PLWikt.TEMPLATE_NAMESPACE);
			Stream.of(templates).map(stripPrefix).forEach(checked::remove);
		}
		
		checked.removeAll(Arrays.asList("skrót", "skrót1", "skrót2", "skrót3"));
		
		if (checked.isEmpty()) {
			System.out.println("No templates extracted!");
			return;
		}
		
		List<String> added = new ArrayList<>(checked);
		added.removeAll(SHORTS_SET);
		
		List<String> removed = new ArrayList<>(SHORTS_SET);
		removed.removeAll(checked);
		
		Collator collator = Misc.getCollator("pl");
		
		if (!added.isEmpty()) {
			added.sort(collator);
			System.out.printf("%d templates added: %s%n", added.size(), added);
			IOUtils.writeToFile(String.join("\n", added), LOCATION + "added.txt");
		}
		
		if (!removed.isEmpty()) {
			removed.sort(collator);
			System.out.printf("%d unrecognized templates: %s%n", removed.size(), removed);
			IOUtils.writeToFile(String.join("\n", removed), LOCATION + "removed.txt");
		}
	}
}
