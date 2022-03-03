package com.github.wikibot.scripts.wd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.github.wikibot.utils.Misc;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.annotations.XStreamAlias;

public final class FetchEpwnBiograms {
	private static final Path LOCATION = Paths.get("./data/scripts.wd/FetchEpwnBiograms/");
	private static final Path LAST_RUN = LOCATION.resolve("last_run.json");
	private static final Path STORAGE = LOCATION.resolve("storage.xml");
	private static final Path STORAGE_FALLBACK = LOCATION.resolve("storage-fallback.txt");
	
	private static final String INDEX_URL_FORMAT = "https://encyklopedia.pwn.pl/lista/%c;%d.html";
	private static final String ENTRY_URL_FORMAT = "https://encyklopedia.pwn.pl/haslo/;%d.html";
	
	private static final Pattern PATT_ENTRY_URL = Pattern.compile("^https://encyklopedia\\.pwn\\.pl/haslo/([A-Za-z-]++);(\\d++)\\.html$");
	
	private static final int TIMEOUT_MS = 2500;
	private static final int THROTTLE_SHORT_MS = 5000;
	private static final int THROTTLE_LONG_MS = 60000;
	private static final int MAX_RETRIES = 5;
	
	private static long lastThrottled = 0;
	
	private static final List<Character> INDEXES = List.of(
		'A', 'B', 'C', 'Ć', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'Ł', 'M', 'N', 'O', 'Ó', 'P', 'Q', 'R', 'S',
		'Ś', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'Ź', 'Ż'
	);
	
	private static final XStream xstream = new XStream();
	
	private static final Pattern PATT_ENTRY_LEAD = Pattern.compile("""
		^
		(?:
			data\\ ur\\.\\ nieznana
			|
			ur\\.\\ (?:ok\\.\\ )?(?<birthDate>(?:(?:\\d+\\ )?[IVX]+\\ )?\\d+
				(?:\\ (?:r\\.\\ )?(?:p\\.)?n\\.e\\.)?)(?:\\(\\?\\))?
		)
		(?:,\\ (?<birthPlace>.+?))??
		(?:,\\ #
			(?:
				data\\ śmierci\\ nieznana
				|
				zm\\.\\ (?:ok\\.\\ )?(?<deathDate>(?:(?:\\d+\\ )?[IVX]+\\ )?\\d+
					(?:\\ (?:r\\.\\ )?(?:p\\.)?n\\.e\\.)?)(?:\\(\\?\\))?
			)
			(?:,\\ (?<deathPlace>.+?))?
		)?
		[,;]?$
		""", Pattern.COMMENTS);
	
	private static final DateTimeFormatter DATE_FORMATTER;
	
	static {
		xstream.processAnnotations(EpwnBiogram.class);
		xstream.allowTypes(new Class[] {EpwnBiogram.class});
		
		var romanMonths = Map.ofEntries(
			Map.entry(1L, "I"),
			Map.entry(2L, "II"),
			Map.entry(3L, "III"),
			Map.entry(4L, "IV"),
			Map.entry(5L, "V"),
			Map.entry(6L, "VI"),
			Map.entry(7L, "VII"),
			Map.entry(8L, "VIII"),
			Map.entry(9L, "IX"),
			Map.entry(10L, "X"),
			Map.entry(11L, "XI"),
			Map.entry(12L, "XII")
		);
		
		var polishEras = Map.of(
			(long)IsoEra.BCE.getValue(), "p.n.e.",
			(long)IsoEra.CE.getValue(), "n.e."
		);
		
		DATE_FORMATTER = new DateTimeFormatterBuilder()
			.appendOptional(new DateTimeFormatterBuilder()
				.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
				.appendLiteral(' ')
				.toFormatter()
			)
			.appendOptional(new DateTimeFormatterBuilder()
				.appendText(ChronoField.MONTH_OF_YEAR, romanMonths)
				.appendLiteral(' ')
				.toFormatter()
			)
			.appendValue(ChronoField.YEAR_OF_ERA, 1, 4, SignStyle.NEVER)
			.appendOptional(new DateTimeFormatterBuilder().appendLiteral(" r.").toFormatter())
			.appendOptional(new DateTimeFormatterBuilder()
				.appendLiteral(' ')
				.appendText(ChronoField.ERA, polishEras)
				.toFormatter()
			)
			.toFormatter();
	}
	
