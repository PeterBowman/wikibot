package com.github.wikibot.webapp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkSpan;
import org.nibor.autolink.LinkType;

/**
 * Servlet implementation class PrettyRefServlet
 */
public class NKJPGenerator extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final List<String> templateParams = Arrays.asList(
		"autorzy", "tytuł_pub", "tytuł_art", "data", "hash", "match_start", "match_end"
	);
	
	private static final String JSP_DISPATCH_TARGET = "/jsp/nkjp-generator.jsp";
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		request.setCharacterEncoding("UTF-8");
		response.setHeader("Access-Control-Allow-Origin", "https://pl.wiktionary.org");
		
		String address = request.getParameter("address");
		
		ResponseWrapper responseWrapper;
		
		if (request.getParameter("gui") != null || address == null) {
			responseWrapper = new WebResponse(request, response);
		} else {
			responseWrapper = new StructuredResponse(response);
		}
		
		if (address == null) {
			responseWrapper.send();
			return;
		} else {
			address = address.replace("&amp;", "&");
		}
		
		Map<String, String> resultMap = new TreeMap<>((s1, s2) -> Integer.compare(templateParams.indexOf(s1), templateParams.indexOf(s2)));
		
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
		LinkExtractor linkExtractor = LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();
		Iterator<LinkSpan> i = linkExtractor.extractLinks(address).iterator();
		
		if (!i.hasNext()) {
			throw new RuntimeException("nie wykryto żadnego linku we wskazanym adresie");
		}
		
		LinkSpan linkSpan = i.next();
		
		return address.substring(linkSpan.getBeginIndex(), linkSpan.getEndIndex());
	}
	
	private static Map<String, String> validateUrl(String urlString) throws MalformedURLException {
		URL url = new URL(urlString);
		String query = url.getQuery();
		
		List<String> mandatoryParams = Arrays.asList("pid", "match_start", "match_end", "wynik");
		Map<String, String> params = new HashMap<>();
		
		for (String paramWithValue : query.split("&")) {
			String[] tokens = paramWithValue.split("=");
			
			if (tokens.length != 2) {
				continue;
			}
			
			String param = tokens[0];
			String value = tokens[1];
			
			if (mandatoryParams.contains(param)) {
				String regex;
				
				if (param.equals("pid")) {
					regex = "[0-9a-f]{32}";
				} else {
					regex = "\\d+";
				}
				
				if (!value.matches(regex)) {
					throw new RuntimeException("nieprawidłowy format parametru \"" + param + "\" (" + value + ")");
				}
			}
			
			params.put(param, value);
		}
		
		params.keySet().retainAll(mandatoryParams);
		
		List<String> remainder = new ArrayList<>(mandatoryParams);
		remainder.removeAll(params.keySet());
		
		if (!remainder.isEmpty()) {
			throw new RuntimeException("adres musi zawierać parametry: " + String.join(", ", remainder));
		}
		
		params.put("hash", params.remove("pid"));
		params.remove("wynik");
		
		return params;
	}
	
	private static void extractNKJPData(Document doc, Map<String, String> resultMap) {
		List<String> mandatoryParams = Arrays.asList("autorzy", "tytuł_pub");
		
		for (Element tr : doc.body().getElementsByTag("tr")) {
			if (tr.children().size() != 2) {
				continue;
			}
			
			String label = tr.children().first().ownText().trim();
			String value = tr.children().last().ownText().trim();
			
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
		}
		
		for (String mandatoryParam : mandatoryParams) {
			if (!resultMap.containsKey(mandatoryParam)) {
				throw new RuntimeException("nie udało się pobrać obowiązkowego parametru \"" + mandatoryParam + "\"");
			}
		}
	}
	
	private static void generateTemplateData(String address, Map<String, String> resultMap) throws IOException {
		String urlString = parseAddressParameter(address);
		
		try {
			Map<String, String> params = validateUrl(urlString);
			resultMap.putAll(params);
		} catch (MalformedURLException e) {
			throw new RuntimeException("błąd parsera adresu URL: " + e.getMessage());
		}
		
		Connection connection = Jsoup.connect(urlString);
		Document doc = connection.get();
		
		extractNKJPData(doc, resultMap);
	}
	
	private static String buildTemplate(Map<String, String> params) {
		StringBuilder sb = new StringBuilder("{{NKJP|");
		
		for (Map.Entry<String, String> entry : params.entrySet()) {
			sb.append(entry.getKey()).append("=").append(entry.getValue()).append("|");
		}
		
		return sb.deleteCharAt(sb.length() - 1).append("}}").toString();
	}
	
	private abstract class ResponseWrapper {
		HttpServletResponse response;
		String format;
		
		ResponseWrapper(HttpServletResponse response) {
			this.response = response;
			this.format = "";
		}
		
		@SuppressWarnings("unused")
		void setFormat(String format) {
			if (format == null) {
				format = "plain";
			}
			
			this.format = format;
		}
		
		String getContentType() {
			switch (format) {
				case "json":
					return "application/json";
				case "jsonp":
					return "text/javascript";
				case "plain":
					return "text/plain";
				default:
					throw new Error("Unsupported format: " + format);
			}
		}
		
		abstract void prepareOutput(Map<String, String> resultMap);
		
		List<String> buildExceptionBacktrace(Exception e) {
			List<String> backTrace = new ArrayList<>();
			
			StackTraceElement[] stackTraceElements = e.getStackTrace();
			String canonicalName = NKJPGenerator.class.getCanonicalName();
			
			for (int i = 0; i < stackTraceElements.length; i++) {
				StackTraceElement el = stackTraceElements[i];
				
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
		HttpServletRequest request;
		RequestDispatcher dispatcher;
		
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
			String template = buildTemplate(resultMap);
			request.setAttribute("output", template);
			request.setAttribute("parameters", resultMap);
		}
		
		@Override
		void handleException(Exception e) {
			List<String> backTrace = buildExceptionBacktrace(e);
			request.setAttribute("error", e.toString());
			request.setAttribute("backtrace", backTrace);
		}
		
		@Override
		void send() throws ServletException, IOException {
			dispatcher.forward(request, response);
		}
	}
	
	private class StructuredResponse extends ResponseWrapper {
		JSONObject json;
		
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
			String template = buildTemplate(resultMap);
			json.put("output", template);
			
			// ensure that key order is preserved
			JSONArray array = new JSONArray();
			
			for (Map.Entry<String, String> entry : resultMap.entrySet()) {
				JSONObject object = new JSONObject();
				object.put("name", entry.getKey());
				object.put("value", entry.getValue());
				array.put(object);
			}
			
			json.put("parameters", array);
		}
		
		@Override
		void handleException(Exception e) {
			List<String> backTrace = buildExceptionBacktrace(e);
			json.put("status", 500);
			json.put("error", e.toString());
			json.put("backtrace", backTrace.toArray(new String[backTrace.size()]));
		}
		
		@Override
		void send() throws ServletException, IOException {
			response.setCharacterEncoding("UTF-8");
			
			String contentType = getContentType();
			response.setHeader("Content-Type", contentType);
			
			// might have been previously set by exception handler
			if (!json.has("status")) {
				json.put("status", 200);
			}
			
			String output = json.toString();
			response.getWriter().append(output);
		}
	}
}
