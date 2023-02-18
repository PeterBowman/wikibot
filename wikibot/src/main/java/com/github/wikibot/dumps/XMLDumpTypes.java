package com.github.wikibot.dumps;

import java.util.Optional;

public enum XMLDumpTypes {
    STUBS_META_HISTORY (
        "xmlstubsdump",
        "${database}-${date}-stub-meta-history\\d+\\.xml(\\.gz)?"
    ),
    STUBS_META_CURRENT (
        "xmlstubsdump",
        "${database}-${date}-stub-meta-current\\d+\\.xml(\\.gz)?"
    ),
    STUBS_ARTICLES (
        "xmlstubsdump",
        "${database}-${date}-stub-articles\\d+\\.xml(\\.gz)?"
    ),
    STUBS_META_HISTORY_RECOMBINE (
        "xmlstubsdumprecombine",
        "${database}-${date}-stub-meta-history\\.xml(\\.gz)?"
    ),
    STUBS_META_CURRENT_RECOMBINE (
        "xmlstubsdumprecombine",
        "${database}-${date}-stub-meta-current\\.xml(\\.gz)?"
    ),
    STUBS_ARTICLES_RECOMBINE (
        "xmlstubsdumprecombine",
        "${database}-${date}-stub-articles\\.xml(\\.gz)?"
    ),
    PAGES_META_HISTORY (
        "metahistorybz2dump",
        "${database}-${date}-pages-meta-history(\\d+\\.xml-p\\d+p\\d+|\\.xml)(\\.bz2)?" // no recombine pair
    ),
    PAGES_META_CURRENT (
        "metacurrentdump",
        "${database}-${date}-pages-meta-current\\d+\\.xml-p\\d+p\\d+(\\.bz2)?"
    ),
    PAGES_META_CURRENT_RECOMBINE (
        "metacurrentdumprecombine",
        "${database}-${date}-pages-meta-current\\.xml(\\.bz2)?"
    ),
    PAGES_ARTICLES (
        "articlesdump",
        "${database}-${date}-pages-articles\\d+\\.xml-p\\d+p\\d+(\\.bz2)?"
    ),
    PAGES_ARTICLES_RECOMBINE (
        "articlesdumprecombine",
        "${database}-${date}-pages-articles\\.xml(\\.bz2)?"
    ),
    PAGES_ARTICLES_MULTISTREAM (
        "articlesmultistreamdump",
        "${database}-${date}-pages-articles-multistream(\\d+\\.xml|-index\\d+\\.txt)-p\\d+p\\d+\\.bz2" // extension is mandatory
    ),
    PAGES_ARTICLES_MULTISTREAM_RECOMBINE (
        "articlesmultistreamdumprecombine",
        "${database}-${date}-pages-articles-multistream(\\.xml|-index\\.txt)\\.bz2" // extension is mandatory
    ),
    ABSTRACTS (
        "abstractsdump",
        "${database}-${date}-abstract\\d+\\.xml(\\.gz)?"
    ),
    ABSTRACTS_RECOMBINE (
        "abstractsdumprecombine",
        "${database}-${date}-abstract\\.xml(\\.gz)?"
    ),
    PAGES_META_HISTORY_INCR (
        "${database}-${date}-pages-meta-hist-incr\\.xml(\\.bz2)?"
    ),
    STUBS_META_HISTORY_INCR (
        "${database}-${date}-stubs-meta-hist-incr\\.xml(\\.gz)?"
    );

    private final String configKey;
    private final String namingSchemeRegex;

    // these can't be final
    private XMLDumpTypes fallback;
    private boolean isIncremental;
    private boolean isMultistream;

    static {
        STUBS_META_HISTORY.fallback = STUBS_META_HISTORY_RECOMBINE;
        STUBS_META_CURRENT.fallback = STUBS_META_CURRENT_RECOMBINE;
        STUBS_ARTICLES.fallback = STUBS_ARTICLES_RECOMBINE;
        PAGES_META_CURRENT.fallback = PAGES_META_CURRENT_RECOMBINE;
        PAGES_ARTICLES.fallback = PAGES_ARTICLES_RECOMBINE;
        PAGES_ARTICLES_MULTISTREAM.fallback = PAGES_ARTICLES_MULTISTREAM_RECOMBINE;
        ABSTRACTS.fallback = ABSTRACTS_RECOMBINE;

        STUBS_META_HISTORY_RECOMBINE.fallback = STUBS_META_HISTORY;
        STUBS_META_CURRENT_RECOMBINE.fallback = STUBS_META_CURRENT;
        STUBS_ARTICLES_RECOMBINE.fallback = STUBS_ARTICLES;
        PAGES_META_CURRENT_RECOMBINE.fallback = PAGES_META_CURRENT;
        PAGES_ARTICLES_RECOMBINE.fallback = PAGES_ARTICLES;
        PAGES_ARTICLES_MULTISTREAM_RECOMBINE.fallback = PAGES_ARTICLES_MULTISTREAM;
        ABSTRACTS_RECOMBINE.fallback = ABSTRACTS;

        PAGES_ARTICLES_MULTISTREAM.isMultistream = true;
        PAGES_ARTICLES_MULTISTREAM_RECOMBINE.isMultistream = true;

        PAGES_META_HISTORY_INCR.isIncremental = true;
        STUBS_META_HISTORY_INCR.isIncremental = true;
    }

    private XMLDumpTypes(String namingSchemeRegex) {
        this(null, namingSchemeRegex);
    }

    private XMLDumpTypes(String configKey, String namingSchemeRegex) {
        this.configKey = configKey;
        this.namingSchemeRegex = namingSchemeRegex;
    }

    public Optional<String> optConfigKey() {
        return Optional.ofNullable(configKey);
    }

    public String getNamingSchemeRegex() {
        return namingSchemeRegex;
    }

    public boolean isIncremental() {
        return isIncremental;
    }

    public boolean isMultistream() {
        return isMultistream;
    }

    public Optional<XMLDumpTypes> optFallback() {
        return Optional.ofNullable(fallback);
    }
}
