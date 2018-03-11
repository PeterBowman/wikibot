package com.github.wikibot.telegram;

import java.io.File;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.TelegramBotsApi;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import com.google.common.base.Strings;

public class SimpleMessageForwarderBot extends TelegramLongPollingBot {

	private String botUsername;
	private String botToken;
	private long chatId;
	private String lastSender;
	private BiConsumer<String, String> replyCallback;
	
	private static final Pattern P_CALLBACK_FMT;
	
	static {
		P_CALLBACK_FMT = Pattern.compile("^/r +(\\S+) +(.+)$", Pattern.CASE_INSENSITIVE);
	}

	public SimpleMessageForwarderBot(String botUsername, String botToken, long chatId) {
		this.botUsername = botUsername;
		this.botToken = botToken;
		this.chatId = chatId;
		this.lastSender = "";
		this.replyCallback = (a, b) -> {};
	}

	@Override
	public void onUpdateReceived(Update update) {
		if (!update.hasMessage() || update.getMessage().getChatId() != chatId) {
			return;
		}
		
		Message message = update.getMessage();
		final String replyPrompt = String.format("Keep talking to %s.", lastSender);
		
		if (message.isReply() && message.getReplyToMessage().getFrom().getUserName().equals(botUsername)) {
			if (message.hasText() && !Strings.isNullOrEmpty(lastSender)) {
				replyCallback.accept(lastSender, message.getText());
				
				try {
					execute(new SendMessage().setChatId(chatId).setText(replyPrompt).setReplyMarkup(new ForceReplyKeyboard()));
				} catch (TelegramApiException e) {
					e.printStackTrace();
				}
			}
		} else if (message.hasText() && message.getText().startsWith("/replylast")) {
			try {
				execute(new SendMessage().setChatId(chatId).setText(replyPrompt).setReplyMarkup(new ForceReplyKeyboard()));
			} catch (TelegramApiException e) {
				e.printStackTrace();
			}
		} else if (message.hasText() && message.getText().startsWith("/replyto")) {
			Matcher m = P_CALLBACK_FMT.matcher(message.getText().trim());
			
			if (m.matches()) {
				replyCallback.accept(m.group(1), m.group(2));
			} else {
				try {
					execute(new SendMessage().setChatId(chatId).setText("ERROR: unable to parse message"));
				} catch (TelegramApiException e) {
					e.printStackTrace();
				}
			}
		} else {
			SendMessage reply = new SendMessage().setChatId(message.getChatId());
			
			if (message.hasText()) {
				reply.setText(message.getText());
			} else {
				reply.setText("ping!");
			}
			
			try {
				execute(reply);
			} catch (TelegramApiException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public String getBotUsername() {
		return botUsername;
	}

	@Override
	public String getBotToken() {
		return botToken;
	}
	
	public void setReplyCallback(BiConsumer<String, String> replyCallback) {
		this.replyCallback = replyCallback;
	}

	public void notifyMaintainer(String sender, String messageText) throws TelegramApiException {
		String text = String.format("Message from %s: \"%s\"", sender, messageText);
		ForceReplyKeyboard replyKeyboard = new ForceReplyKeyboard();
		SendMessage message = new SendMessage().setChatId(chatId).setText(text).setReplyMarkup(replyKeyboard);
		lastSender = sender;
		execute(message);
	}
	
	public static void main(String[] args) throws Exception {
		final String location = "./data/sessions/";
		File fTelegramToken = new File(location + "telegram_token.txt");
		File fTelegramChatid = new File(location + "telegram_chatid.txt");

		String telegramToken = FileUtils.readFileToString(fTelegramToken, Charset.forName("UTF8"));
		String telegramChatid = FileUtils.readFileToString(fTelegramChatid, Charset.forName("UTF8"));
		
		ApiContextInitializer.init();
		TelegramBotsApi botsApi = new TelegramBotsApi();
		SimpleMessageForwarderBot bot = new SimpleMessageForwarderBot("PBbot", telegramToken, Long.parseLong(telegramChatid));
		botsApi.registerBot(bot);
	}
	
}
