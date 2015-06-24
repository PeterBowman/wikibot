package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

public abstract class SectionBase<T extends SectionBase<T>> {
	protected String header;
	protected String intro;
	protected int level;
	protected int tocLevel;
	protected int leadingNewlines;
	protected int trailingNewlines;
	protected String headerFormat;
	protected T parentSection;
	protected List<T> siblingSections;
	protected List<T> childSections;
	protected PageBase<T> containingPage;
	
	protected SectionBase(String text) {
		header = "";
		intro = "";
		level = 0;
		tocLevel = 0;
		leadingNewlines = 0;
		trailingNewlines = 0;
		headerFormat = "%1$s %2$s %1$s";
		parentSection = null;
		siblingSections = null;
		childSections = null;
		containingPage = null;
		
		if (text != null && !text.isEmpty()) {
			parseSection(text);
		}
	}
	
	private void parseSection(String text) {
		String[] lines = text.split("\n", -1);
		parseHeader(lines[0]);
		
		if (lines.length == 1) {
			intro = "";
		} else if (String.join("", Arrays.copyOfRange(lines, 1, lines.length)).isEmpty()) {
			intro = "";
			trailingNewlines = lines.length - 1;
		} else {
			intro = text.substring(text.indexOf("\n") + 1);
			extractIntro();
		}
	}
	
	private void parseHeader(String header) {
		int i = 6;
		header = ParseUtils.removeCommentsAndNoWikiText(header);
		
		for (; i >= 1; --i) {
			String re = String.format("^={%1$d}(.+)={%1$d}\\s*$", i);
			Matcher m = Pattern.compile(re).matcher(header);
			
			if (m.matches()) {
				this.header = m.group(1).trim();
				this.level = i;
				buildHeaderFormatString(m.group(1));
				break;
			}
		}
		
		if (i < 1) {
			throw new ParsingException("Parsing error (SectionBase.parseHeader)");
		}
	}
	
	private void buildHeaderFormatString(String header) {
		headerFormat = header.replaceAll("^( *+).+?( *+)$", "%1\\$s$1%2\\$s$2%1\\$s");
	}
	
	protected void extractIntro() {
		while (intro.endsWith("\n")) {
			trailingNewlines++;
			intro = intro.substring(0, intro.length() - 1);
		}
		
		while (intro.startsWith("\n")) {
			leadingNewlines++;
			intro = intro.substring(1, intro.length());
		}
	}
	
	public String getHeader() {
		return header;
	}
	
	public void setHeader(String header) {
		this.header = header;
	}
	
	public String getIntro() {
		return intro;
	}
	
	public void setIntro(String intro) {
		this.intro = intro.trim();
		
		if (intro.isEmpty()) {
			leadingNewlines = 0;
		}
	}
	
	public int getLevel() {
		return level;
	}
	
	public void setLevel(int level) {
		if (level < 1 || level > 6) {
			throw new IllegalArgumentException("Invalid level value");
		}
		
		this.level = level;
		
		if (this.containingPage != null) {
			this.containingPage.buildSectionTree();
		}
	}
	
	public int getTocLevel() {
		return tocLevel;
	}
	
	public String getHeaderFormat() {
		return headerFormat;
	}
	
	public void setHeaderFormat(String headerFormat) {
		this.headerFormat = headerFormat;
	}
	
	public int getLeadingNewlines() {
		return leadingNewlines;
	}
	
	public void setLeadingNewlines(int leadingNewlines) {
		if (leadingNewlines < 0) {
			throw new IllegalArgumentException("\"leadingNewlines\" cannot be negative");
		}
		
		if (!intro.isEmpty()) {
			this.leadingNewlines = leadingNewlines;
		} else {
			this.trailingNewlines += leadingNewlines;
		}
	}
	
	public int getTrailingNewlines() {
		return trailingNewlines;
	}
	
	public void setTrailingNewlines(int trailingNewlines) {
		if (trailingNewlines < 0) {
			throw new IllegalArgumentException("\"trailingNewlines\" cannot be negative");
		}
		
		this.trailingNewlines = trailingNewlines;
	}

