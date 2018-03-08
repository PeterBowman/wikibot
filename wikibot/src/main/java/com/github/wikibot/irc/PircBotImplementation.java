package com.github.wikibot.irc;

import java.io.File;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.jibble.pircbot.PircBot;

public class PircBotImplementation extends PircBot {
	private static final String LOCATION = "./data/sessions/";
	private static final int PORT = 6666;
	private static final String SERVER = "irc.freenode.net";
	private static final String PLWIKT_CHANNEL = "#wiktionary-pl";
	private static final int RECONNECT_DELAY_MILLIS = 30000;
	
	public PircBotImplementation() {
		this.setName("PBbot");
	}
	
	@Override
	protected void onKick(String channel, String kickerNick, String kickerLogin, String kickerHostname, String recipientNick, String reason) {
		if (recipientNick.equalsIgnoreCase(getNick())) {
		    joinChannel(channel);
		}
	}
	
	@Override
	protected void onDisconnect() {
		while (!isConnected()) {
		    try {
		        reconnect();
		    } catch (Exception e) {
		        log("Couldn't reconnect...");
		        
		        try {
					Thread.sleep(RECONNECT_DELAY_MILLIS);
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
		    }
		}
	}
	
	@Override
	protected void onMessage(String channel, String sender, String login, String hostname, String message) {
		if (message.contains("PBbot")) {
			String reply = String.format("%s: what?", sender);
			sendMessage(channel, reply);
		}
	}
	
	@Override
	protected void onPrivateMessage(String sender, String login, String hostname, String message) {
		sendMessage(sender, "PM test");
	}
	
	public static void main(String[] args) throws Exception {
		PircBotImplementation bot = new PircBotImplementation();
		
		File f = new File(LOCATION + "irc.txt");
		String password = FileUtils.readFileToString(f, Charset.forName("UTF8"));
		
		bot.setVerbose(true);
		bot.connect(SERVER, PORT, password);
		bot.joinChannel(PLWIKT_CHANNEL);
	}

}
