package com.github.wikibot.utils;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public class PageContainer implements Serializable {
	private static final long serialVersionUID = -2537816315120752316L;
	
	protected String title;
	protected String text;
	protected OffsetDateTime timestamp;
	
	public PageContainer(String title, String text, OffsetDateTime timestamp) {
		Objects.requireNonNull(title);
		Objects.requireNonNull(text);
		
		this.title = title;
		this.text = text;
		this.timestamp = timestamp;
	}
	
	public String getTitle() {
		return title;
	}
	
	public String getText() {
		return text;
	}
	
	public OffsetDateTime getTimestamp() {
		return timestamp;
	}
	
	public String toString() {
		return String.format("%s = %s", title, text);
	}
}
