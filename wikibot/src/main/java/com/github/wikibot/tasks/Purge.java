package com.github.wikibot.tasks;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.List;

import org.wikipedia.Wiki;

import com.github.wikibot.utils.Login;

public final class Purge {

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			throw new IllegalArgumentException();
		}
		
		String domain = args[0];
		Wiki wiki = Wiki.newSession(domain);
		Login.login(wiki);

		int opts = Integer.parseInt(args[1]);
		String[] titles = Arrays.copyOfRange(args, 2, args.length);

		switch (opts) {
		case 1:
			wiki.purge(false, titles);
			break;
		case 2:
			wiki.purge(true, titles);
			break;
		case 3:
			for (String title : titles) {
				wiki.purge(true, wiki.whatTranscludesHere(List.of(title)).get(0).toArray(String[]::new));
			}
			break;
		case 4:
			for (String title : titles) {
				wiki.purge(false, wiki.whatTranscludesHere(List.of(title)).get(0).toArray(String[]::new));
			}
			break;
		default:
			throw new InvalidParameterException("Invalid second parameter: " + args[1]);
		}
	}

}