	public static void main(String[] args) throws Exception {
		var line = readOptions(args);
		var storage = retrieveEntries();
		var initialHash = storage.hashCode();
		
		try {
			var lastRun = line.hasOption("continue") ? retrieveRun() : null;
			
			if (line.hasOption("all")) {
				for (var index : INDEXES) {
					parseIndex(index, storage, lastRun);
				}
			} else if (line.hasOption("index")) {
				var index = Character.toTitleCase(line.getOptionValue("index").charAt(0));
				
				if (!INDEXES.contains(index)) {
					throw new IllegalArgumentException("invalid index: " + index);
				}
				
				parseIndex(index, storage, lastRun);
			} else if (line.hasOption("entry")) {
				var id = Long.parseLong(line.getOptionValue("entry"));
				var biogram = parseEntry(id); // only for debug purposes, IOException not handled
				System.out.println(biogram);
				
				if (biogram != null) {
					storage.add(biogram);
				}
			} else {
				throw new IllegalArgumentException("missing option");
			}
		} catch (FailedRunException e) {
			System.out.println(e.getMessage());
			storeRun(e.getFailedRun());
			throw e; // propagate to runner environment
		} finally {
			if (storage.hashCode() != initialHash) {
				storeEntries(storage);
			}
		}
	}
	
	private static CommandLine readOptions(String[] args) throws ParseException {
		var options = new Options();
		options.addOption("a", "all", false, "inspect all indexes");
		options.addOption("i", "index", true, "only inspect selected index");
		options.addOption("e", "entry", true, "only inspect selected entry");
		options.addOption("c", "continue", false, "continue previously stored run");
		
		if (args.length == 0) {
			System.out.print("Option(s): ");
			String input = Misc.readLine();
			args = input.split(" ");
		}
		
		try {
			return new DefaultParser().parse(options, args);
		} catch (ParseException e) {
			new HelpFormatter().printHelp(FetchEpwnBiograms.class.getName(), options);
			throw e;
		}
	}
	
	private static void throttle(int duration) {
		var elapsed = System.currentTimeMillis() - lastThrottled;
		
		if (elapsed < duration) {
			try {
				Thread.sleep(duration - elapsed);
			} catch (InterruptedException e) {}
		}
		
		lastThrottled = System.currentTimeMillis();
	}
	
	private static Document tryParseRequest(String url) throws IOException {
		var retries = 0;
		var delay = THROTTLE_SHORT_MS;
		
		do {
			retries++;
			
			try {
				throttle(delay);
				System.out.println(String.format("%s (%d)", url, retries));
				return Jsoup.parse(new URL(url), TIMEOUT_MS);
			} catch (MalformedURLException e) {
				throw new IOException(e);
			} catch (IOException e) {
				delay = THROTTLE_LONG_MS;
				continue;
			}
		} while (retries < MAX_RETRIES);
		
		throw new IOException("max retries exceeded");
	}
	
	private static int getIndexSize(char index) {
		var url = String.format(INDEX_URL_FORMAT, index, 1);
		
		try {
			var doc = tryParseRequest(url);
			var paginator = doc.getElementsByClass("pagination").get(0);
			
			return paginator.select("a").stream()
				.map(Element::ownText)
				.filter(StringUtils::isNumeric)
				.map(Integer::parseInt)
				.sorted(Comparator.reverseOrder())
				.findFirst()
				.get();
		} catch (IndexOutOfBoundsException e) {
			return 1; // no paginator = only one page in index
		} catch (NumberFormatException | NoSuchElementException | IOException e) {
			throw new FailedRunException(e.toString(), url, index, 1, -1L);
		}
	}
	
