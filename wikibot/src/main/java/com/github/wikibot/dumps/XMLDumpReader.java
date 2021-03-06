package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.io.FilenameUtils;
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
	
	public XMLDumpReader(String database) throws FileNotFoundException {
		Objects.requireNonNull(database);
		file = getLocalFile(database);
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
	
	private static File getLocalFile(String database) throws FileNotFoundException {
		Pattern dbPatt = Pattern.compile("^" + database + "\\b.+?\\.xml\\b.*");
		File[] matching = LOCAL_DUMPS_PATH.listFiles((dir, name) -> dbPatt.matcher(name).matches());
		
		if (matching.length == 0) {
			throw new FileNotFoundException("Dump file not found: " + database.toString());
		}
		
		return matching[0];
	}
	
	private InputStream getInputStream(long position) throws IOException, CompressorException {
		FileInputStream fis = new FileInputStream(file);
		InputStream is = new BufferedInputStream(fis);
		
		var extension = FilenameUtils.getExtension(file.getName());
		
		if (!extension.equals("xml")) {
			if (position != 0) {
				if (!extension.equals("bz2")) {
					fis.close();
					throw new UnsupportedOperationException("position mark only supported in .bz2 files");
				}
				
				// must be placed before the bzip compressor instantiation
				fis.getChannel().position(position);
				// workaround to allow multiple XML root elements (only .bz2)
				is = new XMLFragmentBZip2CompressorInputStream(is);
			} else {
				is = new CompressorStreamFactory().createCompressorInputStream(is);
			}
		}
		
		return is;
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
		
		try (InputStream is = getInputStream(0)) {
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
		return getStAXReader(0, 0);
	}
	
	public StAXDumpReader getStAXReader(long position) throws IOException {
		return getStAXReader(position, 0);
	}
	
	public StAXDumpReader getStAXReader(long position, int estimateSize) throws IOException {
		XMLStreamReader streamReader;
		
		try {
			InputStream is = getInputStream(position);
			XMLInputFactory factory = XMLInputFactory.newInstance();
			streamReader = factory.createXMLStreamReader(is);
		} catch (CompressorException | XMLStreamException e) {
			throw new IOException(e);
		}
		
		return new StAXDumpReader(streamReader, estimateSize);
	}
	
	public static void main(String[] args) throws Exception {
		var reader = new XMLDumpReader("eswiktionary");
		long count = 0;
		
		try (var stream = reader.getStAXReader().stream()) {
			count = stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.count();
		}
		
		System.out.printf("Total count: %d%n", count);
	}
}
