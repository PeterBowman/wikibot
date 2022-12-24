package com.github.wikibot.webapp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkType;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class NKJPGenerator extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private static final List<String> TEMPLATE_PARAMS = List.of(
        "autorzy", "tytuł_pub", "tytuł_mag", "tytuł_art", "data", "hash", "match_start", "match_end"
    );

    private static final String JSP_DISPATCH_TARGET = "/jsp/nkjp-generator.jsp";

    /**
     * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Access-Control-Allow-Origin", "https://pl.wiktionary.org");

        var address = request.getParameter("address");

        final ResponseWrapper responseWrapper;

        if (request.getParameter("gui") != null || address == null) {
            responseWrapper = new WebResponse(request, response);
        } else {
            responseWrapper = new StructuredResponse(response);
        }

        if (address == null) {
            responseWrapper.send();
            return;
        } else {
            // sanitize a bit
            address = "http://" + address.replace("&amp;", "&").replaceFirst("^https?://", "").replaceFirst("[#\\|].*$", "");
        }

        var resultMap = new TreeMap<String, String>(Comparator.comparingInt(TEMPLATE_PARAMS::indexOf));

        try {
            generateTemplateData(address, resultMap);
            responseWrapper.prepareOutput(resultMap);
        } catch (Exception e) {
            responseWrapper.handleException(e);
        }

        responseWrapper.send();
    }

    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    private static String parseAddressParameter(String address) {
        var linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
        var i = linkExtractor.extractLinks(address).iterator();

        if (!i.hasNext()) {
            throw new RuntimeException("nie wykryto żadnego linku we wskazanym adresie");
        }

        var linkSpan = i.next();
        return address.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
    }

    private static Map<String, String> validateUrl(String urlString) throws MalformedURLException {
        var url = new URL(urlString);
        var query = url.getQuery();

        var mandatoryParams = List.of("pid", "match_start", "match_end", "wynik");
        var params = new HashMap<String, String>();

        for (var paramWithValue : query.split("&")) {
            var tokens = paramWithValue.split("=");

            if (tokens.length != 2) {
                continue;
            }

            var param = tokens[0];
            var value = tokens[1];

            if (mandatoryParams.contains(param)) {
                var regex = param.equals("pid") ? "[0-9a-f]{32}" : "\\d+";

                if (!value.matches(regex)) {
                    throw new RuntimeException("nieprawidłowy format parametru \"" + param + "\" (" + value + ")");
                }
            }

            params.put(param, value);
        }

        params.keySet().retainAll(mandatoryParams);

        var remainder = new ArrayList<String>(mandatoryParams);
        remainder.removeAll(params.keySet());

        if (!remainder.isEmpty()) {
            throw new RuntimeException("adres musi zawierać parametry: " + String.join(", ", remainder));
        }

        params.put("hash", params.remove("pid"));
        params.remove("wynik");
        return params;
    }

    private static void extractNKJPData(Document doc, Map<String, String> resultMap) {
        for (var tr : doc.body().getElementsByTag("tr")) {
            if (tr.children().size() != 2) {
                continue;
            }

            var label = tr.children().first().text().strip();
            var value = tr.children().last().text().strip();

            if (value.isEmpty()) {
                continue;
            }

            switch (label) {
                case "Autorzy:":
                    value = value.replace('\u00A0', ' ').replaceFirst(",? *$", "");
                    resultMap.put("autorzy", value);
                    break;
                case "Źródło:":
                    resultMap.put("tytuł_pub", value);
                    break;
                case "Tytuł:":
                    resultMap.put("tytuł_art", value);
                    break;
                case "Data publikacji:":
                    resultMap.put("data", value);
                    break;
            }

            if (resultMap.containsKey("tytuł_pub") && resultMap.containsKey("tytuł_art")) {
                resultMap.put("tytuł_mag", resultMap.remove("tytuł_pub")); // journal/magazine
            }
        }
    }

    private static void generateTemplateData(String address, Map<String, String> resultMap) throws IOException {
        var urlString = parseAddressParameter(address);

        try {
            var params = validateUrl(urlString);
            resultMap.putAll(params);
        } catch (MalformedURLException e) {
            throw new RuntimeException("błąd parsera adresu URL: " + e.getMessage());
        }

        var connection = Jsoup.connect(urlString);
        var doc = connection.get();

        extractNKJPData(doc, resultMap);
    }

    private static String buildTemplate(Map<String, String> params) {
        var sb = new StringBuilder("{{NKJP|");

        for (var entry : params.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("|");
        }

        return sb.deleteCharAt(sb.length() - 1).append("}}").toString();
    }

    private abstract class ResponseWrapper {
        final HttpServletResponse response;
        String format;

        ResponseWrapper(HttpServletResponse response) {
            this.response = response;
            this.format = "";
        }

        @SuppressWarnings("unused")
        void setFormat(String format) {
            this.format = Objects.toString(format, "plain");
        }

        String getContentType() {
            return switch (format) {
                case "json" -> "application/json";
                case "jsonp" -> "text/javascript";
                case "plain" -> "text/plain";
                default -> throw new Error("Unsupported format: " + format);
            };
        }

        abstract void prepareOutput(Map<String, String> resultMap);

        List<String> buildExceptionBacktrace(Exception e) {
            var backTrace = new ArrayList<String>();

            var stackTraceElements = e.getStackTrace();
            var canonicalName = NKJPGenerator.class.getCanonicalName();

            for (var el : stackTraceElements) {
                if (!el.getClassName().equals(canonicalName)) {
                    break;
                }

                backTrace.add(el.toString());
            }

            return backTrace;
        }

        abstract void handleException(Exception e);

        abstract void send() throws ServletException, IOException;
    }

    private class WebResponse extends ResponseWrapper {
        final HttpServletRequest request;
        final RequestDispatcher dispatcher;

        WebResponse(HttpServletRequest request, HttpServletResponse response) {
            super(response);
            this.request = request;
            this.dispatcher = getServletContext().getRequestDispatcher(JSP_DISPATCH_TARGET);
            this.format = "plain";
        }

        @Override
        void setFormat(String format) {
            // unsupported, only "plain" option is available
        }

        @Override
        void prepareOutput(Map<String, String> resultMap) {
            var template = buildTemplate(resultMap);
            request.setAttribute("output", template);
            request.setAttribute("parameters", resultMap);
        }

        @Override
        void handleException(Exception e) {
            var backTrace = buildExceptionBacktrace(e);
            request.setAttribute("error", e.toString());
            request.setAttribute("backtrace", backTrace);
        }

        @Override
        void send() throws ServletException, IOException {
            dispatcher.forward(request, response);
        }
    }

    private class StructuredResponse extends ResponseWrapper {
        final JSONObject json;

        StructuredResponse(HttpServletResponse response) {
            super(response);
            this.json = new JSONObject();
            this.format = "json";
        }

        @Override
        void setFormat(String format) {
            if (!format.equals("json")) {
                json.put("warning", "only \"json\" format is available for structured response data");
            }
        }

        @Override
        void prepareOutput(Map<String, String> resultMap) {
            var template = buildTemplate(resultMap);
            json.put("output", template);

            // ensure that key order is preserved
            var array = new JSONArray();

            for (var entry : resultMap.entrySet()) {
                var object = new JSONObject();
                object.put("name", entry.getKey());
                object.put("value", entry.getValue());
                array.put(object);
            }

            json.put("parameters", array);
        }

        @Override
        void handleException(Exception e) {
            var backTrace = buildExceptionBacktrace(e);
            json.put("status", 500);
            json.put("error", e.toString());
            json.put("backtrace", backTrace.toArray(String[]::new));
        }

        @Override
        void send() throws ServletException, IOException {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            var contentType = getContentType();
            response.setHeader("Content-Type", contentType);

            // might have been previously set by exception handler
            if (!json.has("status")) {
                json.put("status", 200);
            }

            var output = json.toString();
            response.getWriter().append(output);
        }
    }
}
