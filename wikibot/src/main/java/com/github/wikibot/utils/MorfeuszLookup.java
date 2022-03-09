package com.github.wikibot.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FilenameUtils;

import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.ResultIterator;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public class MorfeuszLookup {
	private Path pathToDump;
	private boolean compressed;
	final private TsvParserSettings settings;
	
	public MorfeuszLookup(Path path) throws IOException {
		this(path, checkExtension(path));
	}
	
	public MorfeuszLookup(Path path, boolean useCompression) {
		pathToDump = Objects.requireNonNull(path);
		compressed = useCompression;
		
		settings = new TsvParserSettings();
		// had some issues with copyright notice (uses Windows CRLF, main content is Unix LF)
		settings.setNumberOfRowsToSkip(29);
		settings.setLineSeparatorDetectionEnabled(false);
		settings.setHeaderExtractionEnabled(false);
		settings.getFormat().setLineSeparator("\n");
		// process leaves dangling thread on exit if true (default on multi-core machines)
		settings.setReadInputOnSeparateThread(false);
	}
	
	private static boolean checkExtension(Path path) throws IOException {
		var contentType = Files.probeContentType(path);
		// there might be no FileTypeDetector installed
		var extension = FilenameUtils.getExtension(path.toString());
		return "application/x-gzip".equals(contentType) || extension.equals("gz");
	}
	
	public boolean isCompressed() {
		return compressed;
	}
	
	public Path getPath() {
		return pathToDump;
	}
	
	public Stream<MorfeuszRecord> stream() throws IOException {
		final InputStream is;
		
		if (compressed) {
			is = new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(pathToDump)));
		} else {
			is = new BufferedInputStream(Files.newInputStream(pathToDump));
		}
		
		// must create new parser instance on each stream run, otherwise returns nothing once stopped
		var parser = new TsvParser(settings);
		var iterable = parser.iterate(is, StandardCharsets.UTF_8);
		var iterator = new MorfeuszIterator(iterable.iterator());
		
		int characteristics = Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
				| Spliterator.NONNULL | Spliterator.IMMUTABLE;
		
		var spliterator = Spliterators.spliteratorUnknownSize(iterator, characteristics);
		var stream = StreamSupport.stream(spliterator, false);
		
		stream.onClose(() -> {
			try {
				is.close();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		
		return stream;
	}
	
	private static class MorfeuszIterator implements Iterator<MorfeuszRecord> {
		private ResultIterator<String[], ParsingContext> tsvIterator;
		
		public MorfeuszIterator(ResultIterator<String[], ParsingContext> tsvIterator) {
			this.tsvIterator = tsvIterator;
		}
		
		@Override
		public boolean hasNext() {
			return tsvIterator.hasNext();
		}
		
		@Override
		public MorfeuszRecord next() {
			var fields = tsvIterator.next();
			var record = MorfeuszRecord.fromArray(fields);
			return record;
		}
	}
	
	public static void main(String[] args) throws Exception {
		var path = Paths.get("./data/dumps/");
		var morfeuszLookup = new MorfeuszLookup(path.resolve("sgjp-20200607.tab.gz"));
		
		System.out.println(morfeuszLookup.getPath());
		System.out.println(morfeuszLookup.isCompressed());
		
		try (var stream = morfeuszLookup.stream()) {
			stream.filter(record -> record.getLemma().equals("kotek")).forEach(System.out::println);
		}
		
		try (var stream = morfeuszLookup.stream()) {
			System.out.println(stream.count());
		}
	}
}
