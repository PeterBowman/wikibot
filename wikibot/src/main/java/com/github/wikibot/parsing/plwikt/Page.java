package com.github.wikibot.parsing.plwikt;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.regex.Pattern;

import javax.security.auth.login.FailedLoginException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractPage;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

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

	public Section getSection(String lang) {
		return getSection(lang, false);
	}
	
	public Section getSection(String lang, boolean useShortName) {
		Section out = sections
			.stream()
			.filter(section -> (useShortName ? section.getLangShort() : section.getLang()).equals(lang))
			.findFirst()
			.orElse(null);
		
		return out;
	}
	
	public Section getPolishSection() {
		Section out = getSection("język polski");
		
		if (out == null) {
			out = getSection("termin obcy w języku polskim");
		}
		
		return out;
	}
	
	public Section addSection(String lang) {
		Section section = getSection(lang);
		
		if (section != null) {
			return section;
		}
		
		section = Section.create(lang, title);
		section.getField(FieldTypes.EXAMPLES).editContent("(1.1)", true);
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
		Section section = getSection(lang);
		
		if (section == null) {
			return false;
		}
		
		sections.remove(section);
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
	public static void main(String[] args) throws FailedLoginException, IOException {
		Wikibot wiki = Login.retrieveSession(Domains.PLWIKT, Users.USER1);
		String text = wiki.getPageText("rescate");
		Page page = Page.store("rescate", text);
		Section esp = page.getSection("język hiszpański");
		Field esp_ex = esp.getField(FieldTypes.EXAMPLES);
		DefinitionsField esp_def = esp.getField(FieldTypes.DEFINITIONS);
		page.addSection("język polski");
		String text2 = wiki.getPageText("boks");
		Page page2 = Page.store("boks", text2);
		page2.sortSections();
		System.out.println(page);
	}
}
