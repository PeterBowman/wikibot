package com.github.wikibot.main;

public class PLWikt extends Wikibot {
	protected PLWikt() {
    	super("pl.wiktionary.org");
    }
	
	public static PLWikt createInstance() {
		PLWikt wb = new PLWikt();
		wb.initVars();
		return wb;
	}
}
