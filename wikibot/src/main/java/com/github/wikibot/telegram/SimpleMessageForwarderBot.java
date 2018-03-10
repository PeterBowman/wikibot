package com.github.wikibot.telegram;

import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

public class SimpleMessageForwarderBot extends TelegramLongPollingBot {

	private String botUsername;
	private String botToken;
	private long chatId;

	public SimpleMessageForwarderBot(String botUsername, String botToken, long chatId) {
		this.botUsername = botUsername;
		this.botToken = botToken;
		this.chatId = chatId;
	}

	@Override
	public void onUpdateReceived(Update update) {
		if (update.hasMessage() && update.getMessage().getChatId() == chatId) {
			SendMessage reply = new SendMessage();
			reply.setChatId(update.getMessage().getChatId());
			
			if (update.getMessage().hasText()) {
				reply.setText(update.getMessage().getText());
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

	public void notifyMaintainer(String sender, String messageText) throws TelegramApiException {
		String text = String.format("Message from %s: \"%s\"", sender, messageText);
		SendMessage message = new SendMessage().setChatId(chatId).setText(text);
		execute(message);
	}

}
