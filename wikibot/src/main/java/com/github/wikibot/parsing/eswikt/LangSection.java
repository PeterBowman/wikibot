package com.github.wikibot.parsing.eswikt;

import java.text.Collator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.wikiutils.ParseUtils;

import com.github.wikibot.parsing.ParsingException;

public class LangSection extends Section {
	private String langCode;
	private String langName;
	private String templateType;
	private Map<String, String> templateParams;
	private static final Pattern P_HEADER = Pattern.compile("^\\{\\{ *?(?:lengua|translit) *?\\|.+?\\}\\}$");
	
	LangSection() {
		super(null);
		
		this.langCode = "";
		this.langName = "";
		this.templateType = "";
		this.templateParams = new LinkedHashMap<>();
	}
	
	LangSection(String text) {
		super(text);
		
		if (level != 2) {
			throw new ParsingException("Invalid section level: " + level);
		} else if (text.isEmpty()) {
			throw new ParsingException("Empty text parameter (Section.constructor)");
		}
		
		this.templateParams = new LinkedHashMap<>();
		
		extractHeader();
	}
	
	public static LangSection parse(String text) {
		Objects.requireNonNull(text);
		text = text.trim() + "\n";
		return new LangSection(text);
	}
	
	public static LangSection create(String langCode) {
		Objects.requireNonNull(langCode);
		
		LangSection section = new LangSection();
		
		section.setLevel(2);
		section.setHeader(String.format("{{lengua|%s}}", langCode.toUpperCase()));
		
		//section.addMissingFields();
		return section;
	}
	
	public String getLangCode(boolean ignoreCase) {
		if (ignoreCase) {
			return langCode.toLowerCase();
		} else {
			return langCode;
		}
	}
	
	public String getLangCode() {
		return getLangCode(true);
	}
	
	public boolean langCodeEqualsTo(String langCode) {
		return this.langCode.equalsIgnoreCase(langCode);
	}

	public void setLangCode(String langCode) {
		Objects.requireNonNull(langCode);
		
		if (langCode.isBlank()) {
			throw new UnsupportedOperationException("The passed argument cannot be blank.");
		}
		
		this.langCode = langCode.toLowerCase();
		this.langName = Page.CODE_TO_LANG.getOrDefault(this.langCode, "");
		updateHeader();
	}
	
	public String getLangName() {
		return langName;
	}

	public void setLangName(String langName) throws UnsupportedOperationException {
		Objects.requireNonNull(langName);
		
		if (langName.isBlank()) {
			throw new UnsupportedOperationException("The passed argument cannot be blank.");
		}
		
		String langCode = Page.CODE_TO_LANG.keySet().stream()
			.filter(code -> Page.CODE_TO_LANG.get(code).equals(langName))
			.findFirst()
			.orElse(null);
		
		if (langCode == null) {
			throw new UnsupportedOperationException("Unknown code for language name \"" + langName + "\"");
		}
		
		this.langCode = langCode;
		this.langName = langName;
		updateHeader();
	}
	
	public String getTemplateType() {
		return templateType;
	}
	
	public void setTemplateType(String templateType) {
		Objects.requireNonNull(templateType);
		
		if (templateType.isBlank()) {
			throw new UnsupportedOperationException("The passed argument cannot be blank.");
		}
		
		this.templateType = templateType;
		updateHeader();
	}
	
	public Map<String, String> getTemplateParams() {
		return new LinkedHashMap<>(templateParams);
	}
	
	public void setTemplateParams(Map<String, String> templateParams) {
		Objects.requireNonNull(templateParams);
		this.templateParams = templateParams;
		updateHeader();
	}
	
	@Override
	public void setHeader(String header) {
		Objects.requireNonNull(header);
		
		if (header.isBlank()) {
			throw new UnsupportedOperationException("The passed argument cannot be blank.");
		}
		
		this.header = header;
		extractHeader();
	}
	
	private void extractHeader() {
		if (!P_HEADER.matcher(header).matches()) {
			throw new ParsingException("Invalid header format: " + header);
		}
		
		HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(header);
		
		templateType = params.remove("templateName");
		langCode = params.remove("ParamWithoutName1");
		
		Objects.requireNonNull(langCode);
		
		langName = Page.CODE_TO_LANG.getOrDefault(langCode.toLowerCase(), "");
		templateParams = params;
	}
	
	private void updateHeader() {
		HashMap<String, String> params = new LinkedHashMap<>();
		params.put("templateName", templateType);
		params.put("ParamWithoutName1", langCode);
		
		templateParams.forEach(params::putIfAbsent);
		header = ParseUtils.templateFromMap(params);
	}
	
	void sortSections() {
		if (childSections.isEmpty()) {
			return;
		}
		
		List<Section> etymologySections = findSubSectionsWithHeader("^Etimología.*");
		
		if (etymologySections.size() < 2) {
			if (hasDuplicatedChildSections()) {
				return;
			}
			
			Collections.sort(childSections);
			propagateTree();
		} else {
			etymologySections.forEach(Section::sortSections);
		}
	}

	@Override
	public int compareTo(Section s) {
		LangSection ls = (LangSection) s;
		String targetTemplate = ls.getTemplateType();
		String targetLang = ls.getLangName();
		
		if (langName.equals(targetLang)) {
			return 0;
		}
		
		if (langName.equals("translingüístico")) {
			return -1;
		} else if (targetLang.equals("translingüístico")) {
			return 1;
		}
		
		if (langName.equals("español")) {
			return -1;
		} else if (targetLang.equals("español")) {
			return 1;
		}
		
		if (templateType.equals("lengua") && targetTemplate.equals("translit")) {
			return -1;
		} else if (templateType.equals("translit") && targetTemplate.equals("lengua")) {
			return 1;
		} else if (!langName.isEmpty() && !targetLang.isEmpty()) {
			Collator collator = Collator.getInstance(new Locale("es"));
			collator.setStrength(Collator.SECONDARY);
			return collator.compare(langName, targetLang);
		}
		
		return 0;
	}
}
