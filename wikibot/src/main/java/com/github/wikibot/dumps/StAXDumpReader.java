package com.github.wikibot.dumps;

import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class StAXDumpReader implements Iterable<XMLRevision>, AutoCloseable {
	private static final Set<String> RECOGNIZED_TAGS = Set.of(
		"title", "ns", "id", "redirect", "parentid", "timestamp", "username", "minor", "comment", "text"
	);
	
	private XMLStreamReader streamReader;
	private final int estimateSize;
	
	public StAXDumpReader(XMLStreamReader streamReader) {
		this.streamReader = streamReader;
		this.estimateSize = 0;
	}
	
	public StAXDumpReader(XMLStreamReader streamReader, int estimateSize) {
		if (estimateSize < 0) {
			throw new IllegalArgumentException("Negative estimateSize value: " + estimateSize);
		}
		
		this.streamReader = streamReader;
		this.estimateSize = estimateSize;
	}

	@Override
	public Iterator<XMLRevision> iterator() {
		return new StAXIterator();
	}

	@Override
	public void close() throws Exception {
		streamReader.close();
	}
	
	public Stream<XMLRevision> stream() {
		int characteristics = Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.ORDERED;
		Spliterator<XMLRevision> spliterator;
		
		if (estimateSize == 0) {
			spliterator = Spliterators.spliteratorUnknownSize(iterator(), characteristics);
		} else {
			spliterator = Spliterators.spliterator(iterator(), estimateSize, characteristics);
		}
		
		return StreamSupport.stream(spliterator, false);
	}
	
	private static void initializeMember(String name, String text, XMLRevision revision) {
		switch (name) {
			case "title":
				revision.title = decode(text);
				break;
			case "ns":
				revision.ns = Integer.parseInt(text);
				break;
			case "id": // hacky
				if (revision.pageid == 0) {
					revision.pageid = Integer.parseInt(text);
				} else if (revision.revid == 0) {
					revision.revid = Integer.parseInt(text);
				}
				break;
			case "redirect":
				revision.isRedirect = true;
				break;
			case "parentid":
				revision.parentid = Integer.parseInt(text);
				break;
			case "timestamp":
				revision.timestamp = text;
				break;
			case "username":
				revision.contributor = decode(text);
				break;
			case "ip": // either this or "username"
				revision.contributor = decode(text);
				revision.isAnonymousContributor = true;
				break;
			case "minor":
				revision.isMinor = true;
				break;
			case "comment":
				revision.comment = decode(text);
				break;
			case "text":
				revision.text = decode(text);
				break;
		}
	}
	
	private static String decode(String in) {
		// TODO: review? http://stackoverflow.com/a/1091953
		in = in.replace("&lt;", "<").replace("&gt;", ">");
		in = in.replace("&quot;", "\"");
		in = in.replace("&apos;", "'");
		in = in.replace("&#039;", "'");
		in = in.replace("&amp;", "&");
		return in;
	}
	
	private class StAXIterator implements Iterator<XMLRevision> {
		@Override
		public boolean hasNext() {
			try {
				while (streamReader.hasNext()) {
					if (
						streamReader.next() == XMLStreamReader.START_ELEMENT &&
						streamReader.getLocalName().equals("page")
					) {
						return true;
					}
				}
			} catch (XMLStreamException e) {
				e.printStackTrace();
				return false;
			}
			
			return false;
		}
		
		@Override
		public XMLRevision next() {
			try {
				return buildNextRevision();
			} catch (XMLStreamException e) {
				e.printStackTrace();
				return null;
			}
		}
		
		private XMLRevision buildNextRevision() throws XMLStreamException {
			XMLRevision revision = new XMLRevision();
			
			while (streamReader.hasNext()) {
				int next = streamReader.next();
				
				if (
					next == XMLStreamReader.START_ELEMENT &&
					RECOGNIZED_TAGS.contains(streamReader.getLocalName())
				) {
					initializeMember(streamReader.getLocalName(), streamReader.getElementText(), revision);
				} else if (
					next == XMLStreamReader.END_ELEMENT &&
					streamReader.getLocalName().equals("page")
				) {
					break;
				}
			}
			
			return revision;
		}
	}
}
