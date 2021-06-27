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
	
	// https://stackoverflow.com/a/41265267
	opens com.github.wikibot.tasks.eswikt;
	opens com.github.wikibot.tasks.plwiki;
	opens com.github.wikibot.tasks.plwikt;
}
