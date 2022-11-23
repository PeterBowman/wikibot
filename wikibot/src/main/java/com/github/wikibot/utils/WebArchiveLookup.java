package com.github.wikibot.utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class WebArchiveLookup {
    private static final String BASE_QUERY_URL = "https://archive.org/wayback/available";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private Logger logger;
    private HttpClient client;

    public WebArchiveLookup() {
        logger = Logger.getLogger("web-archive");
        client = HttpClient.newHttpClient();
    }

    public Item queryUrl(URL url, OffsetDateTime timestamp) throws IOException, InterruptedException {
        return queryUrl(url.toString(), timestamp);
    }

    public Item queryUrl(String url, OffsetDateTime timestamp) throws IOException, InterruptedException {
        var query = makeQueryUrl(url, timestamp);
        logger.logp(Level.INFO, "WebArchiveLookup", "queryUrl", query);
        var request = HttpRequest.newBuilder(URI.create(query)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var tokener = new JSONTokener(response.body());
        var object = new JSONObject(tokener);
        return parseResponse(object);
    }

    private String makeQueryUrl(String url, OffsetDateTime timestamp) {
        if (timestamp != null) {
            return String.format("%s?url=%s&timestamp=%s", BASE_QUERY_URL, url, timestamp.format(DATE_FORMAT));
        } else {
            return String.format("%s?url=%s", BASE_QUERY_URL, url);
        }
    }

    private Item parseResponse(JSONObject object) throws MalformedURLException, JSONException {
        var requestUrl = new URL(object.getString("url"));
        var snapshots = object.getJSONObject("archived_snapshots");

        if (snapshots.isEmpty()) {
            return Item.makeInvalidItem(requestUrl);
        }

        var closest = snapshots.getJSONObject("closest");

        if (closest.getBoolean("available") == false || closest.getInt("status") != 200) {
            return Item.makeInvalidItem(requestUrl);
        }

        var archivedUrl = new URL(closest.getString("url"));
        var timestamp = LocalDateTime.parse(closest.getString("timestamp"), DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atOffset(ZoneOffset.UTC);

        return Item.makeValidItem(requestUrl, archivedUrl, timestamp);
    }

    public static class Item {
        private boolean available;
        private URL requestUrl;
        private URL archivedUrl;
        private OffsetDateTime timestamp;

        private Item() {}

        private static Item makeValidItem(URL requestUrl, URL archivedUrl, OffsetDateTime timestamp) {
            var item = new Item();
            item.available = true;
            item.requestUrl = requestUrl;
            item.archivedUrl = archivedUrl;
            item.timestamp = timestamp;
            return item;
        }

        private static Item makeInvalidItem(URL requestUrl) {
            var item = new Item();
            item.available = false;
            item.requestUrl = requestUrl;
            return item;
        }

        public boolean isAvailable() {
            return available;
        }

        public URL getRequestUrl() {
            return requestUrl;
        }

        public URL getArchiveUrl() {
            return archivedUrl;
        }

        public OffsetDateTime getTimestamp() {
            return timestamp;
        }

        @Override
        public int hashCode() {
            return Objects.hash(available, requestUrl, archivedUrl, timestamp);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof Item i) {
                return available == i.available && requestUrl.equals(i.requestUrl) &&
                    archivedUrl.equals(i.archivedUrl) && timestamp.equals(i.timestamp);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            if (available) {
                return String.format("[query=%s, available=true, timestamp=%s]", requestUrl, timestamp);
            } else {
                return String.format("[query=%s, available=false]", requestUrl);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var webArchive = new WebArchiveLookup();
        var item1 = webArchive.queryUrl("http://archiwum.wiz.pl/1996/96113000.asp", null);
        var item2 = webArchive.queryUrl("http://archiwum.wiz.pl/1996/96113000.asp", OffsetDateTime.of(2009, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        var item3 = webArchive.queryUrl("http://whatisthisidontknow.com", null);
        System.out.println(item1);
        System.out.println(item2);
        System.out.println(item3);
    }
}
