package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Objects;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

public class XMLDumpReader extends AbstractXMLDumpReader {
    private final InputStream is;

    public XMLDumpReader(InputStream is) {
        this.is = Objects.requireNonNull(is);
    }

    @Override
    protected InputStream getInputStream() {
        var bis = new BufferedInputStream(is);

        try {
            return new CompressorStreamFactory(true).createCompressorInputStream(bis);
        } catch (CompressorException e) {
            return bis; // assume uncompressed
        }
    }
}
