package com.github.wikibot.parsing;

public class ParsingException extends RuntimeException {
    private static final long serialVersionUID = -8845784388792588721L;

    public ParsingException() {
        super();
    }

    public ParsingException(String message) {
        super(message);
    }

    public ParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    public ParsingException(Throwable cause) {
        super(cause);
    }
}
