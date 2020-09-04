package com.github.wikibot.dumps;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class StAXDumpReader implements Iterable<XMLRevision>, AutoCloseable {
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
				revision.title = text;
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
				revision.contributor = text;
				break;
			case "ip": // either this or "username"
				revision.contributor = text;
				revision.isAnonymousContributor = true;
				break;
			case "minor":
				revision.isMinor = true;
				break;
			case "comment":
				revision.comment = text;
				break;
			case "text":
				revision.text = text;
				break;
		}
	}
	
	private class StAXIterator implements Iterator<XMLRevision> {
		private final StringBuilder buffer;
		
		public StAXIterator() {
			buffer = new StringBuilder(100000);
		}
		
		@Override
		public boolean hasNext() {
			try {
				while (streamReader.hasNext()) {
					if (
						streamReader.next() == XMLStreamConstants.START_ELEMENT &&
						streamReader.getLocalName().equals("page")
					) {
						return true;
					}
				}
				
				return false;
			} catch (XMLStreamException e) {
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public XMLRevision next() {
			try {
				return buildNextRevision();
			} catch (XMLStreamException e) {
				throw new RuntimeException(e);
			}
		}
		
		private XMLRevision buildNextRevision() throws XMLStreamException {
			XMLRevision revision = new XMLRevision();
			boolean appendable = false;
			
			outer:
			while (streamReader.next() != XMLStreamConstants.END_DOCUMENT) {
				switch (streamReader.getEventType()) {
					case XMLStreamConstants.START_ELEMENT:
						buffer.setLength(0);
						appendable = true;
						break;
					case XMLStreamConstants.CHARACTERS:
						if (appendable) {
							buffer.append(streamReader.getTextCharacters(), streamReader.getTextStart(), streamReader.getTextLength());
						}
						
						break;
					case XMLStreamConstants.END_ELEMENT:
						if (streamReader.getLocalName().equals("page")) {
							break outer;
						} else if (appendable) {
							initializeMember(streamReader.getLocalName(), buffer.toString(), revision);
							appendable = false;
						}
						
						break;
				}
			}
			
			return revision;
		}
	}
}
