package com.github.wikibot.parsing.eswikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;

import com.github.wikibot.parsing.Utils;

public final class Catgram {
	private Data firstMember, secondMember, thirdMember;
	private Conjunction conj;
	
	private Catgram(Data firstMember, Data secondMember, Data thirdMember, Conjunction conj) {
		this.firstMember = firstMember;
		this.secondMember = secondMember;
		this.thirdMember = thirdMember;
		this.conj = conj;
	}
	
	public static Catgram make(Data firstMember) {
		try {
			return new Catgram(
				assertMembers(firstMember, true),
				null, null, null
			);
		} catch (NullPointerException | IllegalArgumentException e) {
			return null;
		}
	}
	
	public static Catgram make(Data firstMember, Data secondMember) {
		try {
			return new Catgram(
				assertMembers(firstMember, true),
				assertMembers(secondMember, false),
				null, null
			);
		} catch (NullPointerException | IllegalArgumentException e) {
			return make(firstMember);
		}
	}
	
	public static Catgram make(Data firstMember, Data secondMember, Data thirdMember, Conjunction conj) {
		try {
			return new Catgram(
				assertMembers(firstMember, true),
				assertMembers(secondMember, false),
				assertMembers(thirdMember, false),
				Objects.requireNonNull(conj)
			);
		} catch (NullPointerException | IllegalArgumentException e) {
			return make(firstMember, secondMember);
		}
	}
	
	public static Catgram make(String firstMember) {
		return make(Data.queryData(firstMember));
	}
	
	public static Catgram make(String firstMember, String secondMember) {
		return make(Data.queryData(firstMember), Data.queryData(secondMember));
	}
	
	public static Catgram make(String firstMember, String secondMember, String thirdMember, String conj) {
		Conjunction conjItem = Stream.of(Conjunction.values())
			.filter(value -> getConjunctionString(thirdMember, value).equals(conj))
			.findFirst()
			.orElse(null);
		
		return make(
			Data.queryData(firstMember),
			Data.queryData(secondMember),
			Data.queryData(thirdMember),
			conjItem
		);
	}
	
	private static Data assertMembers(Data member, boolean enforceNonQualifier) {
		switch (Objects.requireNonNull(member).getType()) {
			case MASCULINE_NOUN:
			case FEMININE_NOUN:
			case COMPOUND:
				break;
			case QUALIFIER:
			case INVARIANT_QUALIFIER:
				if (enforceNonQualifier) {
					throw new IllegalArgumentException();
				}
				break;
			default:
				throw new IllegalArgumentException();
		}
		
		return member;
	}
	
	public enum Data {
		ABBREVIATION,
		ACRONYM,
		ADJECTIVE,
		NUMERAL_ADJECTIVE,
		POSSESSIVE_ADJECTIVE,
		ADVERB,
		AFFIX,
		AMBIGUOUS,
		ANIMATE,
		ARTICLE,
		AUXILIARY,
		CARDINAL,
		COLLECTIVE,
		COMPARATIVE,
		COMMON,
		CONJUNCTION,
		COPULATIVE,
		OF_ABLATIVE,
		OF_ACCUSATIVE,
		OF_ACCUSATIVE_OR_ABLATIVE,
		OF_AFFIRMATION,
		OF_QUANTITY,
		OF_DATIVE,
		OF_DOUBT,
		OF_GENITIVE,
		OF_INDICATIVE,
		OF_PLACE,
		OF_MOOD,
		OF_NEGATION,
		OF_SEQUENCE,
		OF_TIME,
		DEFECTIVE,
		DEMONSTRATIVE,
		DETERMINATE,
		DUAL,
		DIGRAPH,
		EXCLAMATIVE,
		FEMININE,
		VERB_FORM,
		IMPERFECTIVE,
		IMPERSONAL,
		INANIMATE,
		INDECLINABLE,
		INDEFINITE,
		INDETERMINATE,
		INFIX,
		INITIALISM,
		INTERJECTION,
		INTERROGATIVE,
		INTRANSITIVE,
		LETTER,
		PHRASE,
		MASCULINE,
		MODAL,
		NEUTER,
		NUMERAL,
		ORDINAL,
		PARTICLE,
		PERSONAL,
		PLURAL,
		POSSESSIVE,
		POSTPOSITION,
		PREFIX,
		PREPOSITION,
		PRONOUN,
		POSSESSIVE_PRONOUN,
		PROPER,
		PROVERB,
		RELATIVE,
		SINGULAR,
		SUFFIX,
		FLEXIVE_SUFFIX,
		NOUN,
		PROPER_NOUN,
		TRANSITIVE,
		VERB;
		
