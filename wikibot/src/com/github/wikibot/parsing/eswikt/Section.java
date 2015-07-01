package com.github.wikibot.parsing.eswikt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.SectionBase;

public class Section extends SectionBase<Section> implements Comparable<Section> {
	Section() {
		super(null);
	}
	
	Section(String text) {
		super(text);
		
		if (text.isEmpty()) {
			throw new ParsingException("Empty text parameter (Section.constructor)");
		}
	}
	
	public static Section parse(String text) {
		Objects.requireNonNull(text);
		text = text.trim() + "\n";
		return new Section(text);
	}
	
	public static Section create(String header, int level) {
		Objects.requireNonNull(header);
		
		Section section = new Section();
		
		section.setLevel(level);
		section.setHeader(header);
		
		return section;
	}
	
	public LangSection getLangSectionParent() {
		Section parentSection = this;
		
		while (parentSection != null) {
			if (parentSection instanceof LangSection) {
				return (LangSection) parentSection;
			}
			
			parentSection = parentSection.parentSection;
		}
		
		return null;
	}
	
	void sortSections() {
		if (childSections == null) {
			return;
		}
		
		Collections.sort(childSections);
		
		if (containingPage != null) {
			propagateTree();
		}
	}

	@Override
	public int compareTo(Section s) {
		// TODO: remove pronunciation sections
		final List<String> headList = Arrays.asList("Pronunciación y escritura", "Etimología");
		final List<String> bottomList = Arrays.asList(
			"Locuciones", "Refranes", "Conjugación", "Información adicional", "Véase también", "Traducciones"
		);
		
		String targetHeader = s.getHeader();
		
		boolean selfInHeadList = headList.contains(header);
		boolean selfInBottomList = bottomList.contains(header);
		boolean targetInHeadList = headList.contains(targetHeader);
		boolean targetInBottomList = bottomList.contains(targetHeader);
		
		if ((selfInHeadList && !targetInHeadList) || (!selfInBottomList && targetInBottomList)) {
			return -1;
		} else if ((!selfInHeadList && targetInHeadList) || (selfInBottomList && !targetInBottomList)) {
			return 1;
		} else if (selfInHeadList && targetInHeadList) {
			return Integer.compare(headList.indexOf(header), headList.indexOf(targetHeader));
		} else if (selfInBottomList && targetInBottomList) {
			return Integer.compare(bottomList.indexOf(header), bottomList.indexOf(targetHeader));
		} else {
			return 0;
		}
	}
}
