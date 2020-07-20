package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.io.FilenameUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public final class XMLDumpReader {

	public static final File LOCAL_DUMPS_PATH = new File("./data/dumps");
	
	private File file;
	
	public XMLDumpReader(File file) throws FileNotFoundException {
		Objects.requireNonNull(file);
		this.file = file;
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
		
		System.out.printf("Reading from file: %s%n", file);
	}
	
	public XMLDumpReader(String domain) throws FileNotFoundException {
		Objects.requireNonNull(domain);
		file = getLocalFile(domain);
		System.out.printf("Reading from file: %s%n", file);
	}
	
	public XMLDumpReader(Path pathToFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToFile);
		file = pathToFile.toFile();
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
		
		System.out.printf("Reading from file: %s%n", file);
	}
	
	public File getFile() {
		return file;
	}

	private static File getLocalFile(String domain) throws FileNotFoundException {
		String domainPrefix = resolveDomainName(domain);
		File[] matching = LOCAL_DUMPS_PATH.listFiles((dir, name) -> name.startsWith(domainPrefix));
		
		if (matching.length == 0) {
			throw new FileNotFoundException("Dump file not found: " + domain.toString());
		}
		
		return matching[0];
	}
	
	private InputStream getInputStream() throws CompressorException, FileNotFoundException {
		String extension = FilenameUtils.getExtension(file.getName());
		InputStream bis = new BufferedInputStream(new FileInputStream(file));
		
		if (extension.equals("xml")) {
			return bis;
		} else {
			return new CompressorStreamFactory().createCompressorInputStream(bis);
		}
	}
	
	private static String resolveDomainName(String domain) {
		switch (domain) {
			case "pl.wiktionary.org":
				return "plwiktionary";
			case "es.wiktionary.org":
				return "eswiktionary";
			default:
				throw new UnsupportedOperationException("unknown domain name: " + domain);
		}
	}
	
	private void runSAXReaderTemplate(SAXPageHandler sph) throws IOException {
		XMLReader xmlReader;
		
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();
			ContentHandler handler = sph;
			xmlReader = saxParser.getXMLReader();
			xmlReader.setContentHandler(handler);
		} catch (ParserConfigurationException | SAXException e) {
			return;
		}
		
		try (InputStream is = getInputStream()) {
			xmlReader.parse(new InputSource(is));
		} catch (CompressorException | SAXException e) {
			throw new IOException(e);
		}
	}
	
	public void runSAXReader(Consumer<XMLRevision> cons) throws IOException {
		SAXPageHandler sph = new SAXPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	public void runParallelSAXReader(Consumer<XMLRevision> cons) throws IOException {
		SAXPageHandler sph = new SAXConcurrentPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	public StAXDumpReader getStAXReader() throws IOException {
		return getStAXReader(0);
	}
	
	public StAXDumpReader getStAXReader(int estimateSize) throws IOException {
		XMLStreamReader streamReader;
		
		try {
			InputStream is = getInputStream();
			XMLInputFactory factory = XMLInputFactory.newInstance();
			streamReader = factory.createXMLStreamReader(is);
		} catch (CompressorException | XMLStreamException e) {
			throw new IOException(e);
		}
		
		return new StAXDumpReader(streamReader, estimateSize);
	}
	
	public List<XMLRevision> getParsedIncrementalDump() throws IOException {
		Document doc;
		
		try (InputStream is = getInputStream()) {
			doc = Jsoup.parse(is, null, "", Parser.xmlParser());
		} catch (CompressorException e) {
			throw new IOException(e);
		}
		
		doc.outputSettings().prettyPrint(false);
		return JsoupDumpReader.parseDocument(doc);
	}
	
	public static void main(String[] args) throws Exception {
		XMLDumpReader reader = new XMLDumpReader("es.wiktionary.org");
		List<String> list = Collections.synchronizedList(new ArrayList<>(5000));
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(900000).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(XMLRevision::getTitle)
				.forEach(list::add);
		}
		
		System.out.printf("Total count: %d%n", list.size());
		/*StringBuilder sb = new StringBuilder((int) (list.size() * 8));
		
		synchronized (list) {
			Iterator<String> i = list.iterator();
			while (i.hasNext()) {
				sb.append(i.next()).append("\n");
			}
		}
		
		IOUtils.writeToFile(sb.toString(), "./data/test.txt");*/
	}

}
