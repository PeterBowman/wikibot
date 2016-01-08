package com.github.wikibot.tasks;

import java.io.IOException;
import java.net.URLDecoder;
import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.Objects;

import javax.security.auth.login.FailedLoginException;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Users;

public final class Purge {

	public static void main(String[] args) throws IOException, FailedLoginException {
		if (args.length < 3) {
			throw new InvalidParameterException();
		}
		
		Domains domain = Domains.findDomain(args[0]);
		Objects.requireNonNull(domain);
		Wikibot wb = Login.retrieveSession(domain, Users.USER1);
		
		int opts = Integer.parseInt(args[1]);
		String[] titles = Arrays.copyOfRange(args, 2, args.length);
		
		for (int i = 0; i < titles.length; i++) {
			String arg = titles[i];
			titles[i] = URLDecoder.decode(arg, "UTF8");
		}
		
		switch (opts) {
			case 1:
				wb.purge(false, titles);
				break;
			case 2:
				wb.purge(true, titles);
				break;
			case 3:
				wb.purgeRecursive(titles);
				break;
			default:
				throw new InvalidParameterException("Invalid second parameter: " + args[1]);
		}
	}

}
