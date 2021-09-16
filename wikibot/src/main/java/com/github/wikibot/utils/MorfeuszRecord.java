package com.github.wikibot.utils;

import java.util.Objects;

public final class MorfeuszRecord implements Comparable<MorfeuszRecord> {
	private String form;
	private String lemma;
	private String tags;
	private String name;
	private String labels;
	
	public MorfeuszRecord(String form, String lemma, String tags, String name, String labels) {
		this.form = Objects.requireNonNull(form);
		this.lemma = Objects.requireNonNull(lemma);
		this.tags = Objects.requireNonNull(tags);
		this.name = Objects.requireNonNullElse(name, "");
		this.labels = Objects.requireNonNullElse(labels, "");
	}
	
	public static MorfeuszRecord fromArray(String[] values) {
		if (values.length != 5) {
			throw new IllegalArgumentException("expected 5 values, got " + values.length);
		}
		
		return new MorfeuszRecord(values[0], values[1], values[2], values[3], values[4]);
	}
	
	public String getForm() {
		return this.form;
	}
	
	public String getLemma() {
		return this.lemma;
	}
	
	public String[] getTags() {
		return this.tags.split(":");
	}
	
	public String getTagsAsString() {
		return this.tags;
	}
	
	public String getName() {
		return this.name;
	}
	
	public String[] getLabels() {
		return this.labels.split("|");
	}
	
	public String getLabelsAsString() {
		return this.labels;
	}
	
	@Override
	public String toString() {
		return String.format("[%s, %s, %s, %s, %s]", form, lemma, tags, name, labels);
	}
	
	@Override
	public int hashCode() {
		return form.hashCode() + lemma.hashCode() + tags.hashCode() + name.hashCode() + labels.hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		} else if (obj instanceof MorfeuszRecord mr) {
			return mr.form.equals(form) && mr.lemma.equals(lemma) && mr.tags.equals(tags) &&
				mr.labels.equals(labels) && mr.name.equals(name);
		} else {
			return false;
		}
	}
	
	@Override
	public int compareTo(MorfeuszRecord o) {
		if (!form.equals(o.form)) {
			return form.compareTo(o.form);
		}
		
		if (!lemma.equals(o.lemma)) {
			return lemma.compareTo(o.lemma);
		}
		
		if (!tags.equals(o.tags)) {
			return tags.compareTo(o.tags);
		}
		
		if (!name.equals(o.name)) {
			return name.compareTo(o.name);
		}
		
		if (!labels.equals(o.labels)) {
			return labels.compareTo(o.labels);
		}
		
		return 0;
	}
}