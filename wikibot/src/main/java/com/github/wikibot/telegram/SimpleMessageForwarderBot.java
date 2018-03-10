package com.github.wikibot.telegram;

import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

public class SimpleMessageForwarderBot extends TelegramLongPollingBot {

	private String botUsername;
	private String botToken;
	private long chatId;
	
	private BiConsumer<String, String> replyCallback;
	
	private static final Pattern P_CALLBACK_FMT;
	
	static {
		P_CALLBACK_FMT = Pattern.compile("^/r +(\\S+) +(.+)$", Pattern.CASE_INSENSITIVE);
	}

	public SimpleMessageForwarderBot(String botUsername, String botToken, long chatId) {
		this.botUsername = botUsername;
		this.botToken = botToken;
		this.chatId = chatId;
		this.replyCallback = (a, b) -> {};
	}

	@Override
	public void onUpdateReceived(Update update) {
		if (!update.hasMessage() || update.getMessage().getChatId() != chatId) {
			return;
		}
		
		Message message = update.getMessage();
		
		if (message.hasText() && message.getText().startsWith("/r ")) {
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
			SendMessage reply = new SendMessage();
			reply.setChatId(message.getChatId());
			
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
		SendMessage message = new SendMessage().setChatId(chatId).setText(text);
		execute(message);
	}

}