		public enum Type {
			MASCULINE_NOUN,
			FEMININE_NOUN,
			QUALIFIER, // TODO: default gender: masculine?
			INVARIANT_QUALIFIER,
			COMPOUND // TODO: extract gender from the first element?
		}

		private static class Properties {
			Type type;
			String singular;
			String plural;
			String mascSingularAdj;
			String femSingularAdj;
			String mascPluralAdj;
			String femPluralAdj;
			Data[] compoundTerms;
			String[] redirects;
		}
		
		private Properties properties;
		
		static {
			Map<String, Properties> map = loadFile("/eswikt-catgram-data.txt");
			
			for (Data data : Data.values()) {
				Properties properties = map.get(data.toString());
				
				if (properties != null) {
					data.properties = properties;
				}
			}
			
			validateData();
		}
		
		private static Map<String, Properties> loadFile(String filename) {
			String text = Utils.loadResource(filename, Catgram.Data.class);
			String[] items = text.trim().split("\\n{2,}");
			Map<String, Properties> map = new HashMap<String, Properties>(Data.values().length, 1);
			
			for (String item : items) {
				String[] lines = item.split("\n");
				String name = lines[0].trim();
				String[] params = Arrays.copyOfRange(lines, 1, lines.length);
				map.put(name, buildPropertiesObject(params));
			}
			
			return map;
		}
		
		private static Properties buildPropertiesObject(String[] params) {
			Properties p = new Properties();
			
			Map<String, String> m = Stream.of(params)
				.map(param -> param.split("="))
				.filter(tokens -> tokens.length == 2)
				.collect(Collectors.toMap(
					tokens -> tokens[0].trim(),
					tokens -> tokens[1].trim()
				));
			
			String type = m.getOrDefault("type", null);
			
			p.type = Stream.of(Type.values())
				.filter(t -> t.toString().equals(type))
				.findAny()
				.orElse(null);
			
			p.mascSingularAdj = m.getOrDefault("m sg", null);
			
			if (Type.INVARIANT_QUALIFIER.equals(p.type)) {
				p.femSingularAdj = p.mascSingularAdj;
				p.mascPluralAdj = p.mascSingularAdj;
				p.femPluralAdj = p.mascSingularAdj;
			} else {
				p.femSingularAdj = m.getOrDefault("f sg", null);
				p.mascPluralAdj = m.getOrDefault("m pl", null);
				p.femPluralAdj = m.getOrDefault("f pl", null);
			}
			
			p.singular = m.getOrDefault("sing", p.mascSingularAdj);
			p.plural = m.getOrDefault("plur", p.mascPluralAdj);
			
			if (m.containsKey("cterms")) {
				p.compoundTerms = Stream.of(m.get("cterms").split(",\\s*"))
					.map(token -> Stream.of(Data.values())
						.filter(data -> data.name().equals(token))
						.findAny()
						.orElse(null)
					)
					.toArray(Data[]::new); 
			}
			
			if (m.containsKey("redirects")) {
				p.redirects = m.get("redirects").split(",\\s*");
			}
			
			return p;
		}
		
