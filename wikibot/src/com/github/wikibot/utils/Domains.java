package com.github.wikibot.utils;

public enum Domains {
	PLWIKT ("pl.wiktionary.org"),
	ESWIKT ("es.wiktionary.org"),
	PLQUOTE ("pl.wikiquote.org");
	
	private String domain;
	
	private Domains(String domain) {
		this.domain = domain;
	}
	
	public String getDomain() {
		return domain;
	}
}
