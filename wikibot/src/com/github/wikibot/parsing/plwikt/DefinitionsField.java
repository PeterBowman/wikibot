package com.github.wikibot.parsing.plwikt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class DefinitionsField extends Field {
	List<DefinitionHeader> definitions;
	
	protected DefinitionsField(FieldTypes name, String content) {
		super(name, content);
		definitions = new ArrayList<DefinitionHeader>();
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
				currentDefinitions = new ArrayList<String>();
			}
		}
		
		if (currentHeader != null) {
			definitions.add(new DefinitionHeader(currentHeader, currentDefinitions));
		}
	}
	
	public DefinitionHeader addDefinitionHeader(String headerTitle, String content) {
		List<String> definitions = new ArrayList<String>();
		definitions.add(content);
		DefinitionHeader header = new DefinitionHeader(headerTitle, definitions);
		content += "\n" + header.toString();
		return header;
	}
	
	public boolean hasDefinitionHeader(String substring) {
		boolean found = false;
		
		List<String> list = definitions.stream()
			.map(def -> def.header)
			.collect(Collectors.toList());
		
		return found;
	}
}
