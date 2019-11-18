package com.github.wikibot.tasks.plwikt;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
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
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Login;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.StaxDriver;

public final class MissingPolishExamples {
	private static final Pattern P_LINKER = Pattern.compile("\\[\\[\\s*?([^\\]\\|]+)\\s*?(?:\\|\\s*?((?:]?[^\\]\\|])*+))*\\s*?\\]\\]([^\\[]*)", Pattern.DOTALL);
	private static final String LOCATION = "./data/tasks.plwikt/MissingPolishExamples/";
	
	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.createSession("pl.wiktionary.org");

		XMLDumpReader reader = getDumpReader(args);
		String timestamp = extractTimestamp(reader.getFile());
		int stats = wb.getSiteStatistics().get("pages");
		
		final Set<String> titles;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			titles = stream.parallel()
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
		Map<String, Set<String>> titlesToBacklinks = new ConcurrentSkipListMap<>();
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(Page::wrap)
				.flatMap(p -> p.getAllSections().stream())
				.flatMap(s -> s.getField(FieldTypes.EXAMPLES).stream())
				.filter(f -> !f.isEmpty())
				.forEach(f -> P_LINKER.matcher(f.getContent()).results()
					.map(m -> m.group(1))
					.filter(titles::contains)
					.forEach(target -> titlesToBacklinks.computeIfAbsent(target, k -> new ConcurrentSkipListSet<>())
						.add(String.format("%s#%s",
							f.getContainingSection().get().getContainingPage().get().getTitle(),
							f.getContainingSection().get().getLang()))
					)
				);
		}
		
		System.out.printf("%d titles mapped to backlinks\n", titlesToBacklinks.size());
		storeData(titlesToBacklinks, timestamp);
	}
	
	private static XMLDumpReader getDumpReader(String[] args) throws FileNotFoundException {
		if (args.length == 0) {
			return new XMLDumpReader("pl.wiktionary.org");
		} else {
			return new XMLDumpReader(new File(args[0].trim()));
		}
	}
	
	private static String extractTimestamp(File f) throws ParseException {
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
			SimpleDateFormat desiredDateFormat = new SimpleDateFormat("dd/MM/yyyy");
			return desiredDateFormat.format(date);
		} catch (ParseException e) {
			throw e;
		}
	}
	
	private static void storeData(Map<String, Set<String>> map, String timestamp) throws IOException {
		File fMap = new File(LOCATION + "map.xml");
		File fDumpTimestamp = new File(LOCATION + "dump-timestamp.xml");
		File fBotTimestamp = new File(LOCATION + "bot-timestamp.xml");
		File fCtrl = new File(LOCATION + "UPDATED");

		XStream xstream = new XStream(new StaxDriver());

		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(fMap))) {
			xstream.toXML(map, bos);
		}

		Files.write(fDumpTimestamp.toPath(), List.of(xstream.toXML(OffsetDateTime.now())));
		Files.write(fBotTimestamp.toPath(), List.of(xstream.toXML(timestamp)));
		
		fCtrl.delete();
	}
}
