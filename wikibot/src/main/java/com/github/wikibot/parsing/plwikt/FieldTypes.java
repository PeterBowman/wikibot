package com.github.wikibot.parsing.plwikt;

public enum FieldTypes {
    ALTERNATIVE_SCRIPTS ("ortografie"),
    NOTATION ("zapis"),
    HIEROGLYPHIC_WRITING ("zapis hieroglificzny"),
    TRANSLITERATION ("transliteracja"),
    TRANSCRIPTION ("transkrypcja"),
    READINGS ("czytania"),
    MORPHOLOGY ("morfologia"),
    PRONUNCIATION ("wymowa"),
    RADICAL_STROKE_KEY ("klucz"),
    TOTAL_STROKES ("kreski"),
    VARIANTS ("warianty"),
    STROKE_ORDER ("kolejność"),
    DEFINITIONS ("znaczenia"),
    DETERMINATIVES ("determinatywy"),
    INFLECTION ("odmiana"),
    EXAMPLES ("przykłady"),
    SYNTAX ("składnia"),
    COLLOCATIONS ("kolokacje"),
    SYNONYMS ("synonimy"),
    ANTONYMS ("antonimy"),
    HYPERNYMS ("hiperonimy"),
    HYPONYMS ("hiponimy"),
    HOLONYMS ("holonimy"),
    MERONYMS ("meronimy"),
    COMPOUNDS ("złożenia"),
    RELATED_TERMS ("pokrewne"),
    DERIVED_TERMS ("pochodne"),
    IDIOMS ("frazeologia"),
    ETYMOLOGY ("etymologia"),
    CODES ("kody"),
    DICTIONARIES ("słowniki"),
    HANJA ("hanja"),
    NOTES ("uwagi"),
    TRANSLATIONS ("tłumaczenia"),
    SOURCES ("źródła");

    private final String localised;

    private FieldTypes(String localised) {
        this.localised = localised;
    }

    public String localised() {
        return localised;
    }
}
