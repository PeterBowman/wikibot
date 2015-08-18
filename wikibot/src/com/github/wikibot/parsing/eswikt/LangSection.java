package com.github.wikibot.parsing.eswikt;

import java.text.Collator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.wikibot.parsing.ParsingException;

public class LangSection extends Section {
	private String langCode;
	private String langName;
	private String templateType;
	private Map<String, String> templateParams;
	private static final Pattern P_HEADER = Pattern.compile("^\\{\\{(lengua|translit)\\|(.+?)(?:\\|(.+?))?\\}\\}$");
	
	LangSection() {
		super(null);
		
		this.langCode = "";
		this.langName = "";
		this.templateType = "";
		this.templateParams = new LinkedHashMap<String, String>();
	}
	
	LangSection(String text) {
		super(text);
		
		if (level != 2) {
			throw new ParsingException("Invalid section level: " + level);
		} else if (text.isEmpty()) {
			throw new ParsingException("Empty text parameter (Section.constructor)");
		}
		
		this.templateParams = new LinkedHashMap<String, String>();
		
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
	
	public String getLangCode() {
		return langCode;
	}
	
	public String getLangCode(boolean upperCase) {
		if (upperCase) {
			return getLangCode().toUpperCase();
		} else {
			return getLangCode().toLowerCase();
		}
	}
	
	public boolean langCodeEqualsTo(String langCode) {
		return getLangCode().equalsIgnoreCase(langCode);
	}

	public void setLangCode(String langCode) {
		this.langCode = langCode;
		this.langName = Page.CODE_TO_LANG.getOrDefault(getLangCode(true), "");
		updateHeader();
	}
	
	public String getLangName() {
		return langName;
	}

	public void setLangName(String langName) throws UnsupportedOperationException {
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
		this.templateType = templateType;
		updateHeader();
	}
	
	public Map<String, String> getTemplateParams() {
		return new LinkedHashMap<String, String>(templateParams);
	}
	
	public void setTemplateParams(Map<String, String> templateParams) {
		this.templateParams = templateParams;
		updateHeader();
	}
	
	@Override
	public void setHeader(String header) {
		this.header = header;
		extractHeader();
	}
	
	private void extractHeader() {
		Matcher m = P_HEADER.matcher(header);
		
		if (!m.matches()) {
			throw new ParsingException("Invalid header format: " + header);
		}
		
		templateType = m.group(1);
		langCode = m.group(2);
		langName = Page.CODE_TO_LANG.getOrDefault(langCode.toUpperCase(), "");
		String paramString = m.group(3);
		
		if (paramString != null) {
			String[] params = paramString.split("\\|");
			
			for (int i = 0; i < params.length; i++) {
				String param = params[i];
				
				if (param.indexOf("=") == -1) {
					templateParams.put(String.format("_param%d", i + 1), param.trim());
				} else {
					String[] splits = param.split("=");
					templateParams.put(splits[0].trim(), splits[splits.length - 1].trim());
				}
			}
		}
	}
	
	private void updateHeader() {
		String paramString = templateParams.keySet().stream()
			.map(key -> {
				if (!key.startsWith("_param")) {
					return String.format("%s=%s", key, templateParams.get(key));
				} else {
					return templateParams.get(key);
				}
			})
			.collect(Collectors.joining("|"));
		
		if (!paramString.isEmpty()) {
			header = String.format("{{%s|%s|%s}}", templateType, langCode, paramString);
		} else {
			header = String.format("{{%s|%s}}", templateType, langCode);
		}
	}
	
	void sortSections() {
		if (childSections == null) {
			return;
		}
		
		List<Section> etymologySections = findSubSectionsWithHeader("^Etimología.*");
		
		if (etymologySections.isEmpty()) {
			return;
		}
		
		if (etymologySections.size() == 1) {
			Collections.sort(childSections);
			propagateTree();
		} else {
			for (Section etymologySection : etymologySections.toArray(new Section[etymologySections.size()])) {
				etymologySection.sortSections();
			}
		}
	}

	@Override
	public int compareTo(Section s) {
		LangSection ls = (LangSection) s;
		String targetTemplate = ls.getTemplateType();
		String targetLang = ls.getLangName();
		
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