		private static void validateData() {
			for (Data data : Data.values()) {
				Properties properties = data.properties;
				
				if (properties == null) {
					throw new Error("Properties object is null: " + data.toString());
				}
				
				if (properties.type == null) {
					throw new Error("Type property is null: " + data.toString());
				}
				
				switch (properties.type) {
					case MASCULINE_NOUN:
					case FEMININE_NOUN:
					case QUALIFIER:
					case INVARIANT_QUALIFIER:
					case COMPOUND:
						break;	
					default:
						throw new Error("Unrecognized Type property (" + properties.type + "): " + data.toString());
				}
				
				if (properties.type == Type.COMPOUND) {
					if (properties.compoundTerms == null) {
						throw new Error("compoundTerms property is null: " + data.toString());
					}
					
					if (properties.compoundTerms.length < 2) {
						throw new Error("compoundTerms property array length is < 2: " + data.toString());
					}
					
					continue;
				}
				
				if (properties.singular == null || properties.singular.isEmpty()) {
					throw new Error("singular property is null or empty: " + data.toString());
				}
				
				if (properties.plural == null || properties.plural.isEmpty()) {
					throw new Error("plural property is null or empty: " + data.toString());
				}
				
				if (properties.mascSingularAdj == null || properties.mascSingularAdj.isEmpty()) {
					throw new Error("mascSingularAdj property is null or empty: " + data.toString());
				}
				
				if (properties.femSingularAdj == null || properties.femSingularAdj.isEmpty()) {
					throw new Error("femSingularAdj property is null or empty: " + data.toString());
				}
				
				if (properties.mascPluralAdj == null || properties.mascPluralAdj.isEmpty()) {
					throw new Error("mascPluralAdj property is null or empty: " + data.toString());
				}
				
				if (properties.femPluralAdj == null || properties.femPluralAdj.isEmpty()) {
					throw new Error("femPluralAdj property is null or empty: " + data.toString());
				}
				
				if (properties.redirects != null && properties.redirects.length == 0) {
					throw new Error("redirects property array length is 0: " + data.toString());
				}
			}
		}
		
		private Data() {
			properties = new Properties();
		}
		
		public Type getType() {
			return properties.type;
		}
		
