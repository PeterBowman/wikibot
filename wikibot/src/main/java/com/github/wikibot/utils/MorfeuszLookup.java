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
import java.util.List;
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
    private InputStream urlStream;
    private boolean compressed;

    final private TsvParserSettings settings;

    {
        settings = new TsvParserSettings();
        // had some issues with copyright notice (uses Windows CRLF, main content is Unix LF)
        settings.setNumberOfRowsToSkip(29);
        settings.setLineSeparatorDetectionEnabled(false);
        settings.setHeaderExtractionEnabled(false);
        settings.getFormat().setLineSeparator("\n");
        // process leaves dangling thread on exit if true (default on multi-core machines)
        settings.setReadInputOnSeparateThread(false);
    }

    public static MorfeuszLookup fromPath(Path path) throws IOException {
        return new MorfeuszLookup(path, checkExtension(path));
    }

    public static Stream<Record> fromInputStream(InputStream stream) throws IOException {
        return new MorfeuszLookup(stream).stream();
    }

    private MorfeuszLookup(Path path, boolean useCompression) {
        pathToDump = Objects.requireNonNull(path);
        compressed = useCompression;
    }

    private MorfeuszLookup(InputStream is) {
        urlStream = is;
        compressed = true;
    }

    private static boolean checkExtension(Path path) throws IOException {
        var contentType = Files.probeContentType(path);
        // there might be no FileTypeDetector installed
        var extension = FilenameUtils.getExtension(path.toString());
        return "application/x-gzip".equals(contentType) || extension.equals("gz");
    }

    public Stream<Record> stream() throws IOException {
        var source = urlStream != null ? urlStream : Files.newInputStream(pathToDump);
        final InputStream is;

        if (compressed) {
            is = new GzipCompressorInputStream(new BufferedInputStream(source));
        } else {
            is = new BufferedInputStream(source);
        }

        // must create a new parser instance on each stream run, otherwise returns nothing once stopped
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

    private static class MorfeuszIterator implements Iterator<Record> {
        private ResultIterator<String[], ParsingContext> tsvIterator;

        public MorfeuszIterator(ResultIterator<String[], ParsingContext> tsvIterator) {
            this.tsvIterator = tsvIterator;
        }

        @Override
        public boolean hasNext() {
            return tsvIterator.hasNext();
        }

        @Override
        public Record next() {
            var fields = tsvIterator.next();
            var record = Record.fromArray(fields);
            return record;
        }
    }

    public record Record(String form, String lemma, String tags, String name, String labels) {
        static Record fromArray(String[] fields) {
            if (fields.length != 5) {
                throw new IllegalArgumentException("expected 5 values, got " + fields.length);
            }

            return new Record(fields[0], fields[1], fields[2], fields[3], fields[4]);
        }

        public List<String> tagsAsList() {
            return List.of(tags.split(","));
        }

        public List<String> labelsAsList() {
            return List.of(labels.split("|"));
        }
    }

    public static void main(String[] args) throws Exception {
        try (var fileStream = Files.list(Paths.get("./data/dumps/"))) {
            var dumpPath = fileStream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches("^sgjp-\\d{8}\\.tab\\.gz$"))
                .findFirst()
                .orElseThrow();

            var morfeuszLookup = MorfeuszLookup.fromPath(dumpPath);

            try (var stream = morfeuszLookup.stream()) {
                stream.filter(record -> record.lemma().equals("kotek")).forEach(System.out::println);
            }

            try (var stream = morfeuszLookup.stream()) {
                System.out.println(stream.count());
            }
        }
    }
}
