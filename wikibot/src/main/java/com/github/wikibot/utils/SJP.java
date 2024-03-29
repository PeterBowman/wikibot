package com.github.wikibot.utils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import org.jsoup.Jsoup;

public class SJP extends OnlineDict<SJP> {
    protected boolean exists = false;
    protected boolean isDefined = false;

    public SJP(String entry) {
        super(entry, "http://sjp.pwn.pl/szukaj/", StandardCharsets.UTF_8.name());
    }

    @Override
    protected String escape(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    @Override
    protected String stripContent(String text) {
        if (text.indexOf("<span class=\"entry-head-title\">Słownik języka polskiego</span>") == -1) {
            return "Nie znaleziono żadnych wyników wyszukiwania";
        } else {
            return text;
        }
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
        if (isSerial) {
            return isDefined;
        } else {
            var doc = Jsoup.parseBodyFragment(content);

            return doc.getElementsByClass("sjp-wyniki").stream()
                .map(el -> el.getElementsByClass("anchor-title"))
                .flatMap(Collection::stream)
                .anyMatch(el -> el.text().equals(entry));
        }
    }

    public static void main(String[] args) {
        var sjp = new SJP("znak równości");

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
