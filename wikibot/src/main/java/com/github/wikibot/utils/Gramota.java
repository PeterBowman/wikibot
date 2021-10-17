package com.github.wikibot.utils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;

public class Gramota extends OnlineDict<Gramota> {

	public Gramota(String entry) {
		super(entry, "http://gramota.ru/slovari/dic/?word=", "windows-1251");
	}

	@Override
	public Gramota call() throws Exception {
		fetchEntry();
		return this;
	}

	@Override
	protected String escape(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	@Override
	protected String stripContent(String text) {
		return Jsoup.parse(text).getElementsByClass("block-content").html();
	}

	public boolean exists() {
		return !content.contains("<h2>Искомое слово отсутствует</h2>") && !content.contains("искомое слово отсутствует");
	}

	public String withAccent() {
		for (var el : Jsoup.parse(content).getElementsByClass("accent")) {
			var parent = el.parent();
			var combined = parent.text().trim();

			if (!combined.equals(entry)) {
				continue;
			}

			el.text(el.text() + Character.toString((char)0x0301));
			return parent.text();
		}

		return null;
	}

	public static void main(String[] args) throws Exception {
		var gramota = new Gramota("милиция");
		gramota.fetchEntry();
		System.out.println(gramota.getContent());
		System.out.println(gramota.exists());
		System.out.println(gramota.withAccent());
	}

}
