package com.github.wikibot.main;

public class ESWikt extends Wikibot {
	protected ESWikt() {
		super("es.wiktionary.org");
	}
	
	public static ESWikt createInstance() {
		ESWikt wb = new ESWikt();
		wb.initVars();
		return wb;
	}
}
