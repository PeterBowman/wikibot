package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.io.FilenameUtils;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public final class XMLDumpReader {
	public static final Path LOCAL_DUMPS = Paths.get("./data/dumps");
	
	private Path pathToDumpFile;
	private Path pathToIndexFile;
	
	private Set<String> filteredTitles;
	private Set<Long> filteredIds;
	private List<Long> availableChunks;
	
	private boolean assumeMultiStream;
	
	public XMLDumpReader(String database) throws IOException {
		this(database, false);
	}
	
	public XMLDumpReader(String database, boolean useMultiStream) throws IOException {
		Objects.requireNonNull(database);
		assumeMultiStream = useMultiStream;
		searchLocalFiles(database, useMultiStream);
	}
	
	public XMLDumpReader(Path pathToDumpFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToDumpFile);
		this.pathToDumpFile = pathToDumpFile;
		
		if (!Files.exists(pathToDumpFile)) {
			throw new FileNotFoundException(pathToDumpFile.toString());
		}
		
		assumeMultiStream = false;
		System.out.printf("Reading from dump: %s%n", pathToDumpFile);
	}
	
	public XMLDumpReader(Path pathToDumpFile, Path pathToIndexFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToDumpFile);
		Objects.requireNonNull(pathToIndexFile);
		this.pathToDumpFile = pathToDumpFile;
		this.pathToIndexFile = pathToIndexFile;
		
		if (!Files.exists(pathToDumpFile)) {
			throw new FileNotFoundException(pathToDumpFile.toString());
		}
		
		if (!Files.exists(pathToIndexFile)) {
			throw new FileNotFoundException(pathToIndexFile.toString());
		}
		
		assumeMultiStream = true;
		System.out.printf("Using index: %s%n", pathToIndexFile);
	}
	
	public XMLDumpReader seekTitles(Collection<String> filteredTitles) {
		Objects.requireNonNull(filteredTitles);
		
		if (!assumeMultiStream) {
			throw new UnsupportedOperationException("position marks only available in multistream dumps");
		}
		
		if (filteredIds != null) {
			throw new UnsupportedOperationException("already provided id filter");
		}
		
		this.filteredTitles = Collections.unmodifiableSet(new HashSet<>(filteredTitles));
		availableChunks = null;
		return this;
	}
	
	public XMLDumpReader seekIds(Collection<Long> filteredIds) {
		Objects.requireNonNull(filteredIds);
		
		if (!assumeMultiStream) {
			throw new UnsupportedOperationException("position marks only available in multistream dumps");
		}
		
		if (filteredTitles != null) {
			throw new UnsupportedOperationException("already provided title filter");
		}
		
		this.filteredIds = Collections.unmodifiableSet(new HashSet<>(filteredIds));
		availableChunks = null;
		return this;
	}
	
	public Path getPathToDump() {
		return Paths.get(pathToDumpFile.toUri()); // clone path
	}
	
	private void searchLocalFiles(String database, boolean useMultiStream) throws IOException {
		var dbPatt = Pattern.compile("^" + database + "\\b.+?" + (useMultiStream ? "-multistream" : "(?<!-multistream)") +"\\.xml\\b.*");
		
		try (var files = Files.list(LOCAL_DUMPS)) {
			pathToDumpFile = files
				.filter(Files::isRegularFile)
				.filter(path -> dbPatt.matcher(path.getFileName().toString()).matches())
				.findFirst()
				.orElseThrow(() -> new FileNotFoundException("Dump file not found: " + database));
		}
		
		System.out.printf("Reading from dump: %s%n", pathToDumpFile);
		
		if (useMultiStream) {
			var filename = pathToDumpFile.getFileName().toString().replaceFirst("\\.xml\\b", "-index.txt");
			pathToIndexFile = pathToDumpFile.resolveSibling(filename);
			
			if (!Files.exists(pathToIndexFile)) {
				throw new FileNotFoundException(pathToIndexFile.toString());
			}
			
			System.out.printf("Using index: %s%n", pathToIndexFile);
		}
	}
	
	private void maybeRetrieveOffsets() throws IOException, CompressorException {
		if (availableChunks != null) {
			return;
		}
		
		var extension = FilenameUtils.getExtension(pathToIndexFile.getFileName().toString());
		
		if (!extension.equals("bz2") && !extension.equals("txt")) {
			throw new UnsupportedOperationException("unsupported index file extension: " + extension);
		}
		
		class Entry {
			Long chunk;
			Long id;
			String title;
		}
		
		try (
			var input = Files.newInputStream(pathToIndexFile);
			var maybeCompressed = extension.equals("bz2") ? new BZip2CompressorInputStream(input) : input;
			var reader = new BufferedReader(new InputStreamReader(maybeCompressed))
		) {
			var stream = reader.lines().map(line -> {
				var firstSeparator = line.indexOf(':');
				var secondSeparator = line.indexOf(':', firstSeparator + 1);
				
				var item = new Entry();
				item.chunk = Long.parseLong(line.substring(0, firstSeparator));
				item.id = Long.parseLong(line.substring(firstSeparator + 1, secondSeparator));
				item.title = line.substring(secondSeparator + 1);
				
				return item;
			});
			
			if (filteredTitles != null) {
				stream = stream.filter(item -> filteredTitles.contains(item.title));
			} else if (filteredIds != null) {
				stream = stream.filter(item -> filteredIds.contains(item.id));
			}
			
			availableChunks = stream.map(item -> item.chunk).distinct().collect(Collectors.toList());
		}
		
		System.out.println("Multistream chunks retrieved: " + availableChunks.size());
	}
	
	private InputStream getInputStream() throws IOException, CompressorException {
		var extension = FilenameUtils.getExtension(pathToDumpFile.getFileName().toString());
		
		if (assumeMultiStream) {
			if (!extension.equals("bz2")) {
				throw new UnsupportedOperationException("position mark only supported in .bz2 files");
			}
			
			maybeRetrieveOffsets();
			return new RootlessXMLInputStream(pathToDumpFile, availableChunks);
		} else {
			var isCompressed = !extension.equals("xml");
			return getInputStream(isCompressed);
		}
	}
	
	private InputStream getInputStream(boolean isCompressed) throws IOException, CompressorException {
		var is = new BufferedInputStream(Files.newInputStream(pathToDumpFile));
		
		if (isCompressed) {
			return new CompressorStreamFactory().createCompressorInputStream(is);
		} else {
			return is;
		}
	}
	
	private void runSAXReaderTemplate(SAXPageHandler sph) throws IOException {
		XMLReader xmlReader;
		
		try {
			var factory = SAXParserFactory.newInstance();
			var saxParser = factory.newSAXParser();
			xmlReader = saxParser.getXMLReader();
			xmlReader.setContentHandler(sph);
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException(e);
		}
		
		try (var is = getInputStream()) {
			xmlReader.parse(new InputSource(is));
		} catch (CompressorException | SAXException e) {
			throw new IOException(e);
		}
	}
	
	public void runSAXReader(Consumer<XMLRevision> cons) throws IOException {
		var sph = new SAXPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	public void runParallelSAXReader(Consumer<XMLRevision> cons) throws IOException {
		var sph = new SAXConcurrentPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	public Stream<XMLRevision> getStAXReaderStream() throws IOException {
		try {
			var factory = XMLInputFactory.newInstance();
			var input = getInputStream();
			var streamReader = factory.createXMLStreamReader(input);
			
			var stream = new StAXDumpReader(streamReader).stream().onClose(() -> {
				try {
					input.close();
					streamReader.close();
				} catch (XMLStreamException | IOException e) {
					throw new RuntimeException(e);
				}
			});
			
			if (filteredTitles != null) {
				return stream.filter(rev -> filteredTitles.contains(rev.getTitle()));
			} else if (filteredIds != null) {
				return stream.filter(rev -> filteredIds.contains(rev.getPageid()));
			} else {
				return stream;
			}
		} catch (CompressorException | XMLStreamException e) {
			throw new IOException(e);
		}
	}
	
	public static void main(String[] args) throws Exception {
		var reader = new XMLDumpReader("eswiktionary");
		long count = 0;
		
		try (var stream = reader.getStAXReaderStream()) {
			count = stream
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.count();
		}
		
		System.out.printf("Total count: %d%n", count);
	}
	
	private static class RootlessXMLInputStream extends InputStream {
		private static final String ROOT_ELEMENT = "dummy_root";
		
		private Path pathToDump;
		private Iterator<Long> offsets;
		
		private ByteArrayInputStream startRoot;
		private ByteArrayInputStream endRoot;
		private InputStream currentInput;
		
		RootlessXMLInputStream(Path pathToDump, Iterable<Long> offsets) {
			this.pathToDump = pathToDump;
			this.offsets = offsets.iterator();
			
			startRoot = new ByteArrayInputStream(String.format("<%s>", ROOT_ELEMENT).getBytes());
			endRoot = new ByteArrayInputStream(String.format("</%s>", ROOT_ELEMENT).getBytes());
		}
		
		private InputStream getCompressedInputStream(long position) throws IOException {
			var fis = new FileInputStream(pathToDump.toFile());
			fis.getChannel().position(position);
			return new BZip2CompressorInputStream(new BufferedInputStream(fis));
		}
		
		private void getNextStream() throws IOException {
			if (currentInput != null) {
				currentInput.close();
			}
			
			if (offsets.hasNext()) {
				var offset = offsets.next();
				currentInput = getCompressedInputStream(offset);
			} else {
				currentInput = null;
			}
		}
		
		@Override
		public int available() throws IOException {
			if (startRoot.available() != 0) {
				return startRoot.available();
			} else if (currentInput != null) {
				return currentInput.available();
			} else {
				return endRoot.available();
			}
		}
		
		@Override
		public int read() throws IOException {
			if (startRoot.available() != 0) {
				return startRoot.read();
			}
			
			if (currentInput == null) {
				getNextStream();
			}
			
			while (currentInput != null) {
				var c = currentInput.read();
				
				if (c != -1) {
					return c;
				} else {
					getNextStream();
				}
			}
			
			return endRoot.read();
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (currentInput == null) {
				return super.read(b, off, len);
			}
			
			Objects.requireNonNull(b);
			
			if (off < 0 || len < 0 || len > b.length - off) {
				throw new IndexOutOfBoundsException();
			} else if (len == 0) {
				return 0;
			}
			
			int read = 0;
			
			while (currentInput != null) {
				int n = currentInput.read(b, off, len);
				
				if (n == len) {
					read += len;
					break;
				} else if (n > 0) {
					read += n;
					len -= n;
					off += n;
				}
				
				getNextStream();
			}
			
			return read;
		}
		
		@Override
		public void close() throws IOException {
			if (currentInput != null) {
				currentInput.close();
			}
		}
	}
}
