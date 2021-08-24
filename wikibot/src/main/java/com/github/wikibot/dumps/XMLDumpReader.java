package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
	public static final Path LOCAL_DUMPS = Paths.get("./data/dumps");
	
	private Path pathToDumpFile;
	
	public XMLDumpReader(String database) throws IOException {
		Objects.requireNonNull(database);
		pathToDumpFile = getLocalFile(database);
		System.out.printf("Reading from file: %s%n", pathToDumpFile);
	}
	
	public XMLDumpReader(Path pathToFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToFile);
		pathToDumpFile = pathToFile;
		
		if (!Files.exists(pathToFile)) {
			throw new FileNotFoundException(pathToFile.toString());
		}
		
		System.out.printf("Reading from file: %s%n", pathToFile);
	}
	
	public Path getPathToDump() {
		return Paths.get(pathToDumpFile.toUri()); // clone path
	}
	
	private static Path getLocalFile(String database) throws IOException {
		Pattern dbPatt = Pattern.compile("^" + database + "\\b.+?\\.xml\\b.*");
		
		try (var files = Files.list(LOCAL_DUMPS)) {
			return files
				.filter(Files::isRegularFile)
				.filter(path -> dbPatt.matcher(path.getFileName().toString()).matches())
				.findFirst()
				.orElseThrow(() -> new FileNotFoundException("Dump file not found: " + database));
		}
	}
	
	private InputStream getInputStream(long position) throws IOException, CompressorException {
		var extension = FilenameUtils.getExtension(pathToDumpFile.getFileName().toString());
		
		if (position != 0 && !extension.equals("bz2")) {
			throw new UnsupportedOperationException("position mark only supported in .bz2 files");
		}
		
		FileInputStream fis = new FileInputStream(pathToDumpFile.toFile());
		BufferedInputStream is = new BufferedInputStream(fis);
		
		if (!extension.equals("xml")) {
			if (position != 0) {
				fis.getChannel().position(position);
				return new XMLFragmentBZip2CompressorInputStream(is);
			} else {
				return new CompressorStreamFactory().createCompressorInputStream(is);
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
