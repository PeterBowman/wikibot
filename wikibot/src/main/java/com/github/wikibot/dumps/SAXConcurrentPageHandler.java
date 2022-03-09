package com.github.wikibot.dumps;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.xml.sax.SAXException;

public final class SAXConcurrentPageHandler extends SAXPageHandler {
	private ExecutorService executor;
	
	public SAXConcurrentPageHandler(Consumer<XMLRevision> cons) {
		super(cons);
	}
	
	public void startDocument() throws SAXException {
		executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	}
	
	public void endDocument() throws SAXException {
		executor.shutdown();
	}
	
	@Override
	protected void processRevision() {
		XMLRevision rev = revision;
		executor.execute(() -> cons.accept(rev));
	}
}
