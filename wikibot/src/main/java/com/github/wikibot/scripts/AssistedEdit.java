package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class AssistedEdit {
	private static final Path LOCATION = Paths.get("./data/scripts/AssistedEdit/");
	private static final Path TITLES = LOCATION.resolve("titles.txt");
	private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
	private static final Path TIMESTAMPS = LOCATION.resolve("timestamps.xml");
	private static final Path HASHCODES = LOCATION.resolve("hash.xml");
	
	private static Wikibot wb;
	private static XStream xstream;
	
	public static void main(String[] args) throws Exception {
		Options options = new Options();
		options.addRequiredOption("o", "op", true, "mode of operation: query|apply");
		options.addRequiredOption("d", "domain", true, "wiki domain name");
		options.addOption("s", "summary", true, "edit summary");
		options.addOption("m", "minor", false, "mark edits as minor");
		options.addOption("t", "throttle", true, "set edit throttle [ms]");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		CommandLineParser parser = new DefaultParser();
		CommandLine line = parser.parse(options, args);
		
		wb = Login.createSession(line.getOptionValue("domain"));
		xstream = new XStream();
		
		String throttle = line.getOptionValue("throttle", "5000");
		wb.setThrottle(Integer.parseInt(throttle));
		
		switch (line.getOptionValue("op")) {
			case "query":
				getContents();
				break;
			case "apply":
				applyChanges(line.getOptionValue("summary"), line.hasOption("minor"));
				break;
			default:
				new HelpFormatter().printHelp(AssistedEdit.class.getName(), options);
				throw new IllegalArgumentException();
		}
	}
	
	public static void getContents() throws IOException {
		List<String> titles = Files.lines(TITLES)
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.distinct()
			.collect(Collectors.toList());
		
		System.out.printf("Size: %d%n", titles.size());
		
		if (titles.isEmpty()) {
			return;
		}

		List<PageContainer> pages = wb.getContentOfPages(titles);
		
		Map<String, String> map = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getText,
				(a, b) -> a,
				LinkedHashMap::new
			));
		
		Files.write(WORKLIST, List.of(Misc.makeList(map)));
		
		Map<String, OffsetDateTime> timestamps = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
			));
		
		Files.writeString(TIMESTAMPS, xstream.toXML(timestamps));
		
		Map<String, Integer> hashcodes = pages.stream()
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				pc -> pc.getText().hashCode()
			));
		
		Files.writeString(HASHCODES, xstream.toXML(hashcodes));
	}
	
	public static void applyChanges(String summary, boolean minor) throws IOException {
		Map<String, String> map = Misc.readList(Files.lines(WORKLIST).toArray(String[]::new));
		final var initialSize = map.size();
		System.out.printf("Size: %d%n", initialSize);
		
		@SuppressWarnings("unchecked")
		var timestamps = (Map<String, OffsetDateTime>) xstream.fromXML(Files.readString(TIMESTAMPS));
		
		@SuppressWarnings("unchecked")
		var hashcodes = (Map<String, Integer>) xstream.fromXML(Files.readString(HASHCODES));
		map.entrySet().removeIf(e -> e.getValue().hashCode() == hashcodes.get(e.getKey()));
		
		if (map.size() != initialSize) {
			System.out.printf("Revised size: %d%n", map.size());
		}
		
		if (map.isEmpty()) {
			return;
		}
		
		List<String> errors = new ArrayList<>();
		
		for (var entry : map.entrySet()) {
			String title = entry.getKey();
			String text = entry.getValue();
			
			try {
				wb.edit(title, text, summary, minor, true, -2, timestamps.get(title));
			} catch (Throwable t) {
    			t.printStackTrace();
    			errors.add(title);
    		}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errors: %s%n", errors.size(), errors.toString());
		}
		
		Files.move(WORKLIST, WORKLIST.resolveSibling("done.txt"), StandardCopyOption.REPLACE_EXISTING);
	}
}