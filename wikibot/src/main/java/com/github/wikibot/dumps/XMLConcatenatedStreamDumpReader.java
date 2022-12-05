package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

public class XMLConcatenatedStreamDumpReader extends AbstractXMLDumpReader {
    private final FileChannel dumpChannel;
    private final InputStream indexStream;
    private final Filterable filter;

    private List<Long> availableChunks;
    private long size;

    private XMLConcatenatedStreamDumpReader(FileChannel fch, InputStream index, Filterable filter) {
        dumpChannel = Objects.requireNonNull(fch);
        indexStream = Objects.requireNonNull(index);
        this.filter = Objects.requireNonNull(filter);
    }

    public static XMLConcatenatedStreamDumpReader ofTitles(FileChannel fch, InputStream index, Set<String> titles) {
        return new XMLConcatenatedStreamDumpReader(fch, index, new TitleFilter(titles));
    }

    public static XMLConcatenatedStreamDumpReader ofPageIds(FileChannel fch, InputStream index, Set<Long> pageIds) {
        return new XMLConcatenatedStreamDumpReader(fch, index, new PageIdFilter(pageIds));
    }

    @Override
    public Optional<Long> getSize() {
        maybeRetrieveOffsets();
        return Optional.of(size);
    }

    private void maybeRetrieveOffsets() {
        if (availableChunks != null) {
            return;
        }

        var filteredOffsets = new LinkedHashSet<Long>(100000);
        var dumpSize = 0L;
        var lastOffset = 0L;
        var accumulator = 0;
        var addCurrentOffsetSize = false;
        var offsetCount = 0;
        var pageCount = 0L;

        var bis = new BufferedInputStream(indexStream);
        InputStream is;

        try {
            is = new CompressorStreamFactory().createCompressorInputStream(bis);
        } catch (CompressorException e) {
            is = bis;
        }

        var reader = new BufferedReader(new InputStreamReader(is));
        var it = reader.lines().iterator();

        while (it.hasNext()) {
            var line = it.next();
            var firstSeparator = line.indexOf(':');
            var offset = 0L;

            try {
                offset = Long.parseLong(line.substring(0, firstSeparator));
            } catch (StringIndexOutOfBoundsException e) {
                throw new RuntimeException("Unexpected: " + line, e);
            }

            if (offset != lastOffset) {
                lastOffset = offset;
                addCurrentOffsetSize = false;
                accumulator = 0;
                offsetCount++;
            }

            if (filter.test(line.substring(firstSeparator + 1))) {
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

            pageCount++;
        }

        availableChunks = Collections.unmodifiableList(new ArrayList<>(filteredOffsets));

        System.out.printf("Multistream chunks retrieved: %d/%d (dump size: %d/%d)%n",
                          availableChunks.size(), offsetCount, dumpSize, pageCount);

        size = dumpSize;
    }

    @Override
    protected InputStream getInputStream() {
        maybeRetrieveOffsets();
        return new RootlessXMLInputStream(dumpChannel, availableChunks);
    }

    @Override
    public Stream<XMLRevision> getStAXReaderStream() {
        return super.getStAXReaderStream().filter(filter::filter);
    }

    private interface Filterable {
        boolean filter(XMLRevision rev);
        boolean test(String line);
    }

    private static class TitleFilter implements Filterable {
        private final Set<String> titles;

        TitleFilter(Set<String> titles) {
            this.titles = titles;
        }

        @Override
        public boolean filter(XMLRevision rev) {
            return titles.contains(rev.getTitle());
        }

        @Override
        public boolean test(String line) {
            var title = line.substring(line.indexOf(':') + 1);
            return titles.contains(title);
        }
    }

    private static class PageIdFilter implements Filterable {
        private final Set<Long> ids;

        PageIdFilter(Set<Long> ids) {
            this.ids = ids;
        }

        @Override
        public boolean filter(XMLRevision rev) {
            return ids.contains(rev.getPageid());
        }

        @Override
        public boolean test(String line) {
            var id = Long.parseLong(line.substring(0, line.indexOf(':')));
            return ids.contains(id);
        }
    }
}
