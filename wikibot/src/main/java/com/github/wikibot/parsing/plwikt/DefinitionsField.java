package com.github.wikibot.parsing.plwikt;

import java.util.ArrayList;
import java.util.List;

public final class DefinitionsField extends Field {
	List<DefinitionHeader> definitions;
	
	protected DefinitionsField(FieldTypes name, String content) {
		super(name, content);
		definitions = new ArrayList<>();
		parseField();
	}
	
	private void parseField() {
		String[] lines = content.split("\n");
		String currentHeader = null;
		List<String> currentDefinitions = null;
		
		for (String line : lines) {
			if (line.startsWith(":") && currentDefinitions != null) {
				currentDefinitions.add(line);
			} else {
				if (currentHeader != null) {
					definitions.add(new DefinitionHeader(currentHeader, currentDefinitions));
				}
				
				currentHeader = line;
				currentDefinitions = new ArrayList<>();
			}
		}
		
		if (currentHeader != null) {
			definitions.add(new DefinitionHeader(currentHeader, currentDefinitions));
		}
	}
	
	public DefinitionHeader addDefinitionHeader(String headerTitle, String content) {
		List<String> definitions = new ArrayList<>();
		definitions.add(content);
		DefinitionHeader header = new DefinitionHeader(headerTitle, definitions);
		content += "\n" + header.toString();
		return header;
	}
	
	// TODO
	public boolean hasDefinitionHeader(String substring) {
		boolean found = false;
		
		@SuppressWarnings("unused")
		List<String> list = definitions.stream()
			.map(def -> def.header)
			.toList();
		
		return found;
	}
}
