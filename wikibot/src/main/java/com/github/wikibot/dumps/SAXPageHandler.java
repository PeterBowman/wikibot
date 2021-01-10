package com.github.wikibot.dumps;

import java.util.Set;
import java.util.function.Consumer;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class SAXPageHandler extends DefaultHandler {

	protected static final Set<String> CONTENT_TAGS = Set.of(
		"title", "ns", "id", "redirect", "parentid", "timestamp", "username", "ip", "minor", "comment", "text"
	);

	protected Consumer<XMLRevision> cons;
	protected XMLRevision revision;
	protected boolean acceptContent;
	protected StringBuilder sb;

	public SAXPageHandler(Consumer<XMLRevision> cons) {
		this.cons = cons;
	}

	public void startElement(String namespaceURI, String localName, String qName, Attributes atts) throws SAXException {
		if (qName.equals("page")) {
			revision = new XMLRevision();
		} else if (revision != null && CONTENT_TAGS.contains(qName)) {
			acceptContent = true;
			
			if (qName.equals("text")) {
				String bytes = atts.getValue("bytes");
				sb = new StringBuilder(Integer.parseInt(bytes));
			} else {
				sb = new StringBuilder();
			}
		}
	}

	public void characters(char[] ch, int start, int length) throws SAXException {
		if (acceptContent) {
			sb.append(ch, start, length);
		}
	}

	public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
		if (revision == null) {
			return;
		}
		
		switch (qName) {
			case "title":
				revision.title = sb.toString();
				break;
			case "ns":
				revision.ns = Integer.parseInt(sb.toString());
				break;
			case "id": // hacky
				if (revision.pageid == 0) {
					revision.pageid = Long.parseLong(sb.toString());
				} else if (revision.revid == 0) {
					revision.revid = Long.parseLong(sb.toString());
				}
				break;
			case "redirect":
				revision.isRedirect = true;
				break;
			case "parentid":
				revision.parentid = Long.parseLong(sb.toString());
				break;
			case "timestamp":
				revision.timestamp = sb.toString();
				break;
			case "username":
				revision.contributor = sb.toString();
				break;
			case "ip": // either this or "username"
				revision.contributor = sb.toString();
				revision.isAnonymousContributor = true;
				break;
			case "minor":
				revision.isMinor = true;
				break;
			case "comment":
				revision.comment = sb.toString();
				break;
			case "text":
				revision.text = sb.toString();
				break;
			case "page":
				processRevision();
				revision = null;
				break;
		}
		
		acceptContent = false;
	}
	
	protected void processRevision() {
		cons.accept(revision);
	}
}
