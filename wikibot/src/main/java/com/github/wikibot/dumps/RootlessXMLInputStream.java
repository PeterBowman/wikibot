package com.github.wikibot.dumps;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.Iterator;
import java.util.Objects;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

public class RootlessXMLInputStream extends InputStream {
    private static final String ROOT_ELEMENT = "dummy_root";

    private final Iterator<Long> offsetsIter;
    private final FileChannel dumpChannel;
    private final CompressorStreamFactory factory;
    private final ByteArrayInputStream startRoot;
    private final ByteArrayInputStream endRoot;

    private InputStream currentStream;
    private String compressorName;

    RootlessXMLInputStream(FileChannel fch, Iterable<Long> offsets) {
        offsetsIter = offsets.iterator();
        dumpChannel = fch;
        factory = new CompressorStreamFactory(false);

        startRoot = new ByteArrayInputStream(String.format("<%s>", ROOT_ELEMENT).getBytes());
        endRoot = new ByteArrayInputStream(String.format("</%s>", ROOT_ELEMENT).getBytes());
    }

    private void prepareNextStream() throws IOException {
        while (offsetsIter.hasNext()) {
            var offset = offsetsIter.next();

            try {
                dumpChannel.position(offset);
                var bis = new BufferedInputStream(Channels.newInputStream(dumpChannel));

                if (compressorName == null) {
                    compressorName = CompressorStreamFactory.detect(bis);
                }

                currentStream = factory.createCompressorInputStream(compressorName, bis);
                return;
            }  catch (CompressorException e) {
                throw e;
            } catch (IOException e) {
                System.out.printf("Unable to read file at channel position %d: %s%n", offset, e.getMessage());
            }
        }

        currentStream = null;
    }

    @Override
    public int available() throws IOException {
        if (startRoot.available() != 0) {
            return startRoot.available();
        } else if (currentStream != null) {
            return currentStream.available();
        } else {
            return endRoot.available();
        }
    }

    @Override
    public int read() throws IOException {
        if (startRoot.available() != 0) {
            return startRoot.read();
        }

        if (currentStream == null) {
            prepareNextStream();
        }

        while (currentStream != null) {
            var c = currentStream.read();

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
        if (currentStream == null) {
            return super.read(b, off, len);
        }

        Objects.requireNonNull(b);

        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        int read = 0;

        while (currentStream != null) {
            int n = currentStream.read(b, off, len);

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
        if (currentStream != null) {
            currentStream.close();
        }
    }
}
