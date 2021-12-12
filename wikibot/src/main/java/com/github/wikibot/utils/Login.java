package com.github.wikibot.utils;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

import javax.security.auth.login.CredentialException;

import org.wikipedia.Wiki;
import org.wikiutils.LoginUtils;

import com.github.wikibot.main.Wikibot;

public class Login {
	private static final Path LOCATION = Paths.get("./data/sessions/");
	private static final String LOGIN_FORMAT = "%s@%s";
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
			userAgent = Files.readAllLines(LOCATION.resolve(USER_AGENT_FILENAME)).get(0);
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Setting basic user agent, please edit " + USER_AGENT_FILENAME);
			userAgent = "bot operator: User:" + username;
		}
		
		var fullUsername = String.format("%s@%s", username, BOT_PASSWORD_SUFFIX);
		LoginUtils.loginAndSetPrefs(wiki, fullUsername, password);
		
		wiki.setThrottle(DEFAULT_THROTTLE_MS);
		wiki.setMaxLag(DEFAULT_MAXLAG_S);
		wiki.setUserAgent(String.format("%s, %s", wiki.getUserAgent(), userAgent));
		wiki.setMarkBot(true);
		setAssertionFlag(wiki);
		
		System.out.printf("Logged in as %s at %s (%s)%n", wiki.getCurrentUser().getUsername(), wiki.getDomain(), wiki.version());
	}
	
	private static void setAssertionFlag(Wiki wiki) {
		var user = wiki.getCurrentUser();
		Objects.requireNonNull(user);
		
		var groups = new ArrayList<String>();
		
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
		var filename = String.format(LOGIN_FORMAT, username, BOT_PASSWORD_SUFFIX) + ".txt";
		System.out.println("Reading from: " + filename);
		return Files.readString(LOCATION.resolve(filename)).trim().toCharArray();
	}
	
	private static void promptAndStoreCredentials() throws IOException {
		System.out.print("Username: ");
		var username = Misc.readLine();
		
		System.out.print("Password: ");
		var password = readPassword();
		var filename = String.format(LOGIN_FORMAT, username, BOT_PASSWORD_SUFFIX) + ".txt";
		
		try {
			Files.write(LOCATION.resolve(filename), List.of(new String(password)), StandardOpenOption.CREATE_NEW);
		} catch (FileAlreadyExistsException e) {
			System.out.println(String.format("File %s already exists!", filename));
		}
	}
	
	private static char[] readPassword() {
		var console = System.console();
		
		if (console != null) {
			return console.readPassword();
		} else {
			@SuppressWarnings("resource")
			var scanner = new Scanner(System.in);
			return scanner.nextLine().toCharArray();
		}
	}
	
	public static void login(Wiki wiki) throws CredentialException {
		var username = System.getenv(ENV_USERNAME_VAR);
		login(wiki, username);
	}
	
	public static void login(Wiki wiki, String username) throws CredentialException {
		Objects.requireNonNull(wiki);
		Objects.requireNonNull(username);
		
		final char[] password;
		
		try {
			password = retrieveCredentials(username);
		} catch (ClassNotFoundException | IOException e) {
			throw new CredentialException("Unable to retrieve credentials: " + e.getMessage());
		}
		
		login(wiki, username, password);
	}
	
	public static Wikibot createSession(String domain) throws CredentialException {
		var wb = Wikibot.newSession(domain);
		login(wb);
		return wb;
	}
	
	public static Wikibot createSession(String domain, String username) throws CredentialException {
		var wb = Wikibot.newSession(domain);
		login(wb, username);
		return wb;
	}
	
	public static void main(String[] args) throws Exception {
		promptAndStoreCredentials();
	}
}
