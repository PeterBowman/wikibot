package com.github.wikibot.parsing.eswikt;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.ArrayUtils;
import org.wikiutils.IOUtils;

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
		return new Catgram(
			assertMembers(firstMember, true),
			null, null, null
		);
	}
	
	public static Catgram make(Data firstMember, Data secondMember) {
		return new Catgram(
			assertMembers(firstMember, true),
			assertMembers(secondMember, false),
			null, null
		);
	}
	
	public static Catgram make(Data firstMember, Data secondMember, Data thirdMember, Conjunction conj) {
		return new Catgram(
			assertMembers(firstMember, true),
			assertMembers(secondMember, false),
			assertMembers(thirdMember, false),
			Objects.requireNonNull(conj)
		);
	}
	
	public static Catgram make(String firstMember) {
		return new Catgram(
			assertMembers(Data.queryData(firstMember), true),
			null, null, null
		);
	}
	
	public static Catgram make(String firstMember, String secondMember) {
		return new Catgram(
			assertMembers(Data.queryData(firstMember), true),
			assertMembers(Data.queryData(secondMember), false),
			null, null
		);
	}
	
	public static Catgram make(String firstMember, String secondMember, String thirdMember, String conj) {
		Conjunction conjItem = Stream.of(Conjunction.values())
			.filter(value -> getConjunctionString(thirdMember, value).equals(conj))
			.findFirst()
			.orElse(null);
		
		return new Catgram(
			assertMembers(Data.queryData(firstMember), true),
			assertMembers(Data.queryData(secondMember), false),
			assertMembers(Data.queryData(thirdMember), false),
			Objects.requireNonNull(conjItem)
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
		ACRONYM,
		SINGULAR,
		SUFFIX,
		FLEXIVE_SUFFIX,
		NOUN,
		PROPER_NOUN,
		TRANSITIVE,
		VERB;
		
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
			}
			
			if (properties.type == Type.INVARIANT_QUALIFIER) {
				return getSingular();
			}
			
			if (properties.plural == null) {
				return String.format("%ss", getSingular());
			} else {
				return properties.plural;
			}
		}
		
		public String getMasculineSingularAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getMasculineSingularAdjective);
			}
			
			if (properties.type == Type.INVARIANT_QUALIFIER) {
				return getSingular();
			}
			
			if (properties.mascSingularAdj == null) {
				return getSingular();
			} else {
				return properties.mascSingularAdj;
			}
		}
		
		public String getFeminineSingularAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getFeminineSingularAdjective);
			}
			
			if (properties.type == Type.INVARIANT_QUALIFIER) {
				return getSingular();
			}
			
			if (properties.femSingularAdj == null) {
				return getMasculineSingularAdjective();
			} else {
				return properties.femSingularAdj;
			}
		}
		
		public String getMasculinePluralAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getMasculinePluralAdjective);
			}
			
			if (properties.type == Type.INVARIANT_QUALIFIER) {
				return getSingular();
			}
			
			if (properties.mascPluralAdj == null) {
				if (properties.mascSingularAdj == null) {
					return getPlural();
				} else {
					return String.format("%ss", properties.mascSingularAdj);
				}
			} else {
				return properties.mascPluralAdj;
			}
		}
		
		public String getFemininePluralAdjective() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(Data::getFemininePluralAdjective);
			}
			
			if (properties.type == Type.INVARIANT_QUALIFIER) {
				return getSingular();
			}
			
			if (properties.femPluralAdj == null) {
				if (properties.femSingularAdj == null) {
					return getMasculinePluralAdjective();
				} else {
					return String.format("%ss", properties.femSingularAdj);
				}
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
			return new Catgram(assertMembers(this, true), null, null, null);
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
	
	public enum Type {
		MASCULINE_NOUN,
		FEMININE_NOUN,
		QUALIFIER, // TODO: default gender: masculine?
		INVARIANT_QUALIFIER,
		COMPOUND // TODO: extract gender from the first element?
	}
	
	static {
		Data.ABBREVIATION.properties.type = Type.FEMININE_NOUN;
		Data.ABBREVIATION.properties.singular = "abreviatura";
		Data.ABBREVIATION.properties.mascSingularAdj = "de abreviatura";
		
		Data.ADJECTIVE.properties.type = Type.MASCULINE_NOUN;
		Data.ADJECTIVE.properties.singular = "adjetivo";
		Data.ADJECTIVE.properties.femSingularAdj = "adjetiva";
		Data.ADJECTIVE.properties.redirects = new String[]{"adjetivos"};
		
		Data.NUMERAL_ADJECTIVE.properties.type = Type.COMPOUND;
		Data.NUMERAL_ADJECTIVE.properties.compoundTerms = new Data[]{Data.ADJECTIVE, Data.NUMERAL};
		
		Data.POSSESSIVE_ADJECTIVE.properties.type = Type.COMPOUND;
		Data.POSSESSIVE_ADJECTIVE.properties.compoundTerms = new Data[]{Data.ADJECTIVE, Data.POSSESSIVE};
		
		Data.ADVERB.properties.type = Type.MASCULINE_NOUN;
		Data.ADVERB.properties.singular = "adverbio";
		Data.ADVERB.properties.mascSingularAdj = "adverbial";
		Data.ADVERB.properties.mascPluralAdj = "adverbiales";
		Data.ADVERB.properties.redirects = new String[]{"adverbios"};
		
		Data.AFFIX.properties.type = Type.MASCULINE_NOUN;
		Data.AFFIX.properties.singular = "afijo";
		Data.AFFIX.properties.femSingularAdj = "afija";
		
		Data.AMBIGUOUS.properties.type = Type.QUALIFIER;
		Data.AMBIGUOUS.properties.singular = "ambiguo";
		Data.AMBIGUOUS.properties.femSingularAdj = "ambigua";
		
		Data.ANIMATE.properties.type = Type.QUALIFIER;
		Data.ANIMATE.properties.singular = "animado";
		Data.ANIMATE.properties.femSingularAdj = "animada";
		
		Data.ARTICLE.properties.type = Type.MASCULINE_NOUN;
		Data.ARTICLE.properties.singular = "artículo";
		Data.ARTICLE.properties.mascSingularAdj = "de artículo";
		Data.ARTICLE.properties.redirects = new String[]{"artículos", "articulos", "articulo"};
		
		Data.AUXILIARY.properties.type = Type.QUALIFIER;
		Data.AUXILIARY.properties.singular = "auxiliar";
		Data.AUXILIARY.properties.plural = "auxiliares";
		
		Data.CARDINAL.properties.type = Type.QUALIFIER;
		Data.CARDINAL.properties.singular = "cardinal";
		Data.CARDINAL.properties.plural = "cardinales";
		
		Data.COLLECTIVE.properties.type = Type.QUALIFIER;
		Data.COLLECTIVE.properties.singular = "colectivo";
		Data.COLLECTIVE.properties.femSingularAdj = "colectiva";
		
		Data.COMPARATIVE.properties.type = Type.QUALIFIER;
		Data.COMPARATIVE.properties.singular = "comparativo";
		Data.COMPARATIVE.properties.femSingularAdj = "comparativa";
		
		Data.COMMON.properties.type = Type.QUALIFIER;
		Data.COMMON.properties.singular = "común";
		Data.COMMON.properties.plural = "comunes";
		
		Data.CONJUNCTION.properties.type = Type.FEMININE_NOUN;
		Data.CONJUNCTION.properties.singular = "conjunción";
		Data.CONJUNCTION.properties.plural = "conjunciones";
		Data.CONJUNCTION.properties.mascSingularAdj = "conjuntivo";
		Data.CONJUNCTION.properties.femSingularAdj = "conjuntiva";
		
		Data.COPULATIVE.properties.type = Type.QUALIFIER;
		Data.COPULATIVE.properties.singular = "copulativo";
		Data.COPULATIVE.properties.femSingularAdj = "copulativa";
		
		Data.OF_ABLATIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_ABLATIVE.properties.singular = "de ablativo";
		
		Data.OF_ACCUSATIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_ACCUSATIVE.properties.singular = "de acusativo";
		
		Data.OF_ACCUSATIVE_OR_ABLATIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_ACCUSATIVE_OR_ABLATIVE.properties.singular = "de acusativo";
		
		Data.OF_AFFIRMATION.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_AFFIRMATION.properties.singular = "de afirmación";
		
		Data.OF_QUANTITY.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_QUANTITY.properties.singular = "de cantidad";
		
		Data.OF_DATIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_DATIVE.properties.singular = "de dativo";
		
		Data.OF_DOUBT.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_DOUBT.properties.singular = "de duda";
		
		Data.OF_GENITIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_GENITIVE.properties.singular = "de genitivo";
		
		Data.OF_INDICATIVE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_INDICATIVE.properties.singular = "de indicativo";
		
		Data.OF_PLACE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_PLACE.properties.singular = "de lugar";
		
		Data.OF_MOOD.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_MOOD.properties.singular = "de modo";
		
		Data.OF_NEGATION.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_NEGATION.properties.singular = "de negación";
		
		Data.OF_SEQUENCE.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_SEQUENCE.properties.singular = "de orden";
		
		Data.OF_TIME.properties.type = Type.INVARIANT_QUALIFIER;
		Data.OF_TIME.properties.singular = "de tiempo";
		
		Data.DEMONSTRATIVE.properties.type = Type.QUALIFIER;
		Data.DEMONSTRATIVE.properties.singular = "demostrativo";
		Data.DEMONSTRATIVE.properties.femSingularAdj = "demostrativa";
		
		Data.DETERMINATE.properties.type = Type.QUALIFIER;
		Data.DETERMINATE.properties.singular = "determinado";
		Data.DETERMINATE.properties.femSingularAdj = "determinada";
		
		Data.DUAL.properties.type = Type.QUALIFIER;
		Data.DUAL.properties.singular = "dual";
		Data.DUAL.properties.plural = "duales";
		
		Data.DIGRAPH.properties.type = Type.MASCULINE_NOUN;
		Data.DIGRAPH.properties.singular = "dígrafo";
		Data.DIGRAPH.properties.mascSingularAdj = "de dígrafo";
		
		Data.EXCLAMATIVE.properties.type = Type.QUALIFIER;
		Data.EXCLAMATIVE.properties.singular = "exclamativo";
		Data.EXCLAMATIVE.properties.femSingularAdj = "exclamativa";
		
		Data.FEMININE.properties.type = Type.QUALIFIER;
		Data.FEMININE.properties.singular = "femenino";
		Data.FEMININE.properties.femSingularAdj = "femenina";
		
		Data.VERB_FORM.properties.type = Type.FEMININE_NOUN;
		Data.VERB_FORM.properties.singular = "forma verbal";
		Data.VERB_FORM.properties.plural = "formas verbales";
		Data.VERB_FORM.properties.mascSingularAdj = "de forma verbal";
		Data.VERB_FORM.properties.mascPluralAdj = "de formas verbales";
		
		Data.IMPERFECTIVE.properties.type = Type.QUALIFIER;
		Data.IMPERFECTIVE.properties.singular = "imperfectivo";
		Data.IMPERFECTIVE.properties.femSingularAdj = "imperfectiva";
		
		Data.IMPERSONAL.properties.type = Type.QUALIFIER;
		Data.IMPERSONAL.properties.singular = "impersonal";
		Data.IMPERSONAL.properties.plural = "impersonales";
		
		Data.INANIMATE.properties.type = Type.QUALIFIER;
		Data.INANIMATE.properties.singular = "inanimado";
		Data.INANIMATE.properties.femSingularAdj = "inanimada";
		
		Data.INDECLINABLE.properties.type = Type.QUALIFIER;
		Data.INDECLINABLE.properties.singular = "indeclinable";
		
		Data.INDEFINITE.properties.type = Type.QUALIFIER;
		Data.INDEFINITE.properties.singular = "indefinido";
		Data.INDEFINITE.properties.femSingularAdj = "indefinida";
		
		Data.INDETERMINATE.properties.type = Type.QUALIFIER;
		Data.INDETERMINATE.properties.singular = "indeterminado";
		Data.INDETERMINATE.properties.femSingularAdj = "indeterminada";
		
		Data.INFIX.properties.type = Type.MASCULINE_NOUN;
		Data.INFIX.properties.singular = "infijo";
		Data.INFIX.properties.femSingularAdj = "infija";
		
		Data.INTERJECTION.properties.type = Type.FEMININE_NOUN;
		Data.INTERJECTION.properties.singular = "interjección";
		Data.INTERJECTION.properties.plural = "interjecciones";
		Data.INTERJECTION.properties.mascSingularAdj = "interjectivo";
		Data.INTERJECTION.properties.femSingularAdj = "interjectiva";
		
		Data.INTERROGATIVE.properties.type = Type.QUALIFIER;
		Data.INTERROGATIVE.properties.singular = "interrogativo";
		Data.INTERROGATIVE.properties.femSingularAdj = "interrogativa";
		
		Data.INTRANSITIVE.properties.type = Type.QUALIFIER;
		Data.INTRANSITIVE.properties.singular = "intransitivo";
		Data.INTRANSITIVE.properties.femSingularAdj = "intransitiva";
		
		Data.LETTER.properties.type = Type.FEMININE_NOUN;
		Data.LETTER.properties.singular = "letra";
		Data.LETTER.properties.mascSingularAdj = "de letra";
		
		Data.PHRASE.properties.type = Type.FEMININE_NOUN;
		Data.PHRASE.properties.singular = "locución";
		Data.PHRASE.properties.plural = "locuciones";
		Data.PHRASE.properties.mascSingularAdj = "locutivo";
		Data.PHRASE.properties.femSingularAdj = "locutiva";
		
		Data.MASCULINE.properties.type = Type.QUALIFIER;
		Data.MASCULINE.properties.singular = "masculino";
		Data.MASCULINE.properties.femSingularAdj = "masculina";
		
		Data.MODAL.properties.type = Type.QUALIFIER;
		Data.MODAL.properties.singular = "modal";
		Data.MODAL.properties.plural = "modales";
		
		Data.NEUTER.properties.type = Type.QUALIFIER;
		Data.NEUTER.properties.singular = "neutro";
		Data.NEUTER.properties.femSingularAdj = "neutra";
		
		Data.NUMERAL.properties.type = Type.MASCULINE_NOUN;
		Data.NUMERAL.properties.singular = "numeral";
		Data.NUMERAL.properties.plural = "numerales";
		
		Data.ORDINAL.properties.type = Type.QUALIFIER;
		Data.ORDINAL.properties.singular = "ordinal";
		Data.ORDINAL.properties.plural = "ordinales";
		
		Data.PARTICLE.properties.type = Type.FEMININE_NOUN;
		Data.PARTICLE.properties.singular = "partícula";
		Data.PARTICLE.properties.mascSingularAdj = "de partícula";
		Data.PARTICLE.properties.mascPluralAdj = "de partículas";
		
		Data.PERSONAL.properties.type = Type.QUALIFIER;
		Data.PERSONAL.properties.singular = "personal";
		Data.PERSONAL.properties.plural = "personales";
		
		Data.PLURAL.properties.type = Type.QUALIFIER;
		Data.PLURAL.properties.singular = "plural";
		Data.PLURAL.properties.plural = "plurales";
		
		Data.POSSESSIVE.properties.type = Type.QUALIFIER;
		Data.POSSESSIVE.properties.singular = "posesivo";
		Data.POSSESSIVE.properties.femSingularAdj = "posesiva";
		Data.POSSESSIVE.properties.redirects = new String[]{"posesivos"};
		
		Data.POSTPOSITION.properties.type = Type.FEMININE_NOUN;
		Data.POSTPOSITION.properties.singular = "postposición";
		Data.POSTPOSITION.properties.plural = "postposiciones";
		Data.POSTPOSITION.properties.mascSingularAdj = "postpositivo";
		Data.POSTPOSITION.properties.femSingularAdj = "postpositiva";
		
		Data.PREFIX.properties.type = Type.MASCULINE_NOUN;
		Data.PREFIX.properties.singular = "prefijo";
		Data.PREFIX.properties.femSingularAdj = "prefija";
		
		Data.PREPOSITION.properties.type = Type.FEMININE_NOUN;
		Data.PREPOSITION.properties.singular = "preposición";
		Data.PREPOSITION.properties.plural = "preposiciones";
		Data.PREPOSITION.properties.mascSingularAdj = "prepositivo";
		Data.PREPOSITION.properties.femSingularAdj = "prepositiva";
		Data.PREPOSITION.properties.redirects = new String[]{"preposicion", "preposiciones"};
		
		Data.PRONOUN.properties.type = Type.MASCULINE_NOUN;
		Data.PRONOUN.properties.singular = "pronombre";
		Data.PRONOUN.properties.mascSingularAdj = "pronominal";
		Data.PRONOUN.properties.mascPluralAdj = "pronominales";
		Data.PRONOUN.properties.redirects = new String[]{"pronombres", "pronominal"};
		
		Data.POSSESSIVE_PRONOUN.properties.type = Type.COMPOUND;
		Data.POSSESSIVE_PRONOUN.properties.compoundTerms = new Data[]{Data.PRONOUN, Data.POSSESSIVE};
		
		Data.PROPER.properties.type = Type.QUALIFIER;
		Data.PROPER.properties.singular = "propio";
		Data.PROPER.properties.femSingularAdj = "propia";
		
		Data.PROVERB.properties.type = Type.MASCULINE_NOUN;
		Data.PROVERB.properties.singular = "refrán";
		Data.PROVERB.properties.plural = "refranes";
		Data.PROVERB.properties.mascSingularAdj = "de refrán";
		Data.PROVERB.properties.mascPluralAdj = "de refrán";
		
		Data.RELATIVE.properties.type = Type.QUALIFIER;
		Data.RELATIVE.properties.singular = "relativo";
		Data.RELATIVE.properties.femSingularAdj = "relativa";
		
		Data.ACRONYM.properties.type = Type.FEMININE_NOUN;
		Data.ACRONYM.properties.singular = "sigla";
		Data.ACRONYM.properties.mascSingularAdj = "de sigla";
		Data.ACRONYM.properties.mascPluralAdj = "de siglas";
		
		Data.SINGULAR.properties.type = Type.QUALIFIER;
		Data.SINGULAR.properties.singular = "singular";
		Data.SINGULAR.properties.plural = "singulares";
		
		Data.SUFFIX.properties.type = Type.MASCULINE_NOUN;
		Data.SUFFIX.properties.singular = "sufijo";
		Data.SUFFIX.properties.femSingularAdj = "sufija";
		
		Data.FLEXIVE_SUFFIX.properties.type = Type.MASCULINE_NOUN;
		Data.FLEXIVE_SUFFIX.properties.singular = "sufijo flexivo";
		Data.FLEXIVE_SUFFIX.properties.plural = "sufijos flexivos";
		Data.FLEXIVE_SUFFIX.properties.femSingularAdj = "sufija flexiva";
		Data.FLEXIVE_SUFFIX.properties.femPluralAdj = "sufijas flexivas";
		
		Data.NOUN.properties.type = Type.MASCULINE_NOUN;
		Data.NOUN.properties.singular = "sustantivo";
		Data.NOUN.properties.femSingularAdj = "sustantiva";
		Data.NOUN.properties.redirects = new String[]{"sustantivos"};
		
		Data.PROPER_NOUN.properties.type = Type.MASCULINE_NOUN;
		Data.PROPER_NOUN.properties.singular = "sustantivo propio";
		Data.PROPER_NOUN.properties.plural = "sustantivos propios";
		Data.PROPER_NOUN.properties.femSingularAdj = "sustantiva propia";
		Data.PROPER_NOUN.properties.femPluralAdj = "sustantivas propias";
		
		Data.TRANSITIVE.properties.type = Type.QUALIFIER;
		Data.TRANSITIVE.properties.singular = "transitivo";
		Data.TRANSITIVE.properties.femSingularAdj = "transitiva";
		
		Data.VERB.properties.type = Type.MASCULINE_NOUN;
		Data.VERB.properties.singular = "verbo";
		Data.VERB.properties.mascSingularAdj = "verbal";
		Data.VERB.properties.mascPluralAdj = "verbales";
		Data.VERB.properties.redirects = new String[]{"verbos"};
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
		
		if (member.getType() != Type.COMPOUND) {
			isMasculine = member.getType() == Type.MASCULINE_NOUN;
		} else {
			isMasculine = member.getCompoundTerms()[0].getType() == Type.MASCULINE_NOUN;
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
		switch (conj) {
			case AND:
				// http://lema.rae.es/dpd/srv/search?id=9n8R9ghyFD6fcqFIBx
				return member.matches("^h?[ií](?![aeiouáéíóú]).+") ? "e" : "y";
			case OR:
				// http://lema.rae.es/dpd/srv/search?id=7wb3ECfmhD6reWjGRa
				return member.matches("^h?[oó].+") ? "u" : "o";
			default:
				throw new UnsupportedOperationException();
		}
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
	
	public static void main(String[] args) throws IOException {
		StringBuilder sb = new StringBuilder(10000);
		
		for (Data data : Data.values()) {
			sb.append(data.toString());
			sb.append(", ");
			sb.append(data.getType().toString());
			sb.append("\n");
			sb.append(data.getSingular());
			sb.append("\n");
			sb.append(data.getPlural());
			sb.append("\n");
			sb.append(data.getMasculineSingularAdjective());
			sb.append("\n");
			sb.append(data.getFeminineSingularAdjective());
			sb.append("\n");
			sb.append(data.getMasculinePluralAdjective());
			sb.append("\n");
			sb.append(data.getFemininePluralAdjective());
			sb.append("\n");
			sb.append("\n");
		}
		
		IOUtils.writeToFile(sb.toString(), "./data/eswikt.catgram.txt");
	}
}
