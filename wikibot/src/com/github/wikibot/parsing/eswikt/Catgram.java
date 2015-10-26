package com.github.wikibot.parsing.eswikt;

import java.io.IOException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.wikiutils.IOUtils;

public class Catgram {
	private Catgram() {}
	
	public enum Data {
		ABBREVIATION (null),
		ADJECTIVE (null),
		NUMERAL_ADJECTIVE (null),
		POSSESSIVE_ADJECTIVE (null),
		ADVERB (null),
		AFFIX (null),
		ANIMATE (null),
		ARTICLE (null),
		AUXILIARY (null),
		CARDINAL (null),
		COLLECTIVE (null),
		COMPARATIVE (null),
		COMMON (null),
		CONJUNCTION (null),
		COPULATIVE (null),
		OF_ABLATIVE (null),
		OF_ACCUSATIVE (null),
		OF_ACCUSATIVE_OR_ABLATIVE (null),
		OF_AFFIRMATION (null),
		OF_QUANTITY (null),
		OF_DATIVE (null),
		OF_DOUBT (null),
		OF_GENITIVE (null),
		OF_INDICATIVE (null),
		OF_PLACE (null),
		OF_MOOD (null),
		OF_NEGATION (null),
		OF_SEQUENCE (null),
		OF_TIME (null),
		DEMONSTRATIVE (null),
		DEFINITE_ALT (null),
		DUAL (null),
		DIGRAPH (null),
		EXCLAMATIVE (null),
		FEMININE (null),
		FEMININE_AND_MASCULINE (null),
		VERB_FORM (null),
		IMPERFECTIVE (null),
		IMPERSONAL (null),
		INANIMATE (null),
		INDECLINABLE (null),
		INDEFINITE (null),
		INDEFINITE_ALT (null),
		INFIX (null),
		INTERJECTION (null),
		INTERROGATIVE (null),
		INTRANSITIVE (null),
		LETTER (null),
		PHRASE (null),
		MASCULINE (null),
		MODAL (null),
		NEUTER (null),
		NUMERAL (null),
		ORDINAL (null),
		PARTICLE (null),
		PERSONAL (null),
		PLURAL (null),
		POSSESSIVE (null),
		POSTPOSITION (null),
		PREFIX (null),
		PREPOSITION (null),
		PRONOUN (null),
		POSSESSIVE_PRONOUN (null),
		PROPER (null),
		PROVERB (null),
		RELATIVE (null),
		ACRONYM (null),
		SINGULAR (null),
		SUFFIX (null),
		FLEXIVE_SUFFIX (null),
		NOUN (null),
		PROPER_NOUN (null),
		TRANSITIVE (null),
		VERB (null);
		
		private Properties properties;
		
		private Data(Properties properties) {
			this.properties = new Properties();
		}
		
		public Type getType() {
			return properties.type;
		}
		
