package com.github.wikibot.irc;

import java.io.File;
import java.nio.charset.Charset;

import org.apache.commons.io.FileUtils;
import org.jibble.pircbot.PircBot;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import com.github.wikibot.telegram.SimpleMessageForwarderBot;

public class PircBotImplementation extends PircBot {
	private static final String LOCATION = "./data/sessions/";
	private static final int PORT = 6666;
	private static final String SERVER = "irc.freenode.net";
	private static final String PLWIKT_CHANNEL = "#wiktionary-pl";
	private static final int RECONNECT_DELAY_MILLIS = 30000;
	private static final String MAINTAINER_USERNAME = "Peter_Bowman";
	private static final String TELEGRAM_BOT_USERNAME = "PBtelegram_bot";
	
	private SimpleMessageForwarderBot telegramBot;
	
	private enum Messages {
		NOTIFY_ON_PING("%s: skontaktuj się ze mną (User:%s) za pośrednictwem bota, wysyłając prywatną wiadomość: /query %s <wiadomość>"),
		NOTIFY_ON_PM_SUCCESS("wysłano powiadomienie"),
		NOTIFY_ON_PM_FAILURE("%s: nie udało się wysłać powiadomienia, proszę o kontakt drogą mailową: https://pl.wiktionary.org/wiki/Specjalna:E-mail/%s");
		
		public final String str;
		
		private Messages(String str) {
			this.str = str;
		}
	}
	
	public PircBotImplementation(SimpleMessageForwarderBot telegramBot) {
		this.setName("PBbot");
		this.telegramBot = telegramBot;
	}
	
	@Override
	protected void onKick(String channel, String kickerNick, String kickerLogin, String kickerHostname,
			String recipientNick, String reason) {
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
	
	public static void main(String[] args) throws Exception {
		File fIrc = new File(LOCATION + "irc.txt");
		File fTelegramToken = new File(LOCATION + "telegram_token.txt");
		File fTelegramChatid = new File(LOCATION + "telegram_chatid.txt");

		String password = FileUtils.readFileToString(fIrc, Charset.forName("UTF8"));
		String telegramToken = FileUtils.readFileToString(fTelegramToken, Charset.forName("UTF8"));
		String telegramChatid = FileUtils.readFileToString(fTelegramChatid, Charset.forName("UTF8"));
		
		ApiContextInitializer.init();
		TelegramBotsApi botsApi = new TelegramBotsApi();
		SimpleMessageForwarderBot telegramBot =
				new SimpleMessageForwarderBot(TELEGRAM_BOT_USERNAME, telegramToken, Long.parseLong(telegramChatid));
		
		botsApi.registerBot(telegramBot);
		
		PircBotImplementation ircBot = new PircBotImplementation(telegramBot);
		
		ircBot.setVerbose(true);
		ircBot.connect(SERVER, PORT, password);
		ircBot.joinChannel(PLWIKT_CHANNEL);
	}

}