		public String getSingular() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getSingular);
			} else {
				return properties.singular;
			}
		}
		
		public String getPlural() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getPlural);
			} else {
				return properties.plural;
			}
		}
		
		public String getMasculineSingularAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getMasculineSingularAdjective);
			} else {
				return properties.mascSingularAdj;
			}
		}
		
		public String getFeminineSingularAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getFeminineSingularAdjective);
			} else {
				return properties.femSingularAdj;
			}
		}
		
		public String getMasculinePluralAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getMasculinePluralAdjective);
			} else {
				return properties.mascPluralAdj;
			}
		}
		
		public String getFemininePluralAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getFemininePluralAdjective);
			} else {
				return properties.femPluralAdj;
			}
		}
		
		private Data[] getCompoundTerms() {
			return properties.compoundTerms;
		}
		
		private String[] getRedirects() {
			return properties.redirects;
		}
		
		private String retrieveCompoundData(Function<Data, String> mapper) {
			return Stream.of(properties.compoundTerms)
				.map(mapper)
				.collect(Collectors.joining(" "));
		}
		
		public Catgram make() {
			return Catgram.make(this);
		}
		
		public static Data queryData(String singular) {
			return Stream.of(Data.values())
				.filter(data ->
					data.getSingular().equals(singular) ||
					Optional.ofNullable(data.getRedirects())
						.filter(arr -> ArrayUtils.contains(arr, singular))
						.isPresent()
				)
				.findAny()
				.orElse(null);
		}
	}
	
	public enum Conjunction {
		AND,
		OR
	}
	
	public String getSingular() {
		if (secondMember == null) {
			return firstMember.getSingular();
		} else {
			Function<Data, String> func = getQualifierFunc(firstMember, false);
			return firstMember.getSingular() + " " + buildQualifierString(func);
		}
	}
	
	public String getPlural() {
		if (secondMember == null) {
			return firstMember.getPlural();
		} else {
			Function<Data, String> func = getQualifierFunc(firstMember, true);
			return firstMember.getPlural() + " " + buildQualifierString(func);
		}
	}
	
	private static Function<Data, String> getQualifierFunc(Data member, boolean isPlural) {
		boolean isMasculine;
		
		if (member.getType() != Data.Type.COMPOUND) {
			isMasculine = member.getType() == Data.Type.MASCULINE_NOUN;
		} else {
			isMasculine = member.getCompoundTerms()[0].getType() == Data.Type.MASCULINE_NOUN;
		}
		
		if (isMasculine && !isPlural) {
			return Data::getMasculineSingularAdjective;
		} else if (!isMasculine && !isPlural) {
			return Data::getFeminineSingularAdjective;
		} else if (isMasculine && isPlural) {
			return Data::getMasculinePluralAdjective;
		} else /*if (!isMasculine && isPlural)*/ {
			return Data::getFemininePluralAdjective;
		}
	}
	
	private String buildQualifierString(Function<Data, String> func) {
		StringBuilder sb = new StringBuilder(func.apply(secondMember));
		
		if (thirdMember != null) {
			String third = func.apply(thirdMember);
			String conjunction = getConjunctionString(third, conj);
			sb.append(" ").append(conjunction).append(" ").append(third);
		}
		
		return sb.toString();
	}
	
	private static String getConjunctionString(String member, Conjunction conj) {
		return switch (conj) {
			// http://lema.rae.es/dpd/srv/search?id=9n8R9ghyFD6fcqFIBx
			case AND -> member.matches("^h?[ií](?![aeiouáéíóú]).+") ? "e" : "y";
			// http://lema.rae.es/dpd/srv/search?id=7wb3ECfmhD6reWjGRa
			case OR -> member.matches("^h?[oó].+") ? "u" : "o";
			default -> throw new UnsupportedOperationException(); // unreachable
		};
	}
	
	public Data getFirstMember() {
		return firstMember;
	}
	
	public Data getSecondMember() {
		return secondMember;
	}
	
	public Data getThirdMember() {
		return thirdMember;
	}
	
	public Conjunction getConjunction() {
		return conj;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(firstMember);
		
		if (secondMember != null) {
			sb.append(" ").append(secondMember);
			
			if (thirdMember != null) {
				sb.append(" ").append(conj).append(" ").append(thirdMember);
			}
		}
		
		return sb.toString();
	}
	
	static void printDataFile(String[] args) throws IOException {
		StringBuilder sb = new StringBuilder(10000);
		
		for (Data data : Data.values()) {
			sb.append(data.toString()).append("\n");
			sb.append("type = ").append(data.getType().toString()).append("\n");
			
			if (!data.getType().toString().equals("COMPOUND")) {
				if (!data.getType().toString().contains("QUALIFIER")) {
					sb.append("sing = ").append(data.getSingular()).append("\n");
					sb.append("plur = ").append(data.getPlural()).append("\n");
				}
				
				sb.append("m sg = ").append(data.getMasculineSingularAdjective()).append("\n");
				
				if (!data.getType().toString().equals("INVARIANT_QUALIFIER")) {
					sb.append("f sg = ").append(data.getFeminineSingularAdjective()).append("\n");
					sb.append("m pl = ").append(data.getMasculinePluralAdjective()).append("\n");
					sb.append("f pl = ").append(data.getFemininePluralAdjective()).append("\n");
				}
			} else {
				Data[] ct = data.getCompoundTerms();
				String temp = Stream.of(ct).map(Data::toString).collect(Collectors.joining(", "));
				sb.append("cterms = ").append(temp).append("\n");
			}
			
			if (data.getRedirects() != null) {
				String[] redirs = data.getRedirects();
				String temp = String.join(", ", redirs);
				sb.append("redirects = ").append(temp).append("\n");
			}
			
			sb.append("\n");
		}
		
		Files.write(Paths.get("./data/eswikt-catgram.txt"), List.of(sb.toString()));
	}
	
	public static void main(String[] args) throws IOException {
		StringBuilder sb = new StringBuilder(10000);
		
		for (Data data : Data.values()) {
			sb.append(data.toString()).append(", ").append(data.getType().toString()).append("\n");
			sb.append(data.getSingular()).append("\n");
			sb.append(data.getPlural()).append("\n");
			sb.append(data.getMasculineSingularAdjective()).append("\n");
			sb.append(data.getFeminineSingularAdjective()).append("\n");
			sb.append(data.getMasculinePluralAdjective()).append("\n");
			sb.append(data.getFemininePluralAdjective()).append("\n");
			
			if (data.getRedirects() != null) {
				sb.append(Arrays.asList(data.getRedirects()));
				sb.append("\n");
			}
			
			sb.append("\n");
		}
		
		Files.write(Paths.get("./data/eswikt-catgram-output.txt"), List.of(sb.toString()));
	}
}