		public String getSingular() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(data -> data.getSingular());
			} else {
				return properties.singular;
			}
		}
		
		public String getPlural() {
			if (properties.type == Type.COMPOUND) {
				return retrieveCompoundData(data -> data.getPlural());
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
				return retrieveCompoundData(data -> data.getMasculineSingularAdjective());
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
				return retrieveCompoundData(data -> data.getFeminineSingularAdjective());
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
				return retrieveCompoundData(data -> data.getMasculinePluralAdjective());
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
				return retrieveCompoundData(data -> data.getFemininePluralAdjective());
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
		
		public Data[] getCompoundTerms() {
			return properties.compoundTerms;
		}
		
		private String retrieveCompoundData(Function<Data, String> mapper) {
			return Stream.of(properties.compoundTerms)
				.map(mapper)
				.collect(Collectors.joining(" "));
		}
	}
	
	static {
		Data.ABBREVIATION.properties.type = Type.FEMININE_NOUN;
		Data.ABBREVIATION.properties.singular = "abreviatura";
		Data.ABBREVIATION.properties.mascSingularAdj = "de abreviatura";
		
		Data.ADJECTIVE.properties.type = Type.MASCULINE_NOUN;
		Data.ADJECTIVE.properties.singular = "adjetivo";
		Data.ADJECTIVE.properties.femSingularAdj = "adjetiva";
		
		Data.NUMERAL_ADJECTIVE.properties.type = Type.COMPOUND;
		Data.NUMERAL_ADJECTIVE.properties.compoundTerms = new Data[]{Data.ADJECTIVE, Data.NUMERAL};
		
		Data.POSSESSIVE_ADJECTIVE.properties.type = Type.COMPOUND;
		Data.POSSESSIVE_ADJECTIVE.properties.compoundTerms = new Data[]{Data.ADJECTIVE, Data.POSSESSIVE};
		
		Data.ADVERB.properties.type = Type.MASCULINE_NOUN;
		Data.ADVERB.properties.singular = "adverbio";
		Data.ADVERB.properties.mascSingularAdj = "adverbial";
		Data.ADVERB.properties.mascPluralAdj = "adverbiales";
		
		Data.AFFIX.properties.type = Type.MASCULINE_NOUN;
		Data.AFFIX.properties.singular = "afijo";
		Data.AFFIX.properties.femSingularAdj = "afija";
		
		Data.ANIMATE.properties.type = Type.QUALIFIER;
		Data.ANIMATE.properties.singular = "animado";
		Data.ANIMATE.properties.femSingularAdj = "animada";
		
		Data.ARTICLE.properties.type = Type.MASCULINE_NOUN;
		Data.ARTICLE.properties.singular = "artículo";
		Data.ARTICLE.properties.mascSingularAdj = "de artículo";
		
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
		
		Data.DEFINITE_ALT.properties.type = Type.QUALIFIER;
		Data.DEFINITE_ALT.properties.singular = "determinado";
		Data.DEFINITE_ALT.properties.femSingularAdj = "determinada";
		
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
		
		Data.FEMININE_AND_MASCULINE.properties.type = Type.QUALIFIER;
		Data.FEMININE_AND_MASCULINE.properties.singular = "femenino y masculino";
		Data.FEMININE_AND_MASCULINE.properties.plural = "femeninos y masculinos";
		Data.FEMININE_AND_MASCULINE.properties.mascSingularAdj = "de género común";
		Data.FEMININE_AND_MASCULINE.properties.mascPluralAdj = "de género común";
		
		Data.VERB_FORM.properties.type = Type.FEMININE_NOUN;
		Data.VERB_FORM.properties.singular = "forma verbal";
		Data.VERB_FORM.properties.plural = "formas verbales";
		Data.VERB_FORM.properties.mascSingularAdj = "de forma verbal";
		
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
		
		Data.INDEFINITE_ALT.properties.type = Type.QUALIFIER;
		Data.INDEFINITE_ALT.properties.singular = "indeterminado";
		Data.INDEFINITE_ALT.properties.femSingularAdj = "indeterminada";
		
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
		Data.PARTICLE.properties.mascPluralAdj = "de partícula";
		
		Data.PERSONAL.properties.type = Type.QUALIFIER;
		Data.PERSONAL.properties.singular = "personal";
		Data.PERSONAL.properties.plural = "personales";
		
		Data.PLURAL.properties.type = Type.QUALIFIER;
		Data.PLURAL.properties.singular = "plural";
		Data.PLURAL.properties.plural = "plurales";
		
		Data.POSSESSIVE.properties.type = Type.QUALIFIER;
		Data.POSSESSIVE.properties.singular = "posesivo";
		Data.POSSESSIVE.properties.femSingularAdj = "posesiva";
		
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
		
		Data.PRONOUN.properties.type = Type.MASCULINE_NOUN;
		Data.PRONOUN.properties.singular = "pronombre";
		Data.PRONOUN.properties.mascSingularAdj = "pronominal";
		Data.PRONOUN.properties.mascPluralAdj = "pronominales";
		
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
		Data.ACRONYM.properties.mascPluralAdj = "de sigla";
		
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
	}
	
	public enum Type {
		MASCULINE_NOUN,
		FEMININE_NOUN,
		QUALIFIER,
		INVARIANT_QUALIFIER,
		COMPOUND
	}
	
	public static Data queryData(String singular) {
		return Stream.of(Data.values())
			.filter(data -> data.getSingular().equals(singular))
			.findAny()
			.orElseThrow(UnsupportedOperationException::new);
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
