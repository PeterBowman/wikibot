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

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
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

import com.github.wikibot.parsing.eswikt.Page;
import com.github.wikibot.utils.Domains;

public final class XMLDumpReader {

	public static final File LOCAL_DUMPS_PATH = new File("./data/dumps");
	
	private File file;
	private Extension extension;
	
	public XMLDumpReader(File file) throws FileNotFoundException {
		Objects.requireNonNull(file);
		this.file = file;
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
		
		checkExtension();
	}
	
	public XMLDumpReader(Domains domain) throws FileNotFoundException {
		Objects.requireNonNull(domain);
		file = getLocalFile(domain);
		checkExtension();
	}
	
	public XMLDumpReader(String pathToFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToFile);
		file = new File(pathToFile);
		
		if (!file.exists()) {
			throw new FileNotFoundException();
		}
		
		checkExtension();
	}

	private static File getLocalFile(Domains domain) throws FileNotFoundException {
		String domainPrefix = resolveDomainName(domain);
		File[] matching = LOCAL_DUMPS_PATH.listFiles((dir, name) -> name.startsWith(domainPrefix));
		
		if (matching.length == 0) {
			throw new FileNotFoundException("Dump file not found: " + domain.toString());
		}
		
		System.out.printf("Reading from file: %s%n", matching[0].getName());
		return matching[0];
	}
	
	private InputStream getInputStream() throws IOException, CompressorException, ArchiveException {
		InputStream bis = new BufferedInputStream(new FileInputStream(file));
		
		switch (extension) {
			case EXT_bz2:
			case EXT_gz:
				return new CompressorStreamFactory().createCompressorInputStream(bis);
			case EXT_7z:
				return new ArchiveStreamFactory("UTF8").createArchiveInputStream(bis);
			default:
				bis.close();
				throw new UnsupportedOperationException();
		}
	}
	
	private void checkExtension() {
		String ext = FilenameUtils.getExtension(file.getName());
		
		switch (ext) {
			case "bz2":
				extension = Extension.EXT_bz2;
				break;
			case "gz":
				extension = Extension.EXT_gz;
				break;
			case "7z":
				extension = Extension.EXT_7z;
				break;
			case "":
				throw new UnsupportedOperationException("Unsupported extension: no extension");
			default:
				throw new UnsupportedOperationException("Unsupported extension: " + ext);
		}
	}
	
	private enum Extension {
		EXT_bz2,
		EXT_gz,
		EXT_7z
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
	
	public void runParallelSAXReader(Consumer<XMLRevision> cons) throws IOException {
		XMLReader xmlReader;
		
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();
			ContentHandler handler = new SAXConcurrentPageHandler(cons);
			xmlReader = saxParser.getXMLReader();
			xmlReader.setContentHandler(handler);
		} catch (ParserConfigurationException | SAXException e) {
			return;
		}
		
		try (InputStream is = getInputStream()) {
			xmlReader.parse(new InputSource(is));
		} catch (CompressorException | ArchiveException | SAXException e) {
			throw new IOException(e);
		}
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
		} catch (CompressorException | ArchiveException | XMLStreamException e) {
			throw new IOException(e);
		}
		
		return new StAXDumpReader(streamReader, estimateSize);
	}
	
	public List<XMLRevision> getParsedIncrementalDump() throws IOException {
		Document doc;
		
		try (InputStream is = getInputStream()) {
			doc = Jsoup.parse(is, null, "", Parser.xmlParser());
		} catch (CompressorException | ArchiveException e) {
			throw new IOException(e);
		}
		
		doc.outputSettings().prettyPrint(false);
		return JsoupDumpReader.parseDocument(doc);
	}
	
	public static void main(String[] args) throws Exception {
		XMLDumpReader reader = new XMLDumpReader(Domains.ESWIKT);
		List<String> list = Collections.synchronizedList(new ArrayList<>(5000));
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(900000).stream()) {
			stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.map(rev -> Page.store(rev.title, rev.text))
				.filter(page -> page.getAllLangSections().stream()
					.anyMatch(ls -> ls.getTemplateParams().containsKey("alt"))
				)
				.forEach(page -> list.add(page.getTitle()));
		}
		
		System.out.printf("Total count: %d%n", list.size());
		//IOUtils.writeToFile(String.join("\n", list), "./data/test2.txt");
	}

}
