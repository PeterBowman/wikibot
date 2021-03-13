package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.io.xml.StaxDriver;

public final class MissingPolishExamples {
	private static final Pattern P_LINKER = Pattern.compile("\\[\\[\\s*?([^\\]\\|]+)\\s*?(?:\\|\\s*?((?:]?[^\\]\\|])*+))*\\s*?\\]\\]([^\\[]*)", Pattern.DOTALL);
	private static final Pattern P_REF = Pattern.compile("<\\s*ref\\b", Pattern.CASE_INSENSITIVE);
	private static final Path LOCATION = Paths.get("./data/tasks.plwikt/MissingPolishExamples/");
	
	public static void main(String[] args) throws Exception {
		XMLDumpReader reader = getDumpReader(args);
		LocalDate timestamp = extractTimestamp(reader.getFile());
		
		final Set<String> titles;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader().stream()) {
			titles = stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getPolishSection().stream())
				.flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
				.filter(Field::isEmpty)
				.map(f -> f.getContainingSection().get().getContainingPage().get().getTitle())
				.collect(Collectors.toSet());
		}
		
		System.out.printf("%d titles retrieved\n", titles.size());
		Map<String, Set<Backlink>> titlesToBacklinks = new ConcurrentSkipListMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader().stream()) {
			stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
				.filter(f -> !f.isEmpty())
				.forEach(f -> Pattern.compile("\n").splitAsStream(f.getContent())
					.filter(line -> f.getContainingSection().get().isPolishSection()
						|| (line.contains("→") && !P_REF.matcher(line).find()))
					.map(line -> line.substring(line.indexOf('→') + 1))
					.flatMap(line -> P_LINKER.matcher(line).results())
					.map(m -> m.group(1))
					.filter(titles::contains)
					.forEach(target -> titlesToBacklinks.computeIfAbsent(target, k -> new ConcurrentSkipListSet<>())
						.add(Backlink.makeBacklink(
							f.getContainingSection().get().getContainingPage().get().getTitle(),
							f.getContainingSection().get()
						))
					)
				);
		}
		
		System.out.printf("%d titles mapped to backlinks\n", titlesToBacklinks.size());
		
		// XStream doesn't provide converters for ConcurrentSkipListMap nor ConcurrentSkipListSet
		List<Entry> list = titlesToBacklinks.entrySet().stream()
			.map(e -> Entry.makeEntry(e.getKey(), new ArrayList<>(e.getValue())))
			.collect(Collectors.toList());
		
		storeData(list, timestamp);
	}
	
	private static XMLDumpReader getDumpReader(String[] args) throws FileNotFoundException {
		if (args.length == 0) {
			return new XMLDumpReader("plwiktionary");
		} else {
			return new XMLDumpReader(new File(args[0].trim()));
		}
	}
	
	private static LocalDate extractTimestamp(File f) throws ParseException {
		String fileName = f.getName();
		Pattern patt = Pattern.compile("^[a-z]+-(\\d+)-.+");		
		Matcher m = patt.matcher(fileName);
		
		if (!m.matches()) {
			throw new RuntimeException();
		}
		
		String canonicalTimestamp = m.group(1);
		
		try {
			SimpleDateFormat originalDateFormat = new SimpleDateFormat("yyyyMMdd");
			Date date = originalDateFormat.parse(canonicalTimestamp);
			return LocalDate.ofInstant(date.toInstant(), ZoneOffset.UTC);
		} catch (ParseException e) {
			throw e;
		}
	}
	
	private static void storeData(List<Entry> list, LocalDate timestamp) throws IOException {
		Path fEntries = LOCATION.resolve("entries.xml");
		Path fDumpTimestamp = LOCATION.resolve("dump-timestamp.xml");
		Path fBotTimestamp = LOCATION.resolve("bot-timestamp.xml");
		Path fCtrl = LOCATION.resolve("UPDATED");

		XStream xstream = new XStream(new StaxDriver());
		xstream.processAnnotations(Entry.class);

		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(fEntries.toFile()))) {
			xstream.toXML(list, bos);
		}

		Files.write(fDumpTimestamp, List.of(xstream.toXML(timestamp)));
		Files.write(fBotTimestamp, List.of(xstream.toXML(OffsetDateTime.now())));
		
		fCtrl.toFile().delete();
	}
	
	// keep in sync with com.github.wikibot.webapp.MissingPolishExamples
	@XStreamAlias("entry")
	static class Entry {
		@XStreamAlias("t")
		String title;
		
		@XStreamAlias("blt")
		List<String> backlinkTitles = new ArrayList<>();
		
		@XStreamAlias("bls")
		List<String> backlinkSections = new ArrayList<>();
		
		public static Entry makeEntry(String title, List<Backlink> backlinks) {
			Entry entry = new Entry();
			entry.title = title;
			
			backlinks.forEach(bl -> {
				entry.backlinkTitles.add(bl.title);
				entry.backlinkSections.add(bl.langLong);
			});
			
			return entry;
		} 
	}
	
	// keep in sync with com.github.wikibot.webapp.MissingPolishExamples
	@XStreamAlias("bl")
	static class Backlink implements Comparable<Backlink> {
		@XStreamAlias("t")
		String title;
		
		@XStreamAlias("ls")
		String langShort;
		
		@XStreamAlias("ll")
		String langLong;
		
		public static Backlink makeBacklink(String title, Section section) {
			Backlink bl = new Backlink();
			bl.title = title;
			bl.langShort = section.getLangShort();
			bl.langLong = section.getLang();
			return bl;
		} 
		
		@Override
		public int compareTo(Backlink o) {
			if (!title.equals(o.title)) {
				return title.compareTo(o.title);
			}
			
			if (!langShort.equals(o.langShort)) {
				return langShort.compareTo(o.langShort);
			}
			
			return 0;
		}
	}
}
