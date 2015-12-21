package com.github.wikibot.dumps;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class SAXConcurrentPageHandler extends DefaultHandler {
	private static final List<String> CONTENT_TAGS = Arrays.asList(
		"title", "ns", "id", "parentid", "timestamp", "username", "comment", "text"
	);
	
	private Consumer<XMLRevision> cons;
	private ExecutorService executor;
	private XMLRevision revision;
	private boolean acceptContent;
	private StringBuilder sb;
	
	public SAXConcurrentPageHandler(Consumer<XMLRevision> cons) {
		this.cons = cons;
	}
	
	public void startDocument() throws SAXException {
		executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	}
	
	public void startElement(String namespaceURI, String localName, String qName, Attributes atts) throws SAXException {
		if (qName.equals("page")) {
			revision = new XMLRevision();
		} else if (revision != null && CONTENT_TAGS.contains(qName)) {
			acceptContent = true;
			
			if (qName.equals("text")) {
				sb = new StringBuilder(2000);
			} else {
				sb = new StringBuilder(50);
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
				revision.title = decode(sb.toString());
				break;
			case "ns":
				revision.ns = Integer.parseInt(sb.toString());
				break;
			case "id": // hacky
				if (revision.pageid == 0) {
					revision.pageid = Integer.parseInt(sb.toString());
				} else if (revision.revid == 0) {
					revision.revid = Integer.parseInt(sb.toString());
				}
				break;
			case "redirect":
				revision.isRedirect = true;
				break;
			case "parentid":
				revision.parentid = Integer.parseInt(sb.toString());
				break;
			case "timestamp":
				revision.timestamp = sb.toString();
				break;
			case "username":
				revision.contributor = decode(sb.toString());
				break;
			case "ip": // either this or "username"
				revision.contributor = decode(sb.toString());
				break;
			case "minor":
				revision.isMinor = true;
				break;
			case "comment":
				revision.comment = decode(sb.toString());
				break;
			case "text":
				revision.text = decode(sb.toString());
				break;
			case "page":
				executor.execute(makeRunnable(cons, revision.clone()));
				revision = null;
				break;
		}
		
		acceptContent = false;
	}
	
	public void endDocument() throws SAXException {
		executor.shutdown();
	}
	
	private static Runnable makeRunnable(Consumer<XMLRevision> cons, XMLRevision revision) {
		return () -> cons.accept(revision);
	}
	
	private static String decode(String in) {
		// TODO: review? http://stackoverflow.com/a/1091953
		in = in.replace("&lt;", "<").replace("&gt;", ">");
		in = in.replace("&quot;", "\"");
		//in = in.replace("&apos;", "'");
		in = in.replace("&#039;", "'");
		in = in.replace("&amp;", "&");
		return in;
    }
}