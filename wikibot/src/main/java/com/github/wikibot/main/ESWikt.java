package com.github.wikibot.main;

public class ESWikt extends Wikibot {
	public static final int ANNEX_NAMESPACE = 100;
	public static final int ANNEX_TALK_NAMESPACE = 101;
	
	protected ESWikt() {
		super("es.wiktionary.org");
	}
	
	public static ESWikt createInstance() {
		ESWikt wb = new ESWikt();
		wb.initVars();
		return wb;
	}
}
