package com.github.wikibot.tasks;

import java.security.InvalidParameterException;
import java.util.Arrays;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public final class Purge {

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			throw new IllegalArgumentException();
		}
		
		String domain = args[0];
		Wikibot wb = Login.createSession(domain);

		int opts = Integer.parseInt(args[1]);
		String[] titles = Arrays.copyOfRange(args, 2, args.length);

		switch (opts) {
		case 1:
			wb.purge(false, titles);
			break;
		case 2:
			wb.purge(true, titles);
			break;
		case 3:
			for (String title : titles) {
				wb.purge(true, wb.whatTranscludesHere(title));
			}
			break;
		case 4:
			for (String title : titles) {
				wb.purge(false, wb.whatTranscludesHere(title));
			}
			break;
		default:
			throw new InvalidParameterException("Invalid second parameter: " + args[1]);
		}
	}

}
