package com.github.wikibot.utils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class SJP extends OnlineDict<SJP> {
	protected boolean exists = false;
	protected boolean isDefined = false;
	
    public SJP(String entry) {
    	super(entry, "http://sjp.pwn.pl/szukaj/", "UTF-8");
    }
    
	@Override
	protected String escape(String text) throws UnsupportedEncodingException {
		return URLEncoder.encode(text, "ISO-8859-2");
	}
	
	@Override
	protected String stripContent(String text) {
		int a = text.indexOf("<span class=\"entry-head-title\">Słownik języka polskiego</span>");
		
		if (a == -1)
			return "Nie znaleziono żadnych wyników wyszukiwania";
		
		int b = text.indexOf("<div class=\"row col-wrapper\">", a);
		return text.substring(a, (b != -1) ? b : text.length());
	}
	
	@Override
    public SJP call() throws IOException {
		fetchEntry();
		return this;
	}
	
	public boolean exists() {
		return isSerial ? exists : !content.contains("Nie znaleziono żadnych wyników wyszukiwania");
	}
	
	public boolean isDefined() {
		return isSerial ? isDefined : content.matches(".*?<span class=\"tytul\"><a .*?class=\"anchor-title\".*?>" + entry + ".*?</a></span>.*");
	}
	
	public static void main(String[] args) {
		SJP sjp = new SJP("Montevideo");
		
		try {
			sjp.fetchEntry();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		System.out.println(sjp.getContent());
		System.out.println(sjp.exists());
		System.out.println(sjp.isDefined());
	}
}
