package com.github.wikibot.parsing.eswikt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.SectionBase;

public class Section extends SectionBase<Section> implements Comparable<Section> {
	public static final List<String> HEAD_SECTIONS = Arrays.asList(
		"Pronunciación y escritura", "Etimología"
	);
	
	public static final List<String> BOTTOM_SECTIONS = Arrays.asList(
		"Locuciones", "Refranes", "Conjugación", "Información adicional", "Véase también", "Traducciones"
	);

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
		section.setHeaderFormat("%1$s%2$s%1$s");
		
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
		propagateTree();
	}

	@Override
	public int compareTo(Section s) {
		String targetHeader = s.getHeader();
		
		boolean selfInHeadList = HEAD_SECTIONS.contains(header);
		boolean selfInBottomList = BOTTOM_SECTIONS.contains(header);
		boolean targetInHeadList = HEAD_SECTIONS.contains(targetHeader);
		boolean targetInBottomList = BOTTOM_SECTIONS.contains(targetHeader);
		
		if ((selfInHeadList && !targetInHeadList) || (!selfInBottomList && targetInBottomList)) {
			return -1;
		} else if ((!selfInHeadList && targetInHeadList) || (selfInBottomList && !targetInBottomList)) {
			return 1;
		} else if (selfInHeadList && targetInHeadList) {
			return Integer.compare(HEAD_SECTIONS.indexOf(header), HEAD_SECTIONS.indexOf(targetHeader));
		} else if (selfInBottomList && targetInBottomList) {
			return Integer.compare(BOTTOM_SECTIONS.indexOf(header), BOTTOM_SECTIONS.indexOf(targetHeader));
		} else {
			return 0;
		}
	}
}
