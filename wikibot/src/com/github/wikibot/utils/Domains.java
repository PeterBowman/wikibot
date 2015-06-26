package com.github.wikibot.utils;

import java.util.stream.Stream;

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
	
	public static Domains findDomain(String host) {
		return Stream.of(Domains.values())
			.filter(domain -> domain.getDomain().equals(host))
			.findFirst()
			.orElse(null);
	}
}
