module org.github.wikibot {
    exports com.github.wikibot.parsing.eswikt;
    exports com.github.wikibot.dumps;
    exports com.github.wikibot.utils;
    exports com.github.wikibot.parsing.plwikt;
    exports com.github.wikibot.parsing;
    exports com.github.wikibot.main;

    requires transitive com.ibm.icu;
    requires transitive commons.cli;
    requires transitive commons.exec;
    requires transitive java.logging;
    requires transitive java.net.http;
    requires transitive java.sql;
    requires transitive java.xml;
    requires transitive org.apache.commons.collections4;
    requires transitive org.apache.commons.compress;
    requires transitive org.apache.commons.io;
    requires transitive org.apache.commons.lang3;
    requires transitive org.apache.commons.text;
    requires transitive org.json;
    requires transitive org.jsoup;
    requires transitive plural4j;
    requires transitive univocity.parsers;
    requires transitive org.wikipedia;
    requires transitive xstream;
    requires transitive org.nibor.autolink;
    requires transitive java.desktop;
    requires transitive rdf4j.http.client;
    requires transitive rdf4j.model;
    requires transitive rdf4j.model.api;
    requires transitive rdf4j.query;
    requires transitive rdf4j.repository.api;
    requires transitive rdf4j.repository.sparql;
    requires transitive rdf4j.common.exception;
    requires transitive rdf4j.common.iterator;
    requires transitive java.xml.bind;

    // https://stackoverflow.com/a/41265267
    opens com.github.wikibot.main;
    opens com.github.wikibot.scripts.wd;
    opens com.github.wikibot.tasks.eswikt;
    opens com.github.wikibot.tasks.plwiki;
    opens com.github.wikibot.tasks.plwikt;
    opens com.github.wikibot.utils;
}
