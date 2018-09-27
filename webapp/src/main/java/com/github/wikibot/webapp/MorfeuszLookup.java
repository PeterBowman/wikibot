package com.github.wikibot.webapp;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
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
		
		morfeusz.setAggl(options.agglutinationRules);
		morfeusz.setPraet(options.pastTenseSegmentation);
		
		return morfeusz;
	}
	
	private static void handleIllegalAction(PrintWriter writer, JSONObject json, String act) {
		List<String> list = Stream.of(Action.values()).map(Object::toString).collect(Collectors.toList());
		String err = String.format("unrecognized action: '%s'; available: %s", act, list);
		writer.append(json.put("error", err).toString());
	}
	
	private static String handleTargetParameter(String target, Action action) {
		Objects.requireNonNull(target);
		
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
		
		String agglutinationRules = "strict";
		String pastTenseSegmentation = "split";
	}
	
	private static class MorfeuszOptionParser {
		private Morfeusz morfeusz;
		private MorfeuszOptions options;
		private Action action;
		private List<String> warnings;
		
		enum TokenNumberingValue {
			separate(TokenNumbering.SEPARATE_NUMBERING),
			continuous(TokenNumbering.CONTINUOUS_NUMBERING);
			
			private TokenNumbering morfeuszType;
			
			private TokenNumberingValue(TokenNumbering tokenNumbering) {
				morfeuszType = tokenNumbering;
			}
		}
		
		enum CaseHandlingValue {
			conditional(CaseHandling.CONDITIONALLY_CASE_SENSITIVE),
			strict(CaseHandling.STRICTLY_CASE_SENSITIVE),
			ignore(CaseHandling.IGNORE_CASE);
			
			private CaseHandling morfeuszType;
			
			private CaseHandlingValue(CaseHandling caseHandling) {
				morfeuszType = caseHandling;
			}
		}
		
		enum WhitespaceHandlingValue {
			skip(WhitespaceHandling.SKIP_WHITESPACES),
			append(WhitespaceHandling.APPEND_WHITESPACES),
			keep(WhitespaceHandling.KEEP_WHITESPACES);
			
			private WhitespaceHandling morfeuszType;
			
			private WhitespaceHandlingValue(WhitespaceHandling whitespaceHandling) {
				morfeuszType = whitespaceHandling;
			}
		}
		
		public MorfeuszOptionParser(Morfeusz morfeusz, MorfeuszOptions options, Action action) {
			this.morfeusz = morfeusz;
			this.options = options;
			this.action = action;
			
			warnings = new ArrayList<>();
		}
		
		public void parse(Map<String, String[]> params) {
			if (params.containsKey("tokenNumbering") && checkAnalyzerOptions("tokenNumbering")) {
				parseEnumParam(TokenNumberingValue.class, params.get("tokenNumbering")[0], value -> {
						options.tokenNumbering = value.morfeuszType;
					});
			}
			
			if (params.containsKey("caseHandling") && checkAnalyzerOptions("caseHandling")) {
				parseEnumParam(CaseHandlingValue.class, params.get("caseHandling")[0], value -> {
						options.caseHandling = value.morfeuszType;
					});
			}
			
			if (params.containsKey("whitespaceHandling") && checkAnalyzerOptions("whitespaceHandling")) {
				parseEnumParam(WhitespaceHandlingValue.class, params.get("whitespaceHandling")[0], value -> {
						options.whitespaceHandling = value.morfeuszType;
					});
			}
			
			if (params.containsKey("agglutinationRules")) {
				parseStringParam(params.get("agglutinationRules")[0], morfeusz.getAvailableAgglOptions(), value -> {
						options.agglutinationRules = value;
					});
			}
			
			if (params.containsKey("pastTenseSegmentation")) {
				parseStringParam(params.get("pastTenseSegmentation")[0], morfeusz.getAvailablePraetOptions(), value -> {
						options.pastTenseSegmentation = value;
					});
			}
		}
		
		private boolean checkAnalyzerOptions(String param) {
			if (action == Action.generate) {
				String message = String.format("option '%s' not available in generator mode", param);
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
		
		private <E extends Enum<E>> void parseEnumParam(Class<E> enumClass, String param, Consumer<E> consumer) {
			try {
				// related: https://stackoverflow.com/q/4014117
				E enumValue = Enum.valueOf(enumClass, param);
				consumer.accept(enumValue);
			} catch (IllegalArgumentException | NullPointerException e) {
				List<String> values = Stream.of(enumClass.getEnumConstants()).map(Enum<E>::toString).collect(Collectors.toList());
				String message = String.format("unsupported option '%s'; available: %s", param, values);
				warnings.add(message);
			}
		}
		
		private void parseStringParam(String param, List<String> availableOpts, Consumer<String> consumer) {
			if (!availableOpts.contains(param)) {
				String message = String.format("unrecognized value '%s'; available: %s", param, availableOpts);
				warnings.add(message);
			} else {
				consumer.accept(param);
			}
		}
	}
}
