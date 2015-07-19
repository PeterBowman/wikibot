package com.github.wikibot.utils;

import java.io.Console;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

import javax.security.auth.login.FailedLoginException;

import org.wikipedia.Wiki;
import org.wikipedia.Wiki.User;
import org.wikiutils.IOUtils;
import org.wikiutils.LoginUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.main.PLWikt;
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
		
		String userAgent = IOUtils.fileToString(new File(LOCATION + "useragent.txt"), "UTF8");
		LoginUtils.loginAndSetPrefs(wiki, user.getUsername(), credentials.get(user.getUsername()));
		
		wiki.setThrottle(5000);
		wiki.setMaxLag(5);
		wiki.setUserAgent(wiki.getUserAgent() + ", " + userAgent);
		
		boolean isBot = Arrays.asList(user.hasBot()).contains(Domains.findDomain(wiki.getDomain()));
		wiki.setAssertionMode(isBot ? Wiki.ASSERT_BOT : Wiki.ASSERT_USER);
		wiki.setMarkBot(isBot);
		
		wiki.getSiteInfo();
		
		System.out.println("Logged in as: " + wiki.getCurrentUser().getUsername());
	}
	
	private static void retrieveCredentials() throws FileNotFoundException, IOException {
		try {
			credentials = Misc.deserialize(LOCATION + "credentials.ser");
		} catch (ClassNotFoundException | IOException e) {
			System.out.println(e.getMessage());
			credentials = new HashMap<String, char[]>();
		}
	}
	
	private static void storeCredentials(Users user) throws FileNotFoundException, IOException {
		Console console = System.console();
		
		if (console != null) {
			console.printf("Username: %s%n", user.getUsername());
			char[] password = console.readPassword("Password: ");
			credentials.put(user.getUsername(), password);
		} else {
			System.out.println("Using standard console");
			@SuppressWarnings("resource")
			Scanner scanner = new Scanner(System.in);
			System.out.printf("Username: %s%n", user.getUsername());
			System.out.printf("Password: ");
			String password = scanner.nextLine();
			credentials.put(user.getUsername(), password.toCharArray());
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Wikibot> T generateSession(Domains domain, Users user) throws FailedLoginException, IOException {
		T wiki;
		
		switch (domain) {
			case PLWIKT:
				wiki = (T) new PLWikt();
				break;
			case ESWIKT:
				wiki = (T) new ESWikt();
				break;
			default:
				wiki = (T) new Wikibot(domain.getDomain());
				break;
		}
		
		login(wiki, user);
		
		return wiki;
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Wikibot> T retrieveSession(Domains domain, Users user) throws FailedLoginException, IOException {
		T wiki;
		
		try {
			wiki = (T) Misc.deserialize(String.format(FILE_FORMAT, user.getAlias(), domain.getDomain()));
			
			try {
				wiki.getSiteInfo();
			} catch (AssertionError e1) {
				System.out.println(e1.getMessage());
				wiki.logout();
				login(wiki, user);
			}
		} catch (ClassCastException | ClassNotFoundException | IOException e2) {
			System.out.println(e2.getMessage());
			wiki = generateSession(domain, user);
		}
		
		return wiki;
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
		
		if (
			credentials.isEmpty() ||
			credentials.size() != Users.values().length ||
			!credentials.keySet().containsAll(Arrays.asList(Users.values()))
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
