package com.github.wikibot.parsing.plwikt;

import java.io.Serializable;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractPage;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class Page extends AbstractPage<Section> implements Serializable {
	private static final long serialVersionUID = 4112162751333437538L;
	private static final Pattern P_SECTION = Pattern.compile("^(?=(==.+?\\(\\{\\{.+?\\}\\}\\) *?==)\\s*$)", Pattern.MULTILINE);
	
	private Page(String title, String text) {
		super(title);
		
		if (text != null && !text.isEmpty()) {
			text = Utils.sanitizeWhitespaces(text);
			extractSections(text);
		}
	}
	
	public static Page wrap(PageContainer page) {
		return new Page(page.getTitle(), page.getText());
	}
	
	public static Page wrap(XMLRevision xml) {
		return new Page(xml.getTitle(), xml.getText());
	}
	
	public static Page store(String title, String text) {
		return new Page(title, text);
	}
	
	public static Page create(String title, String lang) {
		Page page = new Page(title, null);
		page.addSection(lang);
		return page;
	}
	
	protected final void extractSections(String text) {
		super.extractSections(text, Section::new, P_SECTION);
	}
	
	void sortSections() {
		Collections.sort(sections);
		buildSectionTree();
	}

	void updateToc() {
		intro = intro.replaceAll("\n?__TOC__", "");
		
		if (sections.size() == 2 || sections.size() == 3) {
			intro += "\n__TOC__";
		}
	}

	public Optional<Section> getSection(String lang) {
		return getSection(lang, false);
	}
	
	public Optional<Section> getSection(String lang, boolean useShortName) {
		return sections.stream()
			.filter(section -> (useShortName ? section.getLangShort() : section.getLang()).equals(lang))
			.findAny();
	}
	
	public Optional<Section> getPolishSection() {
		Optional<Section> out = getSection("język polski");
		
		if (!out.isPresent()) {
			out = getSection("termin obcy w języku polskim");
		}
		
		return out;
	}
	
	public Section addSection(String lang) {
		Optional<Section> sectionOpt = getSection(lang);
		
		if (sectionOpt.isPresent()) {
			return sectionOpt.get();
		}
		
		Section section = Section.create(lang, title);
		section.getField(FieldTypes.EXAMPLES).ifPresent(f -> f.editContent("(1.1)", true));
		sections.add(section);
		
		sortSections();
		updateToc();
		
		return section;
	}
	
	public boolean addSection(Section section) {
		if (getSection(section.getLang()) != null) {
			return false;
		}
		
		sections.add(section);
		sortSections();
		updateToc();
		
		return true;
	}

	public boolean removeSection(String lang) {
		Optional<Section> sectionOpt = getSection(lang);
		
		if (!sectionOpt.isPresent()) {
			return false;
		}
		
		sections.remove(sectionOpt.get());
		updateToc();
		buildSectionTree();
		
		return true;
	}
	
	public boolean hasSection(String lang) {
		return sections
			.stream()
			.anyMatch(section -> section.getLang().equals(lang));
	}

	@SuppressWarnings("unused")
	public static void main(String[] args) throws Exception {
		Wikibot wiki = Login.createSession("pl.wiktionary.org");
		String text = wiki.getPageText("rescate");
		Page page = Page.store("rescate", text);
		Section esp = page.getSection("język hiszpański").get();
		Field esp_ex = esp.getField(FieldTypes.EXAMPLES).get();
		DefinitionsField esp_def = (DefinitionsField) esp.getField(FieldTypes.DEFINITIONS).get();
		page.addSection("język polski");
		String text2 = wiki.getPageText("boks");
		Page page2 = Page.store("boks", text2);
		page2.sortSections();
		System.out.println(page);
	}
}
