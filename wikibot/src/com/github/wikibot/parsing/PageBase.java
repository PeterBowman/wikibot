package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;

public abstract class PageBase<T extends SectionBase<T>> {
	protected String title;
	protected String intro;
	protected List<T> sections;
	protected int leadingNewlines;
	protected int trailingNewlines;
	private static final Pattern P_SECTION = Pattern.compile("^(?=={1,6}.+?={1,6}\\s*(?:(?:<!--.*?-->)+\\s*)?$)", Pattern.MULTILINE);
	
	public PageBase(String title) {
		this.title = Objects.requireNonNull(title);
		this.title = this.title.trim();
		this.intro = "";
		this.sections = new ArrayList<T>();
		this.leadingNewlines = 0;
		this.trailingNewlines = 0;
	}
	
	public String getTitle() {
		return title;
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
	
	public void appendSections(@SuppressWarnings("unchecked") T... sections) {
		if (sections.length != 0) {
			Collections.addAll(this.sections, sections);
			buildSectionTree();
		}
	}
	
	public void prependSections(@SuppressWarnings("unchecked") T... sections) {
		if (sections.length != 0) {
			this.sections.addAll(0, Arrays.asList(sections));
			buildSectionTree();
		}
	}
	
	public List<T> getAllSections() {
		return sections;
	}
	
	public void normalizeChildLevels() {
		for (int selfIndex = 1; selfIndex < sections.size(); selfIndex++) {
			T currentSection = sections.get(selfIndex);
			int currentLevel = currentSection.getLevel();
			
			for (int prevIndex = selfIndex - 1; prevIndex > -1; prevIndex--) {
				T previousSection = sections.get(prevIndex);
				int previousLevel = previousSection.getLevel();
				
				if (currentLevel > previousLevel) {
					if (currentLevel > previousLevel + 1) {
						currentSection.setLevel(previousLevel + 1);
					}
					
					break;
				}
			}
		}
		
		buildSectionTree();
	}
	
	public boolean hasSectionWithHeader(String regex) {
		return sections.parallelStream()
			.anyMatch(section -> section.getHeader().matches(regex));
	}
	
	public List<T> findSectionsWithHeader(String regex) {
		return sections.stream()
			.filter(section -> section.getHeader().matches(regex))
			.collect(Collectors.toList());
	}
	
	public void sortSections(Comparator<T> comparator) {
		Collections.sort(sections, comparator);
	}
	
	protected void extractSections(String text, Function<String, T> func) {
		extractSections(text, func, P_SECTION);
	}
	
	protected void extractSections(String text, Function<String, T> func, Pattern pSection) {
		List<Range<Integer>> ignoredRanges = getIgnoredRanges(text);
		Matcher m = pSection.matcher(text);
		StringBuffer sb = new StringBuffer(1000);
		List<String> sections = new ArrayList<String>();
		
		while (m.find()) {
			if (
				!ignoredRanges.isEmpty() &&
				ignoredRanges.parallelStream().anyMatch(range -> range.contains(m.start()))
			) {
				continue;
			}
			
			m.appendReplacement(sb, "");
			sections.add(sb.toString());
			sb = new StringBuffer(1000);
		}
		
		m.appendTail(sb);
		sections.add(sb.toString());
		
		intro = sections.get(0);
		
		if (intro.endsWith("\n")) {
			intro = intro.substring(0, intro.length() - 1);
		}
		
		extractIntro();
		
		for (int i = 1; i < sections.size(); i++) {
			String sectionText = sections.get(i);
			
			if (sectionText.endsWith("\n")) {
				sectionText = sectionText.substring(0, sectionText.length() - 1);
			}
			
			T section = func.apply(sectionText);
			section.containingPage = this;
			this.sections.add(section);
		}
		
		buildSectionTree();
	}
	
	private List<Range<Integer>> getIgnoredRanges(String text) {
		Range<Integer>[] comments = Utils.findRanges(text, "<!--", "-->");
		Range<Integer>[] nowikis = Utils.findRanges(text, "<nowiki>", "</nowiki>");
		Range<Integer>[] pres = Utils.findRanges(text, Pattern.compile("<pre(?: |>).+?</pre>", Pattern.DOTALL));
		Range<Integer>[] codes = Utils.findRanges(text, Pattern.compile("<code(?: |>).+?</code>", Pattern.DOTALL));
		
		List<Range<Integer>> ranges = Arrays.asList(comments, nowikis, pres, codes)
			.stream()
			.filter(Objects::nonNull)
			.flatMap(array -> Stream.of(array))
			.sorted((r1, r2) -> Integer.compare(r1.getMinimum(), r2.getMinimum()))
			.collect(Collectors.toList());
		
		ListIterator<Range<Integer>> iterator = ranges.listIterator(ranges.size());
		
		while (iterator.hasPrevious()) {
			Range<Integer> range = iterator.previous();
			int previousIndex = iterator.previousIndex();
			
			if (previousIndex != -1) {
				Range<Integer> range2 = ranges.get(previousIndex);
				
				if (range2.isOverlappedBy(range)) {
					iterator.remove();
				}
			}
		}
		
		return ranges;
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
	
	protected void buildSectionTree() {
		for (T section : sections) {
			section.parentSection = null;
			section.siblingSections = null;
			section.childSections = null;
			section.containingPage = this;
		}
		
		traverseSiblingSections(sections, 1);
	}

	private List<T> traverseSiblingSections(List<T> sections, final int tocLevel) {
		List<T> siblings = new ArrayList<T>(sections.size());
		Map<T, List<T>> map = new LinkedHashMap<T, List<T>>(sections.size());
		int minLevel = 6;
		
		for (T section : sections) {
			int level = section.getLevel();
			minLevel = Math.min(level, minLevel);
			
			if (level <= minLevel) {
				siblings.add(section);
				section.tocLevel = tocLevel;
				section.siblingSections = siblings;
			} else {
				T previous = siblings.get(siblings.size() - 1);
				
				if (map.containsKey(previous)) {
					List<T> children = map.get(previous);
					children.add(section);
				} else {
					List<T> children = new ArrayList<T>();
					children.add(section);
					map.put(previous, children);
				}
			}
		}
		
		for (Entry<T, List<T>> entry : map.entrySet()) {
			T section = entry.getKey();
			List<T> sibl = entry.getValue();
			section.childSections = traverseSiblingSections(sibl, tocLevel + 1);
			
			for (T child : section.childSections) {
				child.parentSection = section;
			}
		}
		
		if (siblings.isEmpty()) {
			return null;
		} else {
			return siblings;
		}
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		
		sb.append(StringUtils.repeat('\n', leadingNewlines));
		sb.append(intro);
		sb.append(StringUtils.repeat('\n', trailingNewlines));
		
		if (sb.length() != 0) {
			sb.append("\n");
		}
		
		for (T section : sections) {
			if (section.tocLevel > 1) {
				continue;
			}
			
			sb.append(section);
			sb.append("\n");
		}
		
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}
}
