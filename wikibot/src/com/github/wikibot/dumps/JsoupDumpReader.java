package com.github.wikibot.dumps;

import static org.apache.commons.lang3.StringEscapeUtils.unescapeXml;

import java.util.List;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public final class JsoupDumpReader {
	JsoupDumpReader() {}
	
	public static List<XMLRevision> parseDocument(Document doc) {
		return doc.select("page > revision").stream()
			.map(JsoupDumpReader::parseRevision)
			.collect(Collectors.toList());
	}
	
	private static XMLRevision parseRevision(Element revision) {
		XMLRevision rev = new XMLRevision();
		Elements siblings = revision.siblingElements();
		
		Element title = siblings.stream().filter(el -> el.tagName().equals("title")).findAny().get();
		rev.title = unescapeXml(title.text());
		
		Element ns = siblings.stream().filter(el -> el.tagName().equals("ns")).findAny().get();
		rev.ns = Integer.parseInt(ns.text());
		
		Element pageid = siblings.stream().filter(el -> el.tagName().equals("id")).findAny().get();
		rev.pageid = Integer.parseInt(pageid.text());
		
		rev.isRedirect = siblings.stream().anyMatch(el -> el.tagName().equals("redirect"));
		
		Element revid = revision.children().stream().filter(el -> el.tagName().equals("id")).findAny().get();
		rev.revid = Integer.parseInt(revid.text());
		
		try {
			rev.parentid = Integer.parseInt(revision.getElementsByTag("parentid").get(0).text());
		} catch (IndexOutOfBoundsException e) {}
		
		rev.timestamp = revision.getElementsByTag("timestamp").get(0).text();
		
		Element contributor = revision.getElementsByTag("contributor").get(0);
		
		try {
			Element username = contributor.getElementsByTag("username").get(0);
			
			// revdeleted
			if (!username.tag().isSelfClosing()) {
				rev.contributor = unescapeXml(username.text());
			}
		} catch (IndexOutOfBoundsException e) {
			Element ip = contributor.getElementsByTag("ip").get(0);
			
			// revdeleted, not sure if it applies here
			if (!ip.tag().isSelfClosing()) {
				rev.contributor = ip.text();
			}
		}
		
		rev.isMinor = !revision.getElementsByTag("minor").isEmpty();
		
		try {
			Element comment = revision.getElementsByTag("comment").get(0);
			
			// revdeleted
			if (!comment.tag().isSelfClosing()) {
				rev.comment = unescapeXml(comment.text());
			}
		} catch (IndexOutOfBoundsException e) {}
		
		Element text = revision.getElementsByTag("text").get(0);
		
		// revdeleted or missing (stubs.xml)
		if (!text.tag().isSelfClosing()) {
			rev.text = unescapeXml(text.html());
		}
		
		return rev;
	}
}
