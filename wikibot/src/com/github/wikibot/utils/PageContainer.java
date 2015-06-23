package com.github.wikibot.utils;

import java.io.Serializable;
import java.util.Calendar;

public class PageContainer implements Serializable {
	private static final long serialVersionUID = 2715796362852345824L;

	protected String title;
	protected String text;
	protected Calendar timestamp;
	
	public PageContainer(String title, String text, Calendar timestamp) {
		if (title == null || text == null) {
			throw new NullPointerException();
		}
		
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
	
	public Calendar getTimestamp() {
		return timestamp;
	}
	
	public String toString() {
		return String.format("%s = %s", title, text);
	}
}
