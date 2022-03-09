package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class AbstractPage<T extends AbstractSection<T>> {
	protected String title;
	protected String intro;
	protected List<T> sections;
	protected int leadingNewlines;
	protected int trailingNewlines;
	private static final Pattern P_SECTION = Pattern.compile("^(?=(?:<!--.*?-->)*+(={1,6}.+?={1,6})\\s*(?:(?:<!--.*?-->)+\\s*)?$)", Pattern.MULTILINE);
	
	public AbstractPage(String title) {
		this.title = Objects.requireNonNull(title);
		this.title = this.title.trim();
		this.intro = "";
		this.sections = new ArrayList<>();
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
	
	public void appendSections(List<T> sections) {
		if (!sections.isEmpty()) {
			this.sections.addAll(sections);
			buildSectionTree();
		}
	}
	
	public void prependSections(List<T> sections) {
		if (!sections.isEmpty()) {
			this.sections.addAll(0, sections);
			buildSectionTree();
		}
	}
	
	public List<T> getAllSections() {
		return Collections.unmodifiableList(new ArrayList<>(sections));
	}
	
	public void normalizeChildLevels() {
		if (sections.isEmpty()) {
			return;
		}
		
		int tocLevel = 1;
		ObjIntConsumer<List<T>> cons = (sections, level) -> sections.stream().forEach(s -> s.setLevel(level));
		
		while (true) {
			List<T> list = new ArrayList<>();
			
			for (T section : sections) {
				if (
					section.getTocLevel() == tocLevel &&
					(
						list.isEmpty() ||
						!list.get(list.size() - 1).equals(section.getSiblingSections())
					)
				) {
					list.add(section);
				}
			}
			
			if (list.isEmpty()) {
				break;
			}
			
			for (T section : list) {
				List<T> siblings = section.getSiblingSections();
				T parent = section.getParentSection().orElse(null);
				
				int minLevel = siblings.stream()
					.map(AbstractSection::getLevel)
					.min(Integer::min)
					.get();
				
				int maxLevel = siblings.stream()
					.map(AbstractSection::getLevel)
					.max(Integer::max)
					.get();
				
				if (
					minLevel != maxLevel ||
					(parent != null && minLevel > parent.getLevel() + 1)
				) {
					if (parent != null) {
						int parentLevel = parent.getLevel();
						cons.accept(siblings, parentLevel + 1);
					} else {
						siblings = new ArrayList<>(section.siblingSections);
						siblings.remove(siblings.size() - 1);
						cons.accept(siblings, minLevel + 1);
					}
				}
			}
			
			tocLevel++;
		}
		
		buildSectionTree();
	}
	
	public boolean hasSectionWithHeader(String regex) {
		return sections.stream()
			.anyMatch(section -> section.getStrippedHeader().matches(regex));
	}
	
	public List<T> filterSections(Predicate<T> predicate) {
		return sections.stream()
			.filter(predicate)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	public List<T> findSectionsWithHeader(String regex) {
		return filterSections(section -> section.getStrippedHeader().matches(regex));
	}

	public void sortSections(Comparator<T> comparator) {
		Collections.sort(sections, comparator);
	}
	
	protected void extractSections(String text, Function<String, T> func) {
		extractSections(text, func, P_SECTION);
	}
	
	protected void extractSections(String text, Function<String, T> func, Pattern pSection) {
		List<String> sections = new ArrayList<>();
		
		String lastChunk = Utils.replaceWithStandardIgnoredRanges(text, pSection,
			m -> m.start(1),
			(m, sb) -> {
				m.appendReplacement(sb, "");
				sections.add(sb.toString());
				sb.delete(0, sb.length());
			}
		);
		
		sections.add(lastChunk);
		intro = sections.get(0);
		
		if (!intro.isEmpty()) {
			if (intro.endsWith("\n")) {
				intro = intro.substring(0, intro.length() - 1);
			}
			
			extractIntro();
		}
		
		for (int i = 1; i < sections.size(); i++) {
			String sectionText = sections.get(i);
			
			if (sectionText.endsWith("\n") && i < sections.size() - 1) {
				sectionText = sectionText.substring(0, sectionText.length() - 1);
			}
			
			T section = func.apply(sectionText);
			section.containingPage = this;
			this.sections.add(section);
		}
		
		buildSectionTree();
	}
	
	protected void extractIntro() {
		String[] lines = intro.split("\n", -1);
		
		if (String.join("", lines).isEmpty()) {
			intro = "";
			trailingNewlines = lines.length;
			return;
		}
		
		while (intro.endsWith("\n")) {
			trailingNewlines++;
			intro = intro.substring(0, intro.length() - 1);
		}
		
		while (intro.startsWith("\n")) {
			leadingNewlines++;
			intro = intro.substring(1);
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
		
		for (T section : sections) {
			if (section.siblingSections == null) {
				section.siblingSections = new ArrayList<>(0);
			}
			
			if (section.childSections == null) {
				section.childSections = new ArrayList<>(0);
			}
		}
	}

	private List<T> traverseSiblingSections(List<T> sections, final int tocLevel) {
		List<T> siblings = new ArrayList<>(sections.size());
		Map<T, List<T>> map = new LinkedHashMap<>(sections.size());
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
					List<T> children = new ArrayList<>();
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
		
		sb.append("\n".repeat(leadingNewlines));
		sb.append(intro);
		sb.append("\n".repeat(trailingNewlines));
		
		if (!intro.isEmpty()) {
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
