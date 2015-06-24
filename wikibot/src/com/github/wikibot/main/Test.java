package com.github.wikibot.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.security.auth.login.LoginException;

public final class Test {
	public static void main(String[] args) throws IOException, LoginException {
		List<String> list = Arrays.asList("f", "a", "d", "b");
		List<String> coll = new ArrayList<String>(Collections.unmodifiableList(new ArrayList<String>(list)));
		System.out.println(coll);
		Collections.sort((List<String>) coll);
		System.out.println(coll);
	}
}
