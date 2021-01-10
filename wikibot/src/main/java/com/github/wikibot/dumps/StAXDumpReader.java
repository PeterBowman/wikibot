package com.github.wikibot.dumps;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
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
				throw new RuntimeException(e);
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
				throw new RuntimeException(e);
			}
		}
		
		private void parseCurrentElement(BiConsumer<XMLStreamReader, StringBuilder> consumer) {
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
					if (appendable) {
						consumer.accept(streamReader, buffer);
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
	
	private static class PageConsumer implements BiConsumer<XMLStreamReader, StringBuilder> {
		private PageInfo page;
		
		PageConsumer(PageInfo pageInfo) {
			this.page = pageInfo;
		}
		
		@Override
		public void accept(XMLStreamReader reader, StringBuilder sb) {
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
	
	private static class RevisionConsumer implements BiConsumer<XMLStreamReader, StringBuilder> {
		private XMLRevision revision;
		
		RevisionConsumer(XMLRevision revision) {
			this.revision = revision;
		}
		
		@Override
		public void accept(XMLStreamReader reader, StringBuilder sb) {
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
