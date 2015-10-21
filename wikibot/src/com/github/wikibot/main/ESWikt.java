package com.github.wikibot.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.xml.sax.SAXException;

import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.eswikt.Page;
import com.github.wikibot.utils.PageContainer;

public class ESWikt extends Wikibot {
	private static final long serialVersionUID = 2142284575098809683L;

	public static final int ANNEX_NAMESPACE = 100;
	public static final int ANNEX_TALK_NAMESPACE = 101;
	
	public ESWikt() {
		super("es.wiktionary.org");
	}
	
	public void readXmlDump(Consumer<PageContainer> cons) throws IOException, SAXException {
		readXmlDump("eswiktionary", cons);
	}
	
	public static void main(String[] args) throws IOException, SAXException {
		ESWikt wb = new ESWikt();
		List<String> list = Collections.synchronizedList(new ArrayList<>(500));
		
		wb.readXmlDump(page -> {
			Page p;
			
			try {
				p = Page.wrap(page);
			} catch (ParsingException e) {
				System.out.printf("ParserException: %s%n", page.getTitle());
				return;
			}
			
			if (p.hasSectionWithHeader("Proverbio")) {
				list.add(page.getTitle());
			}
		});
		
		System.out.printf("Total count: %d%n", list.size());
		//IOUtils.writeToFile(String.join("\n", list), "./data/eswikt.proverb-headers.txt");
	}
}
