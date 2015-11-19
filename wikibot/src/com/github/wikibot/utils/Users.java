package com.github.wikibot.utils;

import java.util.stream.Stream;

public enum Users {
	USER1 (
			"Peter Bowman",
			"peterbowman",
			new Domains[] {Domains.PLWIKT, Domains.ESWIKT},
			new Domains[] {}
		),
	USER2 (
			"PBbot",
			"pbbot",
			new Domains[] {},
			new Domains[] {Domains.PLWIKT, Domains.ESWIKT}
		);
	
	private String username;
	private String alias;
	private Domains[] hasSysop;
	private Domains[] hasBot;
	
	private Users(String username, String alias, Domains[] hasSysop, Domains[] hasBot) {
		this.username = username;
		this.alias = alias;
		this.hasSysop = hasSysop;
		this.hasBot = hasBot;
	}
	
	public String getUsername() {
		return username;
	}
	
	public String getAlias() {
		return alias;
	}
	
	public Domains[] hasSysop() {
		return hasSysop;
	}
	
	public Domains[] hasBot() {
		return hasBot;
	}
	
	public static Users findUser(String username) {
		return Stream.of(Users.values())
			.filter(user -> user.getUsername().equals(username))
			.findFirst()
			.orElse(null);
	}
}