	private static List<Long> getIndexEntriesById(char index, int page) {
		var url = String.format(INDEX_URL_FORMAT, index, page);
		
		try {
			var doc = tryParseRequest(url);
			
			return doc.select(".alfa a[href]").stream()
				.map(a -> a.attr("href"))
				.map(href -> PATT_ENTRY_URL.matcher(href))
				.filter(Matcher::find)
				// assume biogram titles start with a capital letter
				.filter(m -> StringUtils.capitalize(m.group(1)).equals(m.group(1)))
				.map(m -> m.group(2))
				.map(Long::parseLong)
				.toList();
		} catch (NumberFormatException | IOException e) {
			throw new FailedRunException(e.toString(), url, index, page, -1L);
		}
	}
	
	private static void parseIndex(char index, Set<EpwnBiogram> storage, StoredRun offset) {
		var indexSize = getIndexSize(index);
		System.out.printf("Index %c: %d available pages%n", index, indexSize);
		
		for (var page = offset != null ? offset.page() : 1; page <= indexSize; page++) {
			var ids = getIndexEntriesById(index, page);
			System.out.printf("Page %d: %d targetted entries%n", page, ids.size());
			
			if (ids.isEmpty()) {
				continue; // can this ever happen?
			}
			
			if (offset != null && offset.id() != -1L && ids.contains(offset.id())) {
				ids = ids.subList(ids.indexOf(offset.id()), ids.size());
			}
			
			for (var id : ids) {
				var url = String.format(ENTRY_URL_FORMAT, id);
				
				try {
					var biogram = parseEntry(id);
					
					if (biogram != null) {
						storage.add(biogram);
					}
				} catch (IOException e) {
					throw new FailedRunException(e.toString(), url, index, page, id);
				}
			}
		}
	}
	
	private static EpwnBiogram parseEntry(long id) throws IOException {
		var url = String.format(ENTRY_URL_FORMAT, id);
		var doc = tryParseRequest(url);
		var article = doc.getElementsByTag("article").first();
		
		if (article == null || article.text().contains("→")) {
			return null;
		}
		
		var title = article.getElementsByClass("tytul").first();
		var ur_zm = article.getElementsByClass("ur-zm").first();
		
		if (title == null || ur_zm == null) {
			return null;
		}
		
		var m = PATT_ENTRY_LEAD.matcher(ur_zm.ownText().strip());
		
		if (!m.find()) {
			return null;
		}
		
		var entry = Stream.concat(article.getElementsByClass("imie").stream(), Stream.of(title))
			.map(Element::ownText)
			.map(s -> s.strip().replaceFirst(",$", "").stripTrailing())
			.collect(Collectors.joining(" "));
		
		var birthDate = Optional.ofNullable(m.group("birthDate")).map(FetchEpwnBiograms::parseDate).orElse(null);
		
		var birthPlace = Optional.ofNullable(m.group("birthPlace"))
			.map(s -> s.replaceFirst(" [\\p{Ll}\\(].+", "").strip())
			.filter(s -> StringUtils.capitalize(s).equals(s))
			.orElse(null);
		
		var deathDate = Optional.ofNullable(m.group("deathDate")).map(FetchEpwnBiograms::parseDate).orElse(null);
		
		var deathPlace = Optional.ofNullable(m.group("deathPlace"))
			.map(s -> s.replaceFirst(" [\\p{Ll}\\(].+", "").strip()) // lower-case words and parens
			.map(s -> birthPlace != null && s.equals("tamże") ? birthPlace : s)
			.filter(s -> StringUtils.capitalize(s).equals(s))
			.orElse(null);
		
		var definition = Optional.ofNullable(article.getElementsByClass("def").first())
			.map(Element::text)
			.map(s -> s.replaceFirst("[,;]$", ""))
			.orElse(null);
		
		if (birthDate != null && deathDate != null && !birthDate.query(Year::from).isBefore(deathDate.query(Year::from))) {
			if (deathDate.get(ChronoField.ERA) == IsoEra.BCE.getValue()) {
				birthDate = parseDate(m.group("birthDate") + " p.n.e.");
			} else {
				birthDate = null; // invalid
			}
		}
		
		return new EpwnBiogram(id, entry, birthPlace, deathPlace, birthDate, deathDate, definition);
	}
	
