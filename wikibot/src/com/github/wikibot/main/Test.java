package com.github.wikibot.main;

import java.io.IOException;
import java.util.Scanner;

import javax.security.auth.login.LoginException;

public final class Test {
	public static void main(String[] args) throws IOException, LoginException {
		Scanner scanner = new Scanner(System.in);
		System.out.printf("1: ");
		String line = scanner.nextLine();
		System.out.printf("2: ");
		String blarg = scanner.nextLine();
		System.out.println(line);
		System.out.println(blarg);
		System.out.println("");
		scanner.close();
	}
}
