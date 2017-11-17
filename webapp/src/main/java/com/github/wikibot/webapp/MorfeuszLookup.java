package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import pl.sgjp.morfeusz.Morfeusz;
import pl.sgjp.morfeusz.MorfeuszUsage;
import pl.sgjp.morfeusz.MorphInterpretation;

/**
 * Servlet implementation class MorfeuszLookup
 */
public class MorfeuszLookup extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private static final List<String> ALLOWED_ORIGINS = Arrays.asList(
		"pl.wiktionary.org",
		"pl.wikipedia.org",
		"pl.wikiquote.org",
		"pl.wikisource.org",
		"pl.wikibooks.org",
		"pl.wikivoyage.org",
		"pl.wikinews.org"
	);
	
	private enum Action {
		analyze,
		generate
	};
	
	private Morfeusz morfeusz;
	private String versionStr;
	private String dictID;
    
    @Override
    public void init() throws ServletException {
    	try {
    		versionStr = Morfeusz.getVersion();
        	morfeusz = Morfeusz.createInstance(MorfeuszUsage.BOTH_ANALYSE_AND_GENERATE);
        	dictID = morfeusz.getDictID();
        } catch (Exception e) {
        	throw new UnavailableException(e.getMessage());
        }
    }
    
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String remoteHost = request.getRemoteHost();
		String actionStr = request.getParameter("action");
		String target = request.getParameter("target");
		
		if (ALLOWED_ORIGINS.contains(remoteHost)) {
			response.setHeader("Access-Control-Allow-Origin", String.format("https://%s", remoteHost));
		}
		
		response.setContentType("application/json; charset=UTF-8");
		response.setCharacterEncoding("UTF-8");
		
		PrintWriter writer = response.getWriter();
		JSONObject json = new JSONObject();
		
		Action action;
		
		try {
			action = Action.valueOf(actionStr);
		} catch (IllegalArgumentException e) {
			handleIllegalAction(writer, json, actionStr);
			return;
		}
		
		JSONArray results = new JSONArray();
		
		if (target == null) {
			writer.append(json.put("results", results).toString());
			return;
		}
		
		target = handleWhitespaces(target);
		
		synchronized (morfeusz) {
			List<MorphInterpretation> interpretationList = queryDatabase(action, target);
			fillResults(results, interpretationList);
		}
		
		json.put("results", results);
		
		addMetaData(json);
		writer.append(json.toString());
	}
	
	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	
	private static void handleIllegalAction(PrintWriter writer, JSONObject json, String act) {
		List<String> list = new ArrayList<>();
		
		for (Action action : Action.values()) {
			list.add(action.toString());
		}
		
		String err = String.format("unrecognized action: \"%s\"; available: %s", act, list);
		
		json.put("error", err);
		writer.append(json.toString());
	}
	
	private static String handleWhitespaces(String target) {
		return target.replaceAll("\\s", "");
	}
	
	private List<MorphInterpretation> queryDatabase(Action action, String target) {
		switch (action) {
		case analyze:
			return morfeusz.analyseAsList(target);
		case generate:
			return morfeusz.generate(target);
		default:
			return new ArrayList<>();
		}
	}
	
	private void fillResults(JSONArray results, List<MorphInterpretation> interpretationList) {
		for (MorphInterpretation interpretation : interpretationList) {
			JSONObject data = new JSONObject();
			
			data.put("startNode", interpretation.getStartNode());
			data.put("endNode", interpretation.getEndNode());
			data.put("isIgnored", interpretation.isIgn());
			data.put("form", interpretation.getOrth());
			data.put("lemma", interpretation.getLemma());
			data.put("tag", interpretation.getTag(morfeusz));
			data.put("name", interpretation.getName(morfeusz));
			
			for (String label : interpretation.getLabels(morfeusz)) {
				data.append("labels", label);
			}
			
			data.put("tagId", interpretation.getTagId());
			data.put("nameId", interpretation.getNameId());
			data.put("labelsId", interpretation.getLabelsId());
			
			results.put(data);
		}
	}
	
	private void addMetaData(JSONObject json) {
		JSONObject meta = new JSONObject();
		meta.put("version", versionStr);
		meta.put("dictionaryId", dictID);
		
		json.put("meta", meta);
	}
}
