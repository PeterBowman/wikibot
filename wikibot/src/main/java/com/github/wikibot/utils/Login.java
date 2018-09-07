package com.github.wikibot.utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.security.auth.login.CredentialException;

import org.wikipedia.Wiki;
import org.wikiutils.LoginUtils;

import com.github.wikibot.main.Wikibot;

public class Login {
	private static final String LOCATION = "./data/sessions/";
	private static final String FILE_FORMAT = LOCATION + "%s@%s.ser";
	private static final String USER_AGENT_FILENAME = "useragent.txt";
	private static final String BOT_PASSWORD_SUFFIX = "wikibot";
	private static final String ENV_USERNAME_VAR = "WIKIBOT_MAIN_ACCOUNT";
	
	private static final int DEFAULT_THROTTLE_MS = 5000;
	private static final int DEFAULT_MAXLAG_S = 5;
	
	private Login() {}
	
	public static void login(Wiki wiki, String username, char[] password) {
		Objects.requireNonNull(username);
		Objects.requireNonNull(password);
		
		String userAgent;
		
		try {
			userAgent = Files.readAllLines(Paths.get(LOCATION + USER_AGENT_FILENAME)).get(0);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Setting basic user agent, please edit " + USER_AGENT_FILENAME);
			userAgent = "bot operator: User:" + username;
		}
		
		LoginUtils.loginAndSetPrefs(wiki, username, password);
		
		wiki.setThrottle(DEFAULT_THROTTLE_MS);
		wiki.setMaxLag(DEFAULT_MAXLAG_S);
		wiki.setUserAgent(String.format("%s, %s", wiki.getUserAgent(), userAgent));
		wiki.setMarkBot(true);
		setAssertionFlag(wiki);
		
		System.out.printf("Logged in as %s (%s)%n", wiki.getCurrentUser().getUsername(), wiki.version());
	}
	
	private static void setAssertionFlag(Wiki wiki) {
		Wiki.User user = wiki.getCurrentUser();
		Objects.requireNonNull(user);
		
		List<String> groups = new ArrayList<>();
		
		int assertion = Wiki.ASSERT_USER;
		groups.add("user");
		
		if (user.isA("bot")) {
			assertion |= Wiki.ASSERT_BOT;
			groups.add("bot");
		}
		
		if (user.isA("sysop")) {
			assertion |= Wiki.ASSERT_SYSOP;
			groups.add("sysop");
		}
		
		wiki.setAssertionMode(assertion);
		System.out.printf("Groups for user %s: %s%n", user.getUsername(), groups);
	}
	
	private static char[] retrieveCredentials(String username) throws ClassNotFoundException, IOException {
		return Misc.deserialize(LOCATION + String.format(FILE_FORMAT, username, BOT_PASSWORD_SUFFIX));
	}
	
	private static void promptAndStoreCredentials() throws FileNotFoundException, IOException {
		System.out.print("Username: ");
		String username = Misc.readLine();
		
		System.out.print("Password: ");
		char[] password = Misc.readPassword();
		
		String filename = LOCATION + String.format(FILE_FORMAT, username, BOT_PASSWORD_SUFFIX);
		Misc.serialize(password, filename);
	}
	
	public static Wikibot createSession(String domain) throws CredentialException {
		String username = System.getenv(ENV_USERNAME_VAR);
		return createSession(domain, username);
	}
	
	public static Wikibot createSession(String domain, String username) throws CredentialException {
		Objects.requireNonNull(domain);
		Objects.requireNonNull(username);
		
		final char[] password;
		
		try {
			password = retrieveCredentials(username);
		} catch (ClassNotFoundException | IOException e) {
			throw new CredentialException("Unable to retrieve credentials: " + e.getMessage());
		}
		
		Wikibot wb = Wikibot.createInstance(domain);
		login(wb, username, password);
		
		return wb;
	}
	
	public static void main(String[] args) throws Exception {
		promptAndStoreCredentials();
	}
}
