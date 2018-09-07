package com.github.wikibot.parsing.eswikt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.AbstractPage;
import com.github.wikibot.parsing.AbstractSection;
import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;

public final class Page extends AbstractPage<Section> {
	private List<LangSection> langSections;
	private Section references;
	private String trailingContent;
	public static final List<String> INTERWIKI_PREFIXES;
	private static final Pattern P_INTERWIKI;
	public static final Map<String, String> CODE_TO_LANG;
	
	static {
		try {
			INTERWIKI_PREFIXES = Files.readAllLines(Paths.get("./data/interwiki.txt"));
			List<String> excluded = new ArrayList<>(INTERWIKI_PREFIXES);
			// TODO: review per [[Especial:Diff/2709872]]
			//excluded.addAll(Arrays.asList("Category", "Categoría", "File", "Archivo"));
			String regex = "\n(?:\\[\\[(?:" + String.join("|", excluded) + "):[^\\]]+?\\]\\]\\s*)+$";
			P_INTERWIKI = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		try {
			CODE_TO_LANG = Files.lines(Paths.get("./data/eswikt.langs.txt"))
				.map(line -> line.split("\t"))
				.collect(Collectors.toMap(
					arr -> arr[0], // lower case!
					arr -> arr[1],
					(arr1, arr2) -> arr1,
					LinkedHashMap::new
				));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private Page(String title, String text) {
		super(title);
		
		this.langSections = new ArrayList<>();
		this.references = null;
		this.trailingContent = "";

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
		// TODO: move to parsing.Page
		String content = lastSection.getIntro();
		Matcher m = P_INTERWIKI.matcher("\n" + content);
		
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
		return Collections.unmodifiableList(new ArrayList<>(langSections));
	}

	public Optional<Section> getReferencesSection() {
		return Optional.ofNullable(references);
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
		
		List<Section> nonLangSections = new ArrayList<>();
		
		for (Section section : sections) {
			if (!(section instanceof LangSection) && !section.getLangSectionParent().isPresent()) {
				nonLangSections.add(section);
			}
		}
		
		Collections.sort(langSections);
		sections = new ArrayList<>(AbstractSection.flattenSubSections(langSections));
		
		if (!nonLangSections.isEmpty()) {
			appendSections(nonLangSections.toArray(new Section[nonLangSections.size()]));
		}
		
		buildSectionTree();
	}

	public Optional<LangSection> getLangSection(String langCode) {
		return langSections
			.stream()
			.filter(section -> section.langCodeEqualsTo(langCode))
			.findAny();
	}

	public LangSection addLangSection(String langCode) {
		Optional<LangSection> langSectionOpt = getLangSection(langCode);
		
		if (langSectionOpt.isPresent()) {
			return langSectionOpt.get();
		}
		
		LangSection langSection = LangSection.create(langCode);
		langSections.add(langSection);
		sortSections();
		
		return langSection;
	}

	public boolean addLangSection(LangSection langSection) {
		if (hasLangSection(langSection.getLangCode())) {
			return false;
		}
		
		langSections.add(langSection);
		sortSections();
		
		return true;
	}

	public boolean removeLangSection(String langCode) {
		Optional<LangSection> langSectionOpt = getLangSection(langCode);
		
		if (!langSectionOpt.isPresent()) {
			return false;
		}
		
		sections.remove(langSectionOpt.get());
		buildSectionTree();
		
		return true;
	}

	public boolean hasLangSection(String langCode) {
		return langSections
			.stream()
			.anyMatch(section -> section.langCodeEqualsTo(langCode));
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
	public static void main(String[] args) throws Exception {
		Wikibot wiki = Login.createSession(Domains.ESWIKT.getDomain());
		String text = wiki.getPageText("tamén");
		Page page = Page.store("tamén", text);
		System.out.println("");
	}
}
