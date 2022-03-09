package com.github.wikibot.parsing;

import java.util.Objects;

public class Section extends AbstractSection<Section> {
	Section() {
		super(null);
	}
	
	Section(String text) {
		super(text);
		
		if (text.isEmpty()) {
			throw new UnsupportedOperationException("Empty text parameter (Section.constructor)");
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
		section.setTrailingNewlines(1);
		
		return section;
	}
}
