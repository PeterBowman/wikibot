package com.github.wikibot.main;

import java.io.IOException;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.StringUtils;

public final class Test {
	public static void main(String[] args) throws IOException, LoginException {
		System.out.println(StringUtils.strip("===test retest ====", "=").trim());
		System.out.println("===test retest===".replaceAll("^=+?(.*?)=*+$", "$1"));
	}
}
