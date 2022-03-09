package com.github.wikibot.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

public abstract class OnlineDict<T> implements Callable<T> {
    protected String entry;
    protected String content;

    protected final String URL;
    private final String ENCODING;

    public boolean isSerial = false;

    // time to open a connection
    private final int CONNECTION_CONNECT_TIMEOUT_MSEC = 30000; // 30 seconds
    // time for the read to take place. (needs to be longer, some connections are slow
    // and the data volume is large!)
    private final int CONNECTION_READ_TIMEOUT_MSEC = 180000; // 180 seconds

    protected abstract String escape(String text);
    protected abstract String stripContent(String text);

    public OnlineDict(String entry, String url, String encoding) {
        this.entry = entry;
        this.URL = url;
        this.ENCODING = encoding;
    }

    public String getEntry() {
        return entry;
    }

    public void setEntry(String entry) throws IOException {
        this.entry = entry;
        content = getHTML(entry);
    }

    public void fetchEntry() throws IOException {
        content = getHTML(entry);
    }

    public void fetchEntry(String entry) throws IOException {
        this.entry = entry;
        content = getHTML(entry);
    }

    public String getContent() {
        return content;
    }

    public String getLink() {
        return URL + entry;
    }

    protected String fetch(String url) throws IOException
    {
        var connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(CONNECTION_CONNECT_TIMEOUT_MSEC);
        connection.setReadTimeout(CONNECTION_READ_TIMEOUT_MSEC);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.connect();

        // get the text
        var in = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charset.forName(ENCODING)));

        String line;
        var text = new StringBuilder(50000);

        while ((line = in.readLine()) != null) {
            text.append(line).append("\n");
        }

        in.close();
        var temp = text.toString();

        return temp;
    }

    protected String getHTML(String page) throws IOException {
        var target = URL + escape(page);
        System.out.println(target);

        var text = fetch(target);
        text = text.replace("\n", "");
        int a = text.indexOf("<body");
        int b = text.indexOf("</body>", a) + 7;

        return stripContent(text.substring(a, b));
    }
}
