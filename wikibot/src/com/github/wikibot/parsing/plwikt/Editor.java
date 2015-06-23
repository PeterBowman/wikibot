package com.github.wikibot.parsing.plwikt;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.wikibot.parsing.EditorBase;
import com.github.wikibot.utils.PageContainer;

public class Editor extends EditorBase {
	private static final Pattern pLinkToTemplateZw = Pattern.compile("(\\(?\\[\\[(związek zgody|związek rządu)\\]\\]\\)?).+?\\{\\{odmiana-");
	private static final Pattern pNotesToReferencesExternal = Pattern.compile(" \\((\\[[^\\[\\]]+\\])\\)", Pattern.MULTILINE);
	private static final Pattern pNotesToReferencesInterwiki = Pattern.compile(" \\((\\[[^\\[\\]]+\\])\\)", Pattern.MULTILINE);
	
	public Editor(Page page) {
		super(page.getTitle(), page.toString());
	}
	
	public Editor(PageContainer pc) {
		super(pc.getTitle(), pc.getText());
	}
	
	@Override
	public void check() {
		weakWhitespaces();
		strongWhitespaces();
		linkToTemplate();
		sortSections();
		updateToc();
	}
	
	public void weakWhitespaces() {
		String original = text;
		Page page = Page.store(title, original);
		
		for (Section s : page.getAllSections()) {
			for (Field f : s.getAllFields()) {
				f.setEolMark('\n');
			}
			// TODO: intro
		}
		
		String formatted = page.toString();
		
		formatted = formatted.replaceAll("<references */ *>", "<references />");
		formatted = formatted.replace("\n:(", "\n: (");
		formatted = formatted.replaceAll("\n:([^ ])", "\n: $1");
		formatted = formatted.replaceAll("\n\\*([^ ])", "\n* $1");
		
		checkDifferences(original, formatted, "weakWhitespaces", null);
	}
	
	public void strongWhitespaces() {
		String original = text;
		Page page = Page.store(title, original);
		
		for (Section s : page.getAllSections()) {
			for (Field f : s.getAllFields()) {
				String content = f.getContent();
				// TODO: inside templates? merge with previous line?
				//content = content.replaceAll("\n +", "\n");
				content = content.replaceAll("\n{2,}", "\n");
				f.editContent(content);
			}
			
			// TODO: translations
			/*Field translations = s.getField(FieldTypes.TRANSLATIONS);
			
			if (translations != null) {
				String translationsText = translations.getContent();
				translationsText = translationsText.replace("", "");
				translations.editContent(translationsText);
			}*/
			
			s.setLeadingNewlines(0);
			s.setTrailingNewlines(0);
		}
		
		String intro = page.getIntro();
		intro = intro.replaceAll("\n{2,}", "\n");
		page.setIntro(intro);
		page.setLeadingNewlines(0);
		page.setTrailingNewlines(0);
		
		String formatted = page.toString();
		
		checkDifferences(original, formatted, "strongWhitespaces", "formatowanie odstępów");
	}
	
	public void linkToTemplate() {
		String original = text;
		Page page = Page.store(title, text);
		
		if (
			!original.contains("[[związek zgody]]") &&
			!original.contains("[[związek rządu]]")
		) {
			return;
		}
		
		Set<String> log = new HashSet<String>();
		
		for (Section s : page.getAllSections()) {
			Field inflection = s.getField(FieldTypes.INFLECTION);
			
			if (inflection.isEmpty()) {
				continue;
			}
			
			String inflectionText = inflection.getContent();
			Matcher m = pLinkToTemplateZw.matcher(inflectionText);
			StringBuffer sb = new StringBuffer();
			
			while (m.find()) {
				String match = m.group(1);
				String inner = m.group(2);
				String replacement;
				
				if (inner.equals("związek zgody")) {
					replacement = "{{zw zg}}";
					log.add("[[związek zgody]] → {{zw zg}}");
				} else {
					replacement = "{{zw rz}}";
					log.add("[[związek rządu]] → {{zw rz}}");
				}
				
				if (match.endsWith(")")) {
					replacement += ",";
				}
				
				m.appendReplacement(sb, replacement);
			}
			
			m.appendTail(sb);
			
			String modified = sb.toString();
			
			if (!modified.equals(inflection)) {
				inflection.editContent(modified);
			}
		}
		
		if (log.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		
		checkDifferences(original, formatted, "linkToTemplate", String.join(", ", log));
	}
	
	public void sortSections() {
		String original = text;
		Page page = Page.store(title, original);
		page.sortSections();
		String formatted = page.toString();
		checkDifferences(original, formatted, "sortSections", "sortowanie sekcji");
	}
	
	public void updateToc() {
		String original = text;
		Page page = Page.store(title, original);
		boolean hadToc = page.getIntro().contains("__TOC__");
		page.updateToc();
		boolean hasToc = page.getIntro().contains("__TOC__");
		
		if (hadToc == hasToc) {
			return;
		}
		
		String log = hasToc ? "dodanie __TOC__" : "usunięcie __TOC__";
		String formatted = page.toString();
		
		checkDifferences(original, formatted, "updateToc", log);
	}
	
	public void notesToReferences() {
		String original = text;
		Page page = Page.store(title, original);
		
		for (Section s : page.getAllSections()) {
			Field examples = s.getField(FieldTypes.EXAMPLES);
			
			if (examples == null) {
				continue;
			}
			
			String examplesText = examples.getContent();
			
			if (examplesText.contains("<ref")) {
				continue;
			}
			
			Matcher mExternal = pNotesToReferencesExternal.matcher(examplesText);
			examplesText = mExternal.replaceAll("<ref>$1</ref>");
			Matcher mInterwiki = pNotesToReferencesInterwiki.matcher(examplesText);
			examplesText = mInterwiki.replaceAll("<ref>$1</ref>");
			
			if (!examplesText.equals(examples.getContent())) {
				Field sources = s.getField(FieldTypes.SOURCES);
				
				if (sources.isEmpty()) {
					sources.editContent("<references />", true);
				} else {
					String sourcesText = sources.getContent();
					sourcesText += "\n" + "<references />";
					sources.editContent(sourcesText);
				}
			}
		}
		
		String formatted = page.toString();
		
		checkDifferences(original, formatted, "notesToReferences", "przeniesienie dopisków w przypisach");
	}
	
	private void checkDifferences(String original, String formatted, String caller, String log) {
		if (!formatted.equals(original)) {
			logger.add(caller);
			text = formatted;
			
			if (log != null) {
				summ.add(log);
				notifyModifications = true;
			}
		}
	}
}
