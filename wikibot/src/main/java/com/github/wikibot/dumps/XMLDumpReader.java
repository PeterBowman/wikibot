package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
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
	
	private long dumpSize;
	private boolean assumeMultiStream;
	
	public XMLDumpReader(String database) throws IOException {
		this(database, false);
	}
	
	public XMLDumpReader(String database, boolean useMultiStream) throws IOException {
		Objects.requireNonNull(database);
		assumeMultiStream = useMultiStream;
		searchLocalFiles(database);
	}
	
	public XMLDumpReader(Path pathToDumpFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToDumpFile);
		
		if (!Files.exists(pathToDumpFile)) {
			throw new FileNotFoundException(pathToDumpFile.toString());
		}
		
		this.pathToDumpFile = pathToDumpFile;
		assumeMultiStream = false;
		System.out.println("Reading from dump: " + pathToDumpFile);
	}
	
	public XMLDumpReader(Path pathToDumpFile, Path pathToIndexFile) throws FileNotFoundException {
		Objects.requireNonNull(pathToDumpFile);
		Objects.requireNonNull(pathToIndexFile);
		
		if (!Files.exists(pathToDumpFile)) {
			throw new FileNotFoundException(pathToDumpFile.toString());
		}
		
		if (!Files.exists(pathToIndexFile)) {
			throw new FileNotFoundException(pathToIndexFile.toString());
		}
		
		var extension = FilenameUtils.getExtension(pathToIndexFile.getFileName().toString());
		
		if (!extension.equals("bz2")) {
			throw new UnsupportedOperationException("position mark only supported in .bz2 files");
		}

		this.pathToDumpFile = pathToDumpFile;
		this.pathToIndexFile = pathToIndexFile;
		assumeMultiStream = true;
		System.out.println("Reading from dump: " + pathToDumpFile);
		System.out.println("Using index: " + pathToIndexFile);
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
	
	private void searchLocalFiles(String database) throws IOException {
		final Pattern dumpPatt;
		
		if (assumeMultiStream) {
			dumpPatt = Pattern.compile("^" + database + "\\b.+?-multistream\\.xml\\.bz2$");
		} else {
			dumpPatt = Pattern.compile("^" + database + "\\b.+?(?<!-multistream)\\.xml\\b.*");
		}
		
		var dumpPath = LOCAL_DUMPS.resolve("public").resolve(database).resolve("latest"); // preferred
		
		if (!Files.exists(dumpPath)) {
			dumpPath = LOCAL_DUMPS;
		}
		
		try (var files = Files.list(dumpPath)) {
			pathToDumpFile = files
				.filter(Files::isRegularFile)
				.filter(path -> dumpPatt.matcher(path.getFileName().toString()).matches())
				.findFirst()
				.orElseThrow(() -> new FileNotFoundException("Dump file not found: " + database));
		}
		
		System.out.println("Reading from dump: " + pathToDumpFile);
		
		if (assumeMultiStream) {
			var filename = pathToDumpFile.getFileName().toString().replaceFirst("\\.xml\\b", "-index.txt");
			pathToIndexFile = pathToDumpFile.resolveSibling(filename);
			
			if (!Files.exists(pathToIndexFile)) {
				throw new FileNotFoundException(pathToIndexFile.toString());
			}
			
			System.out.println("Using index: " + pathToIndexFile);
		}
	}
	
	private void maybeRetrieveOffsets() throws IOException {
		if (!assumeMultiStream || availableChunks != null) {
			return;
		}
		
		var extension = FilenameUtils.getExtension(pathToIndexFile.getFileName().toString());
		
		if (!extension.equals("bz2") && !extension.equals("txt")) {
			throw new UnsupportedOperationException("unsupported index file extension: " + extension);
		}
		
		var filteredOffsets = new LinkedHashSet<Long>(10000);
		var dumpSize = 0L;
		var lastOffset = 0L;
		var accumulator = 0;
		var addCurrentOffsetSize = false;
		
		try (
			var bufferedInput = new BufferedInputStream(Files.newInputStream(pathToIndexFile));
			var maybeCompressed = extension.equals("bz2") ? new BZip2CompressorInputStream(bufferedInput) : bufferedInput;
			var reader = new BufferedReader(new InputStreamReader(maybeCompressed))
		) {
			var it = reader.lines().iterator();
			
			while (it.hasNext()) {
				var line = it.next();
				
				var firstSeparator = line.indexOf(':');
				var secondSeparator = line.indexOf(':', firstSeparator + 1);
				
				var offset = Long.parseLong(line.substring(0, firstSeparator));
				var id = Long.parseLong(line.substring(firstSeparator + 1, secondSeparator));
				var title = line.substring(secondSeparator + 1);
				
				if (offset != lastOffset) {
					lastOffset = offset;
					addCurrentOffsetSize = false;
					accumulator = 0;
				}
				
				if ((filteredTitles == null || filteredTitles.contains(title)) && (filteredIds == null || filteredIds.contains(id))) {
					filteredOffsets.add(offset);
					
					if (!addCurrentOffsetSize) {
						dumpSize += accumulator;
						addCurrentOffsetSize = true;
					}
				}
				
				if (addCurrentOffsetSize) {
					dumpSize++;
				} else {
					accumulator++;
				}
			}
		}
		
		this.dumpSize = dumpSize;
		availableChunks = Collections.unmodifiableList(new ArrayList<>(filteredOffsets));
		System.out.printf("Multistream chunks retrieved: %d (dump size: %d)%n", availableChunks.size(), dumpSize);
	}
	
	private InputStream getInputStream() throws IOException {
		if (assumeMultiStream) {
			return new RootlessXMLInputStream(pathToDumpFile, availableChunks);
		} else {
			var extension = FilenameUtils.getExtension(pathToDumpFile.getFileName().toString());
			var input = new BufferedInputStream(Files.newInputStream(pathToDumpFile));

			if (!extension.equals("xml")) {
				try {
					return new CompressorStreamFactory(true).createCompressorInputStream(input);
				} catch (CompressorException e) {
					throw new IOException(e);
				}
			} else {
				return input;
			}
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
		
		maybeRetrieveOffsets();
		
		try (var is = getInputStream()) {
			xmlReader.parse(new InputSource(is));
		} catch (SAXException e) {
			throw new IOException(e);
		}
	}
	
	public void runSAXReader(Consumer<XMLRevision> cons) throws IOException {
		Objects.requireNonNull(cons);
		var sph = new SAXPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	public void runParallelSAXReader(Consumer<XMLRevision> cons) throws IOException {
		Objects.requireNonNull(cons);
		var sph = new SAXConcurrentPageHandler(cons);
		runSAXReaderTemplate(sph);
	}
	
	private Stream<XMLRevision> getStAXReaderStreamInternal(InputStream input) {
		try {
			var factory = XMLInputFactory.newInstance();
			var streamReader = factory.createXMLStreamReader(input);
			
			var stream = new StAXDumpReader(streamReader, dumpSize).stream().onClose(() -> {
				try {
					input.close();
					streamReader.close();
				} catch (XMLStreamException | IOException e) {
					throw new UncheckedIOException(new IOException(e));
				}
			});
			
			if (filteredTitles != null) {
				return stream.filter(rev -> filteredTitles.contains(rev.getTitle()));
			} else if (filteredIds != null) {
				return stream.filter(rev -> filteredIds.contains(rev.getPageid()));
			} else {
				return stream;
			}
		} catch (XMLStreamException e) {
			throw new UncheckedIOException(new IOException(e));
		}
	}
	
	public Stream<XMLRevision> getStAXReaderStream() throws IOException {
		maybeRetrieveOffsets();
		var input = getInputStream();
		return getStAXReaderStreamInternal(input);
	}
	
	public Stream<XMLRevision> getConcurrentStAXReaderStream() throws IOException {
		if (!assumeMultiStream) {
			throw new UnsupportedOperationException("only available in multistream dumps");
		}
		
		maybeRetrieveOffsets();
		
		return availableChunks.parallelStream()
			.map(offset -> new RootlessXMLInputStream(pathToDumpFile, List.of(offset)))
			.map(this::getStAXReaderStreamInternal)
			.flatMap(Function.identity());
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
		
		private Iterator<Long> offsetsIter;
		private FileInputStream dumpStream;
		private FileChannel dumpChannel;
		private ByteArrayInputStream startRoot;
		private ByteArrayInputStream endRoot;
		private InputStream currentInput;
		
		RootlessXMLInputStream(Path dump, Iterable<Long> offsets) {
			offsetsIter = offsets.iterator();
			startRoot = new ByteArrayInputStream(String.format("<%s>", ROOT_ELEMENT).getBytes());
			endRoot = new ByteArrayInputStream(String.format("</%s>", ROOT_ELEMENT).getBytes());
			
			try {
				dumpStream = new FileInputStream(dump.toFile());
				dumpChannel = dumpStream.getChannel();
			} catch (FileNotFoundException e) {
				throw new UncheckedIOException(e);
			}
		}
		
		private void prepareNextStream() {
			while (offsetsIter.hasNext()) {
				var offset = offsetsIter.next();
				
				try {
					dumpChannel.position(offset);
					currentInput = new BZip2CompressorInputStream(new BufferedInputStream(dumpStream));
					return;
				} catch (IOException e) {
					System.out.printf("Unable to read file at channel position %d: %s%n", offset, e.getMessage());
				}
			}
			
			currentInput = null;
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
				prepareNextStream();
			}
			
			while (currentInput != null) {
				var c = currentInput.read();
				
				if (c != -1) {
					return c;
				} else {
					prepareNextStream();
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
				
				prepareNextStream();
			}
			
			return read;
		}
		
		@Override
		public void close() throws IOException {
			dumpStream.close();
		}
	}
}