	public T getParentSection() {
		return parentSection;
	}
	
	public Collection<T> getSiblingSections() {
		return Collections.unmodifiableCollection(new ArrayList<T>(siblingSections));
	}
	
	public Collection<T> getChildSections() {
		return Collections.unmodifiableCollection(new ArrayList<T>(childSections));
	}
	
	public T nextSection() {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
		}
		
		int selfIndex = containingPage.sections.indexOf(this);
		
		try {
			return containingPage.sections.get(selfIndex + 1);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}
	
	public T previousSection() {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
		}
		
		int selfIndex = containingPage.sections.indexOf(this);
		
		try {
			return containingPage.sections.get(selfIndex - 1);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}
	
	public T nextSiblingSection() {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
		}
		
		if (siblingSections == null) {
			throw new UnsupportedOperationException("No sibling Sections attached");
		}
		
		int selfIndex = siblingSections.indexOf(this);
		
		try {
			return siblingSections.get(selfIndex + 1);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}
	
	public T previousSiblingSection() {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
		}
		
		if (siblingSections == null) {
			throw new UnsupportedOperationException("No sibling Sections attached");
		}
		
		int selfIndex = siblingSections.indexOf(this);
		
		try {
			return siblingSections.get(selfIndex - 1);
		} catch (IndexOutOfBoundsException e) {
			return null;
		}
	}
	
	public void appendSections(@SuppressWarnings("unchecked") T... sections) {
		if (sections.length != 0) {
			for (T section : sections) {
				if (section.level <= level) {
					throw new UnsupportedOperationException("Invalid level of appended Sections: " + section.level + " (must be > " + level + ")");
				}
			}
			
			if (containingPage != null) {
				T nextSibling = nextSiblingSection();
				
				if (nextSibling != null) {
					int index = containingPage.sections.indexOf(nextSibling);
					containingPage.sections.addAll(index, flattenSubSections(Arrays.asList(sections)));
				} else {
					containingPage.sections.addAll(flattenSubSections(Arrays.asList(sections)));
				}
				
				containingPage.buildSectionTree();
			} else {
				Collections.addAll(childSections, sections);
			}
		}
	}
	
	public void prependSections(@SuppressWarnings("unchecked") T... sections) {
		if (sections.length != 0) {
			for (T section : sections) {
				if (section.level <= level) {
					throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be > " + level + ")");
				}
			}
			
			if (containingPage != null) {
				int index = containingPage.sections.indexOf(this);
				containingPage.sections.addAll(index + 1, flattenSubSections(Arrays.asList(sections)));
				containingPage.buildSectionTree();
			} else {
				childSections.addAll(0, Arrays.asList(sections));
			}
		}
	}
	
	public void insertSectionsAfter(@SuppressWarnings("unchecked") T... sections) {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot insert Sections with no containing Page");
		}
		
		if (sections.length != 0) {
			for (T section : sections) {
				if (section.level != level) {
					throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be == " + level + ")");
				}
			}
			
			int index = siblingSections.indexOf(this);
			siblingSections.addAll(index + 1, Arrays.asList(sections));
			containingPage.buildSectionTree();
		}
	}
	
	public void insertSectionsBefore(@SuppressWarnings("unchecked") T... sections) {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot insert Sections with no containing Page");
		}
		
		if (sections.length != 0) {
			for (T section : sections) {
				if (section.level != level) {
					throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be == " + level + ")");
				}
			}
			
			int index = siblingSections.indexOf(this);
			siblingSections.addAll(index, Arrays.asList(sections));
			containingPage.buildSectionTree();
		}
	}
	
	public void detach() {
		if (parentSection == null) {
			throw new UnsupportedOperationException("Cannot detach Sections with no parent Section");
		}
		
		parentSection.childSections.remove(this);
		
		if (!parentSection.childSections.isEmpty()) {
			for (T sibling : parentSection.childSections) {
				sibling.siblingSections.remove(this);
			}
		} else {
			parentSection.childSections = null;
		}
		
		if (containingPage != null) {
			containingPage.sections.remove(this);
			
			if (childSections != null) {
				List<T> flattened = flattenSubSections(childSections);
				containingPage.sections.removeAll(flattened);
				containingPage.buildSectionTree();
			}
		}
	}
	
	public void detachOnlySelf() {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot detach Sections with no containing Page");
		}
		
		containingPage.sections.remove(this);
		containingPage.buildSectionTree();
	}
	
	public void pushLevels(int diff) {
		if (diff < -5 || diff == 0 || diff > 5) {
			throw new IllegalArgumentException("Level diffs must be included either in [-5,-1] or [1,5]");
		}
		
		if (level + diff < 1) {
			throw new IllegalArgumentException("New level out of accepted range (SectionBase.pushLevels)");
		}
		
		if (childSections != null) {
			List<T> subSections = flattenSubSections(childSections);
			
			if (diff > 0) {
				int highestLevel = subSections.stream()
					.map(section -> section.getLevel())
					.max(Integer::max)
					.get();
				
				if (highestLevel + diff > 6) {
					throw new IllegalArgumentException("New level out of accepted range (SectionBase.pushLevels)");
				}
			}
			
			for (T section : subSections) {
				int subLevel = section.getLevel();
				section.setLevel(subLevel + diff);
			}
		}
		
		setLevel(level + diff);
	}
	
	public List<T> findSubSectionsWithHeader(String regex) {
		if (childSections != null) {
			return flattenSubSections(childSections).stream()
				.filter(section -> section.getHeader().matches(regex))
				.collect(Collectors.toList());
		} else {
			return new ArrayList<T>();
		}
	}
	
	public void replaceWith(T section) {
		if (containingPage == null) {
			throw new UnsupportedOperationException("Cannot replace Sections with no containing Page");
		}
		
		if (section.getLevel() != level) {
			throw new UnsupportedOperationException("Target and replacing Sections must have the same level");
		}
		
		int index = containingPage.sections.indexOf(this);
		containingPage.sections.remove(index);
		
		if (childSections != null) {
			List<T> flattened = flattenSubSections(childSections);
			containingPage.sections.removeAll(flattened);
		}
		
		if (section.childSections != null) {
			List<T> replacementSections = new ArrayList<T>();
			replacementSections.addAll(flattenSubSections(section));
			containingPage.sections.addAll(index, replacementSections);
		} else {
			containingPage.sections.add(index, section);
		}
		
		containingPage.buildSectionTree();
	}
	
	public static <U extends SectionBase<U>> List<U> flattenSubSections(U section) {
		return flattenSubSections(Arrays.asList(section));
	}
	
	public static <U extends SectionBase<U>> List<U> flattenSubSections(List<? extends U> sections) {
		List<U> list = new ArrayList<U>();
		
		for (U section : sections) {
			list.add(section);
			
			if (section.childSections != null) {
				list.addAll(flattenSubSections(section.childSections));
			}
		}
		
		return list;
	}
	
	@Override
	public int hashCode() {
		return 31 * toString().hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof SectionBase)) {
			return false;
		}
		
		@SuppressWarnings("unchecked")
		SectionBase<T> s = (SectionBase<T>) obj;
		return s.toString().equals(toString());
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(1500);
		sb.append(String.format(headerFormat, StringUtils.repeat('=', level), header));

		if (!intro.isEmpty()) {
			sb.append("\n");
		}
		
		sb.append(StringUtils.repeat('\n', leadingNewlines));
		sb.append(intro);
		sb.append(StringUtils.repeat('\n', trailingNewlines));
		
		if (childSections != null) {
			sb.append("\n");
			
			for (T subSection : childSections) {
				sb.append(subSection);
				sb.append("\n");
			}
			
			sb.deleteCharAt(sb.length() - 1);
		}
		
		return sb.toString();
	}
}