	private static TemporalAccessor parseDate(String date) {
		try {
			return DATE_FORMATTER.parseBest(date, LocalDate::from, YearMonth::from, Year::from);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	private static Set<EpwnBiogram> retrieveEntries() {
		try (var stream = new BufferedInputStream(Files.newInputStream(STORAGE))) {
			var storage = (Set<EpwnBiogram>)xstream.fromXML(stream);
			System.out.println("Storage size: " + storage.size());
			return storage;
		} catch (IOException | XStreamException e) {
			System.out.println(e);
			return new HashSet<>(100000);
		}
	}
	
	private static void storeEntries(Set<EpwnBiogram> storage) throws IOException {
		System.out.println("New storage size: " + storage.size());
		
		try (var stream = new BufferedOutputStream(Files.newOutputStream(STORAGE))) {
			xstream.toXML(storage, stream);
		} catch (XStreamException e) {
			System.out.println(e);
			
			try (var writer = new PrintWriter(Files.newBufferedWriter(STORAGE_FALLBACK))) {
				for (var entry : storage) {
					writer.println(entry);
				}
			}
		}
	}
	
	private static StoredRun retrieveRun() throws IOException {
		try {
			var text = Files.readString(LAST_RUN);
			var run = StoredRun.fromMap(new JSONObject(text).toMap());
			System.out.println("Last run: " + run);
			return run;
		} catch (IOException | JSONException e) {
			System.out.println(e);
			return null;
		}
	}
	
	private static void storeRun(StoredRun run) throws IOException {
		System.out.println("Storing run: " + run);
		
		try {
			Files.writeString(LAST_RUN, new JSONObject(run.toMap()).toString());
		} catch (JSONException e) {
			System.out.println(e);
			Files.writeString(LAST_RUN, run.toString());
		}
	}
	
	private static class FailedRunException extends RuntimeException {
		private static final long serialVersionUID = 1426736382416757854L;
		private final StoredRun failedRun;
		
		FailedRunException(String message, String url, char index, int page, long id) {
			super(message + ": " + url);
			failedRun = new StoredRun(index, page, id);
		}
		
		StoredRun getFailedRun() {
			return failedRun;
		}
	}
	
	private record StoredRun(char index, int page, long id) {
		Map<String, Object> toMap() {
			return Map.of("index", index, "page", page, "id", id);
		}
		
		static StoredRun fromMap(Map<String, Object> map) {
			var id = map.get("id"); // either Long or Integer underlying type 
			return new StoredRun(((String)map.get("index")).charAt(0), (int)map.get("page"), id instanceof Long ? (long)id : (int)id);
		}
	}
}

@XStreamAlias("biogram")
class EpwnBiogram {
	final long id;
	final String entry;
	final String placeOfBirth;
	final String placeOfDeath;
	final TemporalAccessor birthDate;
	final TemporalAccessor deathDate;
	final String definition;
	
	@SuppressWarnings("unused")
	private EpwnBiogram() {
		// this exists only for the sake of xstream not complaining about a missing no-args ctor on deserialization
		id = 0L;
		entry = placeOfBirth = placeOfDeath = definition = null;
		birthDate = deathDate = null;
	}
	
	EpwnBiogram(long id, String entry, String placeOfBirth, String placeOfDeath, TemporalAccessor birthDate, TemporalAccessor deathDate, String definition) {
		this.id = id;
		this.entry = entry;
		this.placeOfBirth = placeOfBirth;
		this.placeOfDeath = placeOfDeath;
		this.birthDate = birthDate;
		this.deathDate = deathDate;
		this.definition = definition;
	}
	
	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		} else if (obj instanceof EpwnBiogram e) {
			return id == e.id;
		} else {
			return false;
		}
	}
	
	@Override
	public String toString() {
		return String.format(
			"EpwnBiogram[id=%d, entry=%s, placeOfBirth=%s, placeOfDeath=%s, birthDate=%s, deathDate=%s]",
			id, entry, placeOfBirth, placeOfDeath, birthDate, deathDate
		);
	}
}
