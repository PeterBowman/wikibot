module org.github.wikibot {
	exports com.github.wikibot.irc;
	exports com.github.wikibot.parsing.eswikt;
	exports com.github.wikibot.dumps;
	exports com.github.wikibot.utils;
	exports com.github.wikibot.parsing.plwikt;
	exports com.github.wikibot.parsing;
	exports com.github.wikibot.telegram;
	exports com.github.wikibot.main;

	requires com.google.common;
	requires com.ibm.icu;
	requires commons.cli;
	requires commons.exec;
	requires java.logging;
	requires java.net.http;
	requires java.sql;
	requires java.xml;
	requires org.apache.commons.collections4;
	requires org.apache.commons.compress;
	requires org.apache.commons.io;
	requires org.apache.commons.lang3;
	requires org.apache.commons.text;
	requires org.json;
	requires org.jsoup;
	requires pircbot;
	requires plural4j;
	requires telegrambots;
	requires telegrambots.meta;
	requires univocity.parsers;
	requires org.wikipedia;
	requires xstream;
	requires org.nibor.autolink;
	
	// https://stackoverflow.com/a/41265267
	opens com.github.wikibot.tasks.eswikt;
	opens com.github.wikibot.tasks.plwiki;
	opens com.github.wikibot.tasks.plwikt;
}
