package com.github.wikibot.utils;

public enum Users {
	User1 ("Peter Bowman", "peterbowman", true, false),
	User2 ("PBbot", "pbbot", false, true);
	
	private String username;
	private String alias;
	private boolean isSysop;
	private boolean isBot;
	
	private Users(String username, String alias, boolean isSysop, boolean isBot) {
		this.username = username;
		this.alias = alias;
		this.isSysop = isSysop;
		this.isBot = isBot;
	}
	
	public String getUsername() {
		return username;
	}
	
	public String getAlias() {
		return alias;
	}
	
	public boolean isSysop() {
		return isSysop;
	}
	
	public boolean isBot() {
		return isBot;
	}
}
