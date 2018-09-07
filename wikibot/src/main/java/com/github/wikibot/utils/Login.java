package com.github.wikibot.utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikipedia.Wiki;
import org.wikipedia.Wiki.User;
import org.wikiutils.LoginUtils;

import com.github.wikibot.main.Wikibot;

public class Login {
	private static Map<String, char[]> credentials;
	private static final String LOCATION = "./data/sessions/";
	private static final String FILE_FORMAT = LOCATION + "%s@%s.ser";
	
	private Login() {}
	
	public static void login(Wiki wiki, Users user) throws FailedLoginException, IOException {
		Objects.requireNonNull(user);
		
		if (credentials == null) {
			retrieveCredentials();
		}
		
		if (!credentials.containsKey(user.getUsername())) {
			throw new FailedLoginException("No credentials found for this user");
		}
		
		String userAgent = Files.readAllLines(Paths.get(LOCATION + "useragent.txt")).get(0);
		LoginUtils.loginAndSetPrefs(wiki, user.getUsername(), credentials.get(user.getUsername()));
		
		wiki.setThrottle(5000);
		wiki.setMaxLag(5);
		wiki.setUserAgent(wiki.getUserAgent() + ", " + userAgent);
		
		boolean isBot = Arrays.asList(user.hasBot()).contains(Domains.findDomain(wiki.getDomain()));
		wiki.setAssertionMode(isBot ? Wiki.ASSERT_BOT : Wiki.ASSERT_USER);
		wiki.setMarkBot(isBot);
		
		wiki.usesCapitalLinks();
		
		System.out.println("Logged in as: " + wiki.getCurrentUser().getUsername());
	}
	
	private static void retrieveCredentials() throws FileNotFoundException, IOException {
		try {
			credentials = Misc.deserialize(LOCATION + "credentials.ser");
		} catch (ClassNotFoundException | IOException e) {
			System.out.println(e.getMessage());
			credentials = new HashMap<>();
		}
	}
	
	private static void storeCredentials(Users user) throws FileNotFoundException, IOException {
		System.out.printf("Username: %s%n", user.getUsername());
		System.out.print("Password: ");
		char[] password = Misc.readPassword();
		credentials.put(user.getUsername(), password);
	}
	
	public static Wikibot generateSession(Domains domain, Users user) throws FailedLoginException, IOException {
		Wikibot wb = Wikibot.createInstance(domain.getDomain());
		login(wb, user);
		return wb;
	}
	
	public static Wikibot retrieveSession(Domains domain, Users user) throws FailedLoginException, IOException {
		Wikibot wb;
		
		try {
			wb = Misc.deserialize(String.format(FILE_FORMAT, user.getAlias(), domain.getDomain()));
			
			try {
				wb.usesCapitalLinks();
			} catch (AssertionError e1) {
				System.out.println(e1.getMessage());
				wb.logout();
				login(wb, user);
			}
		} catch (ClassCastException | ClassNotFoundException | IOException e2) {
			System.out.println(e2.getMessage());
			wb = generateSession(domain, user);
		}
		
		return wb;
	}
	
	public static void saveSession(Wiki wiki) throws FileNotFoundException, IOException {
		User currentUser = wiki.getCurrentUser();
		
		if (currentUser == null) {
			throw new UnsupportedOperationException("Cannot save session with no account logged in");
		}
		
		Users user = Users.findUser(currentUser.getUsername());
		
		if (user == null) {
			throw new UnsupportedOperationException("Unrecognized user: " + currentUser.getUsername());
		}
		
		Misc.serialize(wiki, String.format(FILE_FORMAT, user.getAlias(), wiki.getDomain()));
		wiki.logout();
	}
	
	public static void main(String[] args) throws FileNotFoundException, IOException, FailedLoginException {
		retrieveCredentials();
		
		List<String> storedUsernames = Stream.of(Users.values())
			.map(Users::getUsername)
			.collect(Collectors.toList());
		
		if (
			credentials.isEmpty() ||
			credentials.size() != Users.values().length ||
			!credentials.keySet().containsAll(storedUsernames)
		) {
			System.out.printf("Credentials available: %s%n", credentials.keySet().toString());
			
			for (Users user : Users.values()) {
				storeCredentials(user);
			}
		}
		
		for (Users user : Users.values()) {
			for (Domains domain : Domains.values()) {
				saveSession(generateSession(domain, user));
			}
		}
		
		//Misc.serialize(credentials, LOCATION + "credentials.ser");
	}
}
