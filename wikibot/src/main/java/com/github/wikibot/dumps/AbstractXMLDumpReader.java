package com.github.wikibot.dumps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

abstract class AbstractXMLDumpReader {
    public Optional<Long> getSize() {
        return Optional.empty();
    }

    protected abstract InputStream getInputStream();

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
        } catch (SAXException e) {
            throw new IOException(e);
        }
    }

    public final void runSAXReader(Consumer<XMLRevision> cons) throws IOException {
        Objects.requireNonNull(cons);
        var sph = new SAXPageHandler(cons);
        runSAXReaderTemplate(sph);
    }

    public final void runParallelSAXReader(Consumer<XMLRevision> cons) throws IOException {
        Objects.requireNonNull(cons);
        var sph = new SAXConcurrentPageHandler(cons);
        runSAXReaderTemplate(sph);
    }

    public Stream<XMLRevision> getStAXReaderStream() {
        var input = getInputStream();

        try {
            var factory = XMLInputFactory.newInstance();
            var streamReader = factory.createXMLStreamReader(input);
            var dumpSize = getSize().orElse(0L);
            var staxReader = new StAXDumpReader(streamReader, dumpSize);

            return StreamSupport.stream(staxReader.spliterator(), false).onClose(() -> {
                try {
                    streamReader.close();
                } catch (XMLStreamException e) {
                    throw new UncheckedIOException(new IOException(e));
                }
            });
        } catch (XMLStreamException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
