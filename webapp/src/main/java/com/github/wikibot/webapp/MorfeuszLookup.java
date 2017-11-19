package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import pl.sgjp.morfeusz.CaseHandling;
import pl.sgjp.morfeusz.Morfeusz;
import pl.sgjp.morfeusz.MorfeuszUsage;
import pl.sgjp.morfeusz.MorphInterpretation;
import pl.sgjp.morfeusz.TokenNumbering;
import pl.sgjp.morfeusz.WhitespaceHandling;

/**
 * Servlet implementation class MorfeuszLookup
 */
public class MorfeuszLookup extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private enum Action {
		analyze,
		generate
	};
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setContentType("application/json; charset=UTF-8");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Access-Control-Allow-Origin", "https://pl.wiktionary.org");
		
		String actionStr = request.getParameter("action");
		String target = request.getParameter("target");
		
		PrintWriter writer = response.getWriter();
		
		JSONObject json = new JSONObject();
		JSONArray results = new JSONArray();
		
		Action action;
		
		try {
			action = Action.valueOf(actionStr);
		} catch (IllegalArgumentException | NullPointerException e) {
			handleIllegalAction(writer, json, actionStr);
			return;
		}
		
		Morfeusz morfeusz = instantiateMorfeusz(action, request.getParameterMap(), json);
		addMetaData(morfeusz, json);
		
		try {
			target = handleTargetParameter(target, action);
		} catch (NullPointerException e) {
			writer.append(json.put("results", results).toString());
			return;
		} catch (UnsupportedOperationException e) {
			json.append("errors", "target value must not contain whitespaces");
			writer.append(json.toString());
			return;
		}
		
		List<MorphInterpretation> interpretationList = queryDatabase(morfeusz, action, target);
		
		fillResults(morfeusz, results, interpretationList);
		json.put("results", results);
		
		writer.append(json.toString());
	}
	
	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
	
	private Morfeusz instantiateMorfeusz(Action action, Map<String, String[]> params, JSONObject json)
		throws IllegalArgumentException
	{
		Morfeusz morfeusz;
		
		synchronized (this) {
			if (action == Action.analyze) {
				morfeusz = Morfeusz.createInstance(MorfeuszUsage.ANALYSE_ONLY);
			} else if (action == Action.generate) {
				morfeusz = Morfeusz.createInstance(MorfeuszUsage.GENERATE_ONLY);
			} else {
				morfeusz = Morfeusz.createInstance(MorfeuszUsage.BOTH_ANALYSE_AND_GENERATE);
			}
		}
		
		MorfeuszOptions options = new MorfeuszOptions();
		MorfeuszOptionParser optionParser = new MorfeuszOptionParser(morfeusz, options, action);
		
		optionParser.parse(params);
		optionParser.notifyWarnings(json);
		
		if (action == Action.analyze) {
			morfeusz.setTokenNumbering(options.tokenNumbering);
			morfeusz.setCaseHandling(options.caseHandling);
			morfeusz.setWhitespaceHandling(options.whitespaceHandling);
		}
		
		morfeusz.setAggl(options.aggl);
		morfeusz.setPraet(options.praet);
		
		return morfeusz;
	}
	
	private static void handleIllegalAction(PrintWriter writer, JSONObject json, String act) {
		List<String> list = new ArrayList<>();
		
		for (Action action : Action.values()) {
			list.add(action.toString());
		}
		
		String err = String.format("unrecognized action: \"%s\"; available: %s", act, list);
		writer.append(json.put("error", err).toString());
	}
	
	private static String handleTargetParameter(String target, Action action) {
		if (target == null) {
			throw new NullPointerException();
		}
		
		if (action == Action.generate) {
			target = target.trim();
			
			for (char c : target.toCharArray()) {
				// unhandled swig exceptions if target string contains spaces
				if (Character.isWhitespace(c)) {
					throw new UnsupportedOperationException();
				}
			}
		}
		
		return target;
	}
	
	private List<MorphInterpretation> queryDatabase(Morfeusz morfeusz, Action action, String target) {
		switch (action) {
		case analyze:
			return morfeusz.analyseAsList(target);
		case generate:
			return morfeusz.generate(target);
		default:
			return new ArrayList<>();
		}
	}
	
	private void fillResults(Morfeusz morfeusz, JSONArray results, List<MorphInterpretation> interpretationList) {
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
	
	private void addMetaData(Morfeusz morfeusz, JSONObject json) {
		JSONObject meta = new JSONObject();
		meta.put("version", Morfeusz.getVersion());
		meta.put("dictionaryId", morfeusz.getDictID());
		
		json.put("meta", meta);
	}
	
	private static class MorfeuszOptions {
		TokenNumbering tokenNumbering = TokenNumbering.SEPARATE_NUMBERING;
		CaseHandling caseHandling = CaseHandling.CONDITIONALLY_CASE_SENSITIVE;
		WhitespaceHandling whitespaceHandling = WhitespaceHandling.SKIP_WHITESPACES;
		
		String aggl = "strict";
		String praet = "split";
	}
	
	private static class MorfeuszOptionParser {
		private Morfeusz morfeusz;
		private MorfeuszOptions options;
		private Action action;
		private List<String> warnings;
		
		enum TokenNumberingValue {
			separate,
			continuous
		}
		
		enum CaseHandlingValue {
			conditional,
			strict,
			ignore
		}
		
		enum WhitespaceHandlingValue {
			skip,
			append,
			keep
		}
		
		public MorfeuszOptionParser(Morfeusz morfeusz, MorfeuszOptions options, Action action) {
			this.morfeusz = morfeusz;
			this.options = options;
			this.action = action;
			
			warnings = new ArrayList<>();
		}
		
		public void parse(Map<String, String[]> params) {
			if (params.containsKey("tokenHandling") && checkAnalyzerOptions("tokenHandling")) {
				parseTokenNumbering(params.get("tokenHandling")[0]);
			}
			
			if (params.containsKey("caseHandling") && checkAnalyzerOptions("caseHandling")) {
				parseCaseHandling(params.get("caseHandling")[0]);
			}
			
			if (params.containsKey("whitespaceHandling") && checkAnalyzerOptions("whitespaceHandling")) {
				parseWhitespaceHandling(params.get("whitespaceHandling")[0]);
			}
			
			if (params.containsKey("agglutinationRules")) {
				parseAggl(params.get("agglutinationRules")[0]);
			}
			
			if (params.containsKey("pastTenseSegmentation")) {
				parsePraet(params.get("pastTenseSegmentation")[0]);
			}
		}
		
		private boolean checkAnalyzerOptions(String param) {
			if (action == Action.generate) {
				String message = String.format("option \"%s\" not available in generator mode", param);
				warnings.add(message);
				return false;
			} else {
				return true;
			}
		}
		
		public void notifyWarnings(JSONObject json) {
			if (!warnings.isEmpty()) {
				JSONArray arr = new JSONArray(warnings);
				json.put("warnings", arr);
			}
		}
		
		private <E extends Enum<E>> E getEnumeration(Class<E> enumClass, final String param, final E defaultValue) {
			try {
				// related: https://stackoverflow.com/q/4014117
				return Enum.valueOf(enumClass, param);
			} catch (IllegalArgumentException e) {
				List<String> values = new ArrayList<>();
				
				for (E enumValue : enumClass.getEnumConstants()) {
					values.add(enumValue.toString());
				}
				
				String message = String.format("unsupported option \"%s\"; available: %s", param, values);
				warnings.add(message);
				return defaultValue;
			}
		}
		
		private void parseTokenNumbering(final String param) {
			switch (getEnumeration(TokenNumberingValue.class, param, TokenNumberingValue.separate)) {
			case separate:
				options.tokenNumbering = TokenNumbering.SEPARATE_NUMBERING;
				break;
			case continuous:
				options.tokenNumbering = TokenNumbering.CONTINUOUS_NUMBERING;
				break;
			}
		}
		
		private void parseCaseHandling(final String param) {
			switch (getEnumeration(CaseHandlingValue.class, param, CaseHandlingValue.conditional)) {
			case conditional:
				options.caseHandling = CaseHandling.CONDITIONALLY_CASE_SENSITIVE;
				break;
			case strict:
				options.caseHandling = CaseHandling.STRICTLY_CASE_SENSITIVE;
				break;
			case ignore:
				options.caseHandling = CaseHandling.IGNORE_CASE;
				break;
			}
		}
		
		private void parseWhitespaceHandling(final String param) {
			switch (getEnumeration(WhitespaceHandlingValue.class, param, WhitespaceHandlingValue.skip)) {
			case skip:
				options.whitespaceHandling = WhitespaceHandling.SKIP_WHITESPACES;
				break;
			case append:
				options.whitespaceHandling = WhitespaceHandling.APPEND_WHITESPACES;
				break;
			case keep:
				options.whitespaceHandling = WhitespaceHandling.KEEP_WHITESPACES;
				break;
			}
		}
		
		private void parseAggl(String param) {
			List<String> availableOpts = morfeusz.getAvailableAgglOptions();
			
			if (!availableOpts.contains(param)) {
				String message = String.format(
					"unrecognized agglutinationRules value \"%s\"; available: %s",
					param, availableOpts
				);
				
				warnings.add(message);
			} else {
				options.aggl = param;
			}
		}
		
		private void parsePraet(String param) {
			List<String> availableOpts = morfeusz.getAvailablePraetOptions();
			
			if (!availableOpts.contains(param)) {
				String message = String.format(
					"unrecognized pastTenseSegmentation value \"%s\"; available: %s",
					param, availableOpts
				);
				
				warnings.add(message);
			} else {
				options.praet  = param;
			}
		}
	}
}
