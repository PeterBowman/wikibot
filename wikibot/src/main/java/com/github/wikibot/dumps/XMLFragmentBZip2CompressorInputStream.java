package com.github.wikibot.dumps;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

public class XMLFragmentBZip2CompressorInputStream extends CompressorInputStream {
	private static final String ROOT_ELEMENT = "dummy_root";
	
	private BZip2CompressorInputStream bzip2;
	private ByteArrayInputStream startRoot;
	private ByteArrayInputStream endRoot;
	
	XMLFragmentBZip2CompressorInputStream(InputStream is) throws IOException {
		bzip2 = new BZip2CompressorInputStream(is);
		startRoot = new ByteArrayInputStream(String.format("<%s>", ROOT_ELEMENT).getBytes());
		endRoot = new ByteArrayInputStream(String.format("</%s>", ROOT_ELEMENT).getBytes());
	}
	
	@Override
	public int read() throws IOException {
		if (startRoot.available() != 0) {
			return startRoot.read();
		}
		
		int ch = bzip2.read();
		
		if (ch == -1) {
			return endRoot.read();
		}
		
		return ch;
	}
}
