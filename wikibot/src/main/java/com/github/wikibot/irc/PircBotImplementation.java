package com.github.wikibot.irc;

import java.io.File;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.jibble.pircbot.PircBot;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import org.telegram.telegrambots.exceptions.TelegramApiRequestException;

import com.github.wikibot.telegram.SimpleMessageForwarderBot;

public class PircBotImplementation extends PircBot {
	private static final String LOCATION = "./data/sessions/";
	private static final int PORT_NUMBER = 6666;
	private static final String SERVER = "irc.freenode.net";
	private static final String PLWIKT_CHANNEL = "#wiktionary-pl";
	private static final int RECONNECT_DELAY_MILLIS = 30000;
	private static final String MAINTAINER_USERNAME = "Peter_Bowman";
	private static final String TELEGRAM_BOT_USERNAME = "PBtelegram_bot";
	private static final String IRC_BOT_NAME = "PBbot";
	
	private SimpleMessageForwarderBot telegramBot;
	
	private enum Messages {
		NOTIFY_ON_PING("%s: skontaktuj się ze mną (User:%s) za pośrednictwem bota, wysyłając prywatną wiadomość: /query %s <wiadomość>"),
		NOTIFY_ON_PM_SUCCESS("wysłano powiadomienie"),
		NOTIFY_ON_PM_FAILURE("%s: nie udało się wysłać powiadomienia, proszę o ponowienie próby lub kontakt drogą mailową: https://pl.wiktionary.org/wiki/Specjalna:E-mail/%s");
		
		public final String str;
		
		private Messages(String str) {
			this.str = str;
		}
	}
	
	public PircBotImplementation(SimpleMessageForwarderBot telegramBot) {
		this.setName(IRC_BOT_NAME);
		this.setAutoNickChange(true);
		this.telegramBot = telegramBot;
	}
	
	@Override
	protected void onKick(String channel, String kickerNick, String kickerLogin, String kickerHostname,
			String recipientNick, String reason) {
		if (recipientNick.equalsIgnoreCase(getNick())) {
			try {
				String message = String.format("kicked by %s! Reason: ", kickerNick, reason);
				telegramBot.notifyMaintainer(getClass().getSimpleName(), message);
			} catch (TelegramApiException e) {
				e.printStackTrace();
			}
			
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
	protected void onAction(String sender, String login, String hostname, String target, String action) {
		if (action.contains("PBbot")) {
			String reply = String.format(Messages.NOTIFY_ON_PING.str, sender, MAINTAINER_USERNAME, getNick());
			sendNotice(sender, reply);
		}
	}
	
	@Override
	protected void onMessage(String channel, String sender, String login, String hostname, String message) {
		if (message.contains("PBbot")) {
			String reply = String.format(Messages.NOTIFY_ON_PING.str, sender, MAINTAINER_USERNAME, getNick());
			sendNotice(sender, reply);
		}
	}
	
	@Override
	protected void onPrivateMessage(String sender, String login, String hostname, String message) {
		try {
			telegramBot.notifyMaintainer(sender, message);
			sendNotice(sender, Messages.NOTIFY_ON_PM_SUCCESS.str);
		} catch (TelegramApiException e) {
			e.printStackTrace();
			String reply = String.format(Messages.NOTIFY_ON_PM_FAILURE.str, sender, MAINTAINER_USERNAME);
			sendNotice(sender, reply);
		}
	}
	
	@Override
	protected void onServerPing(String response) {
		super.onServerPing(response);
		
		if (!getNick().equals(getName())) {
			changeNick(getName());
		}
	}
	
	@Override
	protected void onNickChange(String oldNick, String login, String hostname, String newNick) {
		if (newNick.equals(getNick())) {
			identify(getPassword());
		}
	}
	
	@Override
	protected void onConnect() {
		if (!getNick().equals(getName())) {
			String line = String.format("MSG NickServ GHOST %s %s", getName(), getPassword());
			sendRawLine(line);
			changeNick(getName());
		}
	}
	
	public static void main(String[] args) throws Exception {
		File fIrc = new File(LOCATION + "irc.txt");
		File fTelegramToken = new File(LOCATION + "telegram_token.txt");
		File fTelegramChatid = new File(LOCATION + "telegram_chatid.txt");

		String password = FileUtils.readFileToString(fIrc, Charset.forName("UTF8")).trim();
		String telegramToken = FileUtils.readFileToString(fTelegramToken, Charset.forName("UTF8")).trim();
		String telegramChatid = FileUtils.readFileToString(fTelegramChatid, Charset.forName("UTF8")).trim();
		
		ApiContextInitializer.init();
		TelegramBotsApi botsApi = new TelegramBotsApi();
		SimpleMessageForwarderBot telegramBot =
				new SimpleMessageForwarderBot(TELEGRAM_BOT_USERNAME, telegramToken, Long.parseLong(telegramChatid));
		
		try {
			botsApi.registerBot(telegramBot);
		} catch (TelegramApiRequestException e) {
			e.printStackTrace();
			System.out.printf("Sleeping %d milliseconds, just in case...%n", RECONNECT_DELAY_MILLIS);
			Thread.sleep(RECONNECT_DELAY_MILLIS);
			throw e;
		}
		
		PircBotImplementation ircBot = new PircBotImplementation(telegramBot);
		
		ircBot.setVerbose(true);
		ircBot.connect(SERVER, PORT_NUMBER, password);
		ircBot.joinChannel(PLWIKT_CHANNEL);
		
		telegramBot.setReplyCallback(ircBot::sendMessage);
	}

}
