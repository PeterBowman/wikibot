package com.github.wikibot.dumps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class StAXDumpReader implements Iterable<XMLRevision> {
	private XMLStreamReader streamReader;
	private final long estimateSize;
	
	public StAXDumpReader(XMLStreamReader streamReader) {
		this.streamReader = streamReader;
		this.estimateSize = 0;
	}
	
	public StAXDumpReader(XMLStreamReader streamReader, long estimateSize) {
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

	public Stream<XMLRevision> stream() {
		int characteristics = Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.ORDERED | Spliterator.SORTED;
		
		if (estimateSize > 0) {
			characteristics |= Spliterator.SIZED | Spliterator.SUBSIZED;
		}
		
		Spliterator<XMLRevision> spliterator;
		
		if (estimateSize == 0) {
			spliterator = Spliterators.spliteratorUnknownSize(iterator(), characteristics);
		} else {
			spliterator = Spliterators.spliterator(iterator(), estimateSize, characteristics);
		}
		
		return StreamSupport.stream(spliterator, false);
	}
	
	private class StAXIterator implements Iterator<XMLRevision> {
		private final StringBuilder buffer;
		private PageInfo pageInfo;
		private boolean appendable;
		
		public StAXIterator() {
			buffer = new StringBuilder(100000);
			pageInfo = new PageInfo();
			appendable = false;
		}
		
		@Override
		public boolean hasNext() {
			try {
				var parsePageElement = false;
				var consumer = new PageConsumer(pageInfo);
				pageInfo.clear();
				
				while (streamReader.hasNext() && streamReader.next() != XMLStreamConstants.END_DOCUMENT) {
					if (streamReader.getEventType() == XMLStreamConstants.START_ELEMENT) {
						if (streamReader.getLocalName().equals("page")) {
							parsePageElement = true;
							continue;
						} else if (streamReader.getLocalName().equals("revision")) {
							return true;
						}
					}
					
					if (parsePageElement) {
						parseCurrentElement(consumer);
					}
				}
				
				return false;
			} catch (XMLStreamException e) {
				throw new UncheckedIOException(new IOException(e));
			}
		}
		
		@Override
		public XMLRevision next() {
			try {
				var revision = pageInfo.makeRevision();
				var consumer = new RevisionConsumer(revision);
				
				while (!(streamReader.next() == XMLStreamConstants.END_ELEMENT && streamReader.getLocalName().equals("revision"))) {
					parseCurrentElement(consumer);
				}
				
				return revision;
			} catch (XMLStreamException e) {
				throw new UncheckedIOException(new IOException(e));
			}
		}
		
		private void parseCurrentElement(XmlConsumer consumer) {
			switch (streamReader.getEventType()) {
				case XMLStreamConstants.START_ELEMENT:
					buffer.setLength(0);
					appendable = true;
					consumer.acceptStartElement(streamReader);
					break;
				case XMLStreamConstants.CHARACTERS:
					if (appendable) {
						buffer.append(streamReader.getTextCharacters(), streamReader.getTextStart(), streamReader.getTextLength());
					}
					
					break;
				case XMLStreamConstants.END_ELEMENT:
					if (appendable) {
						consumer.acceptEndElement(streamReader, buffer);
						appendable = false;
					}
					
					break;
			}
		}
	}
	
	private static class PageInfo {
		String title;
		int ns;
		long id;
		boolean isRedirect;
		
		void clear() {
			isRedirect = false;
		}
		
		XMLRevision makeRevision() {
			var revision = new XMLRevision();
			revision.title = title;
			revision.ns = ns;
			revision.pageid = id;
			revision.isRedirect = isRedirect;
			return revision;
		}
	}
	
	private static interface XmlConsumer {
		void acceptStartElement(XMLStreamReader reader);
		void acceptEndElement(XMLStreamReader reader, StringBuilder sb);
	}
	
	private static class PageConsumer implements XmlConsumer {
		private PageInfo page;
		
		PageConsumer(PageInfo pageInfo) {
			this.page = pageInfo;
		}
		
		@Override
		public void acceptStartElement(XMLStreamReader reader) {}
		
		@Override
		public void acceptEndElement(XMLStreamReader reader, StringBuilder sb) {
			switch (reader.getLocalName()) {
				case "title":
					page.title = sb.toString();
					break;
				case "ns":
					page.ns = Integer.parseInt(sb.toString());
					break;
				case "id":
					page.id = Long.parseLong(sb.toString());
					break;
				case "redirect":
					page.isRedirect = true;
					break;
			}
		}
	}
	
	private static class RevisionConsumer implements XmlConsumer {
		private XMLRevision revision;
		
		RevisionConsumer(XMLRevision revision) {
			this.revision = revision;
		}
		
		@Override
		public void acceptStartElement(XMLStreamReader reader) {
			for (int i = 0; i < reader.getAttributeCount(); i++) {
				switch (reader.getLocalName()) {
					case "contributor":
						if (reader.getAttributeLocalName(i).equals("deleted")) {
							revision.isUserDeleted = true;
						}
						
						break;
					case "comment":
						if (reader.getAttributeLocalName(i).equals("deleted")) {
							revision.isCommentDeleted = true;
						}
						
						break;
					case "text":
						if (reader.getAttributeLocalName(i).equals("bytes")) {
							revision.bytes = Integer.parseInt(reader.getAttributeValue(i));
						} else if (reader.getAttributeLocalName(i).equals("deleted")) {
							revision.isRevDeleted = true;
						}
						
						break;
					}
			}
		}
		
		@Override
		public void acceptEndElement(XMLStreamReader reader, StringBuilder sb) {
			switch (reader.getLocalName()) {
				case "id":
					// ignore if already set, e.g. contributor's id
					if (revision.revid == 0) {
						revision.revid = Long.parseLong(sb.toString());
					}
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
			}
		}
	}
}
