package com.github.wikibot.parsing.plwikt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DefinitionHeader {
    String header;
    List<String> definitions;

    DefinitionHeader(String header, List<String> definitions) {
        this.header = header;
        this.definitions = Objects.requireNonNullElse(definitions, new ArrayList<>());
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("''%s''", header));
        sb.append("\n");

        for (String definition : definitions) {
            sb.append(definition);
            sb.append("\n");
        }

        return sb.toString().trim();
    }
}
