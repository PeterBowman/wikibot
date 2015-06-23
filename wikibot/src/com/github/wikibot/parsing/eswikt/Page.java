package com.github.wikibot.parsing.eswikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.PageBase;
import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.SectionBase;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class Page extends PageBase<Section> {
	private List<LangSection> langSections;
	private Section references;
	private String trailingContent;
	public static final String[] INTERWIKI_PREFIXES;
	public static final Map<String, String> CODE_TO_LANG;
	
	static {
		try {
			INTERWIKI_PREFIXES = IOUtils.loadFromFile("./data/interwiki.txt", "", "UTF8");
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
		
		try {
			CODE_TO_LANG = Stream.of(IOUtils.loadFromFile("./data/eswikt.langs.txt", "", "UTF8"))
				.map(line -> line.split("\\s"))
				.collect(Collectors.toMap(
					arr -> arr[0].toUpperCase(),
					arr -> arr[1],
					(arr1, arr2) -> arr1,
					LinkedHashMap::new
				));
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
	
	private Page(String title, String text) {
		super(title);
		
		this.langSections = new ArrayList<LangSection>();
		this.references = null;
		this.trailingContent = "";

		if (text != null && !text.isEmpty()) {
			text = sanitizeWhiteSpaces(text);
			extractSections(text);
		}
	}
	
	public static Page wrap(PageContainer page) {
		return new Page(page.getTitle(), page.getText());
	}
	
	public static Page store(String title, String text) {
		return new Page(title, text);
	}
	
	public static Page create(String title, String langCode) {
		Page page = new Page(title, null);
		page.addLangSection(langCode);
		return page;
	}
	
	private void extractSections(String text) {
		super.extractSections(text, sectionText -> {
			try {
				return new LangSection(sectionText);
			} catch (ParsingException e) {
				return new Section(sectionText);
			}
		});
		
		if (!sections.isEmpty()) {
			Section lastSection = sections.get(sections.size() - 1);
			extractTrailingContent(lastSection);
		}
		
		rebuildSelfTree();
	}
	
	@Override
	protected void buildSectionTree() {
		super.buildSectionTree();
		rebuildSelfTree();
	}

	private void rebuildSelfTree() {
		if (sections.isEmpty()) {
			return;
		}
		
		langSections.clear();
		
		for (Section section : sections) {
			if (section instanceof LangSection) {
				langSections.add((LangSection) section);
			}
		}
		
		Section lastSection = sections.get(sections.size() - 1);
		
		if (lastSection.getHeader().matches("^Referencias.+?")) {
			references = lastSection;
		}
	}
	
	private void extractTrailingContent(Section lastSection) {
		String content = lastSection.getIntro();
		List<String> excluded = new ArrayList<String>(Arrays.asList(INTERWIKI_PREFIXES));
		excluded.addAll(Arrays.asList("Category", "Categoría", "File", "Archivo"));
		String regex = "\n(?:\\[\\[(?:" + String.join("|", excluded) + "):[^\\]]+?\\]\\]\\s*)+$";
		Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher("\n" + content);
		
		if (!m.find()) {
			return;
		}
		
		int index = m.start();
		trailingContent = m.group().substring(1);
		String trimmedIntro = content.substring(0, Math.max(index - 1, 0));
		int trailingNewlines = 0;
		
		if (!trimmedIntro.isEmpty()) {
			while (trimmedIntro.endsWith("\n")) {
				trailingNewlines++;
				trimmedIntro = trimmedIntro.substring(0, trimmedIntro.length() - 1);
			}
			
			lastSection.setIntro(trimmedIntro);
			lastSection.setTrailingNewlines(trailingNewlines);
		} else {
			trailingNewlines = lastSection.getLeadingNewlines();
			lastSection.setIntro("");
			lastSection.setTrailingNewlines(trailingNewlines);
		}
	}
	
	public List<LangSection> getAllLangSections() {
		return langSections;
	}

	public Section getReferencesSection() {
		return references;
	}
	
	public void setReferencesSection(Section references) {
		if (this.references != null) {
			sections.remove(this.references);
		}
		
		this.references = references;
		sections.add(references);
		buildSectionTree();
	}
	
	public String getTrailingContent() {
		return trailingContent;
	}
	
	public void setTrailingContent(String trailingContent) {
		this.trailingContent = trailingContent;
	}
	
	void sortSections() {
		if (langSections.isEmpty()) {
			return;
		}
		
		List<Section> nonLangSections = new ArrayList<Section>();
		
		for (Section section : sections) {
			if (!(section instanceof LangSection) && section.getLangSectionParent() == null) {
				nonLangSections.add(section);
			}
		}
		
		Collections.sort(langSections);
		sections = new ArrayList<Section>(SectionBase.flattenSubSections(langSections));
		
		if (!nonLangSections.isEmpty()) {
			appendSections(nonLangSections.toArray(new Section[nonLangSections.size()]));
		}
		
		buildSectionTree();
	}

	public LangSection getLangSection(String langCode) {
		LangSection out = langSections
			.stream()
			.filter(section -> section.getLangCode().equals(langCode.toUpperCase()))
			.findFirst()
			.orElse(null);
		
		return out;
	}

	public LangSection addLangSection(String langCode) {
		LangSection langSection = getLangSection(langCode);
		
		if (langSection != null) {
			return langSection;
		}
		
		langSection = LangSection.create(langCode);
		langSections.add(langSection);
		sortSections();
		
		return null;
	}

	public boolean addLangSection(LangSection langSection) {
		if (getLangSection(langSection.getLangCode()) != null) {
			return false;
		}
		
		langSections.add(langSection);
		sortSections();
		
		return false;
	}

	public boolean removeLangSection(String langCode) {
		LangSection langSection = getLangSection(langCode);
		
		if (langSection == null) {
			return false;
		}
		
		sections.remove(langSection);
		buildSectionTree();
		
		return false;
	}

	public boolean hasLangSection(String langCode) {
		return langSections
			.stream()
			.anyMatch(section -> section.getLangCode().equals(langCode.toUpperCase()));
	}
	
	@Override
	public String toString() {
		if (trailingContent.isEmpty()) {
			return super.toString();
		} else {
			StringBuilder sb = new StringBuilder(super.toString());
			sb.append("\n");
			sb.append(trailingContent);
			return sb.toString();
		}
	}
	
	@SuppressWarnings("unused")
	public static void main(String[] args) throws FailedLoginException, IOException {
		Wikibot wiki = Login.retrieveSession(Domains.ESWIKT, Users.User1);
		String text = wiki.getPageText("tamén");
		Page page = Page.store("tamén", text);
		System.out.println("");
	}
}
