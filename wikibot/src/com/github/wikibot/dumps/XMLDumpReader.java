package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import com.github.wikibot.parsing.eswikt.Page;
import com.github.wikibot.utils.Domains;

public final class XMLDumpReader {

	public static final File LOCAL_DUMPS_PATH = new File("./data/dumps");
	
	private File file;
	
	public XMLDumpReader(File file) throws FileNotFoundException {
		Objects.requireNonNull(file);
		this.file = file;
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
	}
	
	public XMLDumpReader(Domains domain) throws FileNotFoundException {
		Objects.requireNonNull(domain);
		this.file = getLocalFile(domain);
	}
	
	public XMLDumpReader(String pathToFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToFile);
		file = new File(pathToFile);
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
	}

	private static File getLocalFile(Domains domain) throws FileNotFoundException {
		String domainPrefix = resolveDomainName(domain);
		File[] matching = LOCAL_DUMPS_PATH.listFiles((dir, name) -> name.startsWith(domainPrefix));
		
		if (matching.length == 0) {
			throw new FileNotFoundException("Dump file not found: " + domain);
		}
		
		System.out.printf("Reading from file: %s%n", matching[0].getName());
		
		return matching[0];
	}
	
	private static String resolveDomainName(Domains domain) {
		switch (domain) {
			case PLWIKT:
				return "plwiktionary";
			case ESWIKT:
				return "eswiktionary";
			default:
				throw new UnsupportedOperationException();
		}
	}
	
	public void runSAXReader(Consumer<XMLRevision> cons) throws IOException, SAXException {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser;
		
		try {
			saxParser = factory.newSAXParser();
		} catch (ParserConfigurationException e) {
			return;
		}
		
	    ContentHandler handler = new SAXConcurrentPageHandler(cons);
	    XMLReader xmlReader = saxParser.getXMLReader();
	    xmlReader.setContentHandler(handler);
		
		try (InputStream is = new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			xmlReader.parse(new InputSource(is));
		}
	}
	
	public StAXDumpReader getStAXReader() throws XMLStreamException, IOException {
		InputStream is = new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(file)));
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLStreamReader streamReader = factory.createXMLStreamReader(is);
		return new StAXDumpReader(streamReader);
	}
	
	public List<XMLRevision> getParsedIncrementalDump() throws FileNotFoundException, IOException {
		Document doc;
		
		try (InputStream is = new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			doc = Jsoup.parse(is, null, "", Parser.xmlParser());
		}
		
		doc.outputSettings().prettyPrint(false);
		return JsoupDumpReader.parseDocument(doc);
	}
	
	public static void main(String[] args) throws Exception {
		XMLDumpReader reader = new XMLDumpReader(Domains.ESWIKT);
		List<String> list = Collections.synchronizedList(new ArrayList<>(5000));
		
		/*reader.runSAXReader(rev -> {
			Page page = Page.store(rev.title, rev.text);
			boolean anyMatch = page.getAllLangSections().stream()
				.anyMatch(ls -> ls.getTemplateParams().containsKey("alt"));
			
			if (anyMatch) {
				list.add(page.getTitle());
			}
		});*/
		
		// 866328 - 4717 - 85 s (vs 80 s)
		try (Stream<XMLRevision> stream = reader.getStAXReader().stream(900000)) {
			stream.parallel()
				//.filter(rev -> rev.ns == 0)
				//.limit(100)
				.map(rev -> Page.store(rev.title, rev.text))
				.filter(page -> page.getAllLangSections().stream()
					.anyMatch(ls -> ls.getTemplateParams().containsKey("alt"))
				)
				.forEach(page -> list.add(page.toString()));
		}
		
		System.out.printf("Total count: %d%n", list.size());
		//IOUtils.writeToFile(String.join("\n", list), "./data/test2.txt");
	}

}
