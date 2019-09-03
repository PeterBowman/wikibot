package com.github.wikibot.utils;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class Inflector extends OnlineDict<Inflector> {
	public static final String MAIN_URL = "http://nlp.actaforte.pl:8080/Nomina/Nazwiska";
	public static final String CASE_SEPARATOR = ", ";
	
	protected boolean exists;
	protected boolean inPESEL;
	protected boolean inSGJP;
	
	private Gender gender;

	public static enum Gender {
		MASCULINE_SINGULAR ("m"),
		FEMININE_SINGULAR ("f"),
		MASCULINE_PLURAL ("p1"),
		FEMININE_PLURAL ("p2");
		
		private final String param;
		
		Gender(String param) {
			this.param = param;
		}
	}

	public Inflector(String entry, Gender gender) {
		super(entry, String.format("%s?typ=%s&nazwisko=", MAIN_URL, gender.param), StandardCharsets.UTF_8.name());
		this.gender = gender;
	}

	@Override
	public Inflector call() throws Exception {
		fetchEntry();
		return this;
	}

	@Override
	protected String escape(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	@Override
	protected String stripContent(String text) {
		return text;
	}
	
	public boolean exists() {
		if (isSerial) {
			return exists;
		} else {
			return !content.contains("Uwaga: Podałeś nazwisko nie spotykane w bazie PESEL, słowniku ani w korpusie tekstów.");
		}
	}
	
	public boolean inPESEL() {
		if (isSerial) {
			return inPESEL;
		} else {
			return exists() && !content.contains("<b>nie występuje</b> w bazie PESEL");
		}
	}
	
	public boolean inSGJP() {
		if (isSerial) {
			return inSGJP;
		} else {
			return exists() && content.contains("<p>Taką odmianę tego nazwiska podaje SGJP.</p>");
		}
	}
	
	public Gender getGender() {
		return gender;
	}
	
	public Cases getInflection() {
		Cases cases = new Cases();
		
		Document doc = Jsoup.parseBodyFragment(content);
		Element table = doc.select("table[width=800] table").first();
		
		if (table == null || table.getElementsContainingOwnText("Biernik (zapraszamy):").isEmpty()) {
			throw new InflectorException("inflection table not found");
		}
		
		if (!table.getElementsByTag("sup").isEmpty()) {
			throw new InflectorException("multiple inflection variants");
		}
		
		Elements rows = table.select("> tbody > tr");
		
		if (gender != Gender.MASCULINE_PLURAL && rows.size() != 7) {
			throw new InflectorException(rows.size() + " cases extracted, 7 expected");
		}
		
		if (gender == Gender.MASCULINE_PLURAL && rows.size() != 8) {
			throw new InflectorException(rows.size() + " cases extracted, 8 expected");
		}
		
		cases.nominative   = extractCase(rows.get(0), "mianownik");
		cases.genitive     = extractCase(rows.get(1), "dopełniacz");
		cases.dative       = extractCase(rows.get(2), "celownik");
		cases.accusative   = extractCase(rows.get(3), "biernik");
		cases.instrumental = extractCase(rows.get(4), "narzędnik");
		cases.locative     = extractCase(rows.get(5), "miejscownik");
		cases.vocative     = extractCase(rows.get(6), "wołacz");
		
		if (gender == Gender.MASCULINE_PLURAL) {
			cases.depreciative = extractCase(rows.get(7), "formy deprecjatywne");
		}
		
		return cases;
	}
	
	private String extractCase(Element el, String expectedCase) {
		if (el.select("> td:first-child:containsOwn(" + expectedCase + ")").isEmpty()) {
			throw new InflectorException("missing table row: " + expectedCase);
		}
		
		return Optional.of(el.select("table:first-of-type div > b"))
			.filter(els -> !els.isEmpty())
			.map(els -> els.stream().map(Element::text).collect(Collectors.joining(CASE_SEPARATOR)))
			.orElseThrow(() -> new InflectorException("missing case column: " + expectedCase));
	}
	
	public static void main(String[] args) throws Exception {
		String surname = "Anioł";
		
		Inflector inflector = new Inflector(surname, Gender.MASCULINE_PLURAL);
		inflector.fetchEntry();
		
		System.out.println(inflector.exists());
		System.out.println(inflector.inPESEL());
		System.out.println(inflector.inSGJP());
		System.out.println(inflector.getInflection());
	}

	public static class Cases implements Serializable {
		private static final long serialVersionUID = 8289930055444460671L;
		
		private String nominative;
		private String genitive;
		private String dative;
		private String accusative;
		private String instrumental;
		private String locative;
		private String vocative;
		private String depreciative;
		
		private Cases() {}

		public String getNominative() {
			return nominative;
		}

		public String getGenitive() {
			return genitive;
		}

		public String getDative() {
			return dative;
		}

		public String getAccusative() {
			return accusative;
		}

		public String getInstrumental() {
			return instrumental;
		}

		public String getLocative() {
			return locative;
		}

		public String getVocative() {
			return vocative;
		}
		
		public String getDepreciative() {
			return depreciative;
		}
		
		public boolean isIndeclinable() {
			return
				nominative.equals(genitive) && nominative.equals(dative) && nominative.equals(accusative) &&
				nominative.equals(instrumental) && nominative.equals(locative) && nominative.equals(vocative);
		}
		
		@Override
		public String toString() {
			return String.format(
				"Mianownik\t%s%nDopełniacz\t%s%nCelownik\t%s%nBiernik\t\t%s%nNarzędnik\t%s%nMiejscownik\t%s%nWołacz\t\t%s%nForma depr.\t%s",
				nominative, genitive, dative, accusative, instrumental, locative, vocative, depreciative
			);
		}
	}
	
	public class InflectorException extends RuntimeException {
		private static final long serialVersionUID = -185232449006700126L;
		
		public InflectorException(String message) {
			super(message);
		}
		
		public String getExtendedMessage() {
			String encoded = URLEncoder.encode(getEntry(), StandardCharsets.UTF_8);
			
			return String.format(
				"%s ([%s?nazwisko=%s&typ=%s %s])",
				getMessage(), MAIN_URL, encoded, getGender().param, getGender()
			);
		}
	}
}
