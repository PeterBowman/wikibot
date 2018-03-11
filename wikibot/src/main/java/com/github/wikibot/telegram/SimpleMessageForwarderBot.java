package com.github.wikibot.telegram;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
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
import org.telegram.telegrambots.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;

import com.google.common.base.Strings;

public class SimpleMessageForwarderBot extends TelegramLongPollingBot {

	private String botUsername;
	private String botToken;
	private long chatId;
	private String lastSender;
	private BiConsumer<String, String> replyCallback;
	private Set<String> lastSenders;
	
	private static final int SENDER_HISTORY_SIZE = 5;
	
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
		this.lastSenders = new TreeSet<>();
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
					execute(new SendMessage(chatId, replyPrompt).setReplyMarkup(new ForceReplyKeyboard()));
				} catch (TelegramApiException e) {
					e.printStackTrace();
				}
			}
		} else if (message.hasText() && message.getText().startsWith("/replylast")) {
			try {
				execute(new SendMessage(chatId, replyPrompt).setReplyMarkup(new ForceReplyKeyboard()));
			} catch (TelegramApiException e) {
				e.printStackTrace();
			}
		} else if (message.hasText() && message.getText().startsWith("/replyto")) {
			Matcher m = P_CALLBACK_FMT.matcher(message.getText().trim());
			
			if (m.matches()) {
				String sender = m.group(1);
				String text = m.group(2);
				replyCallback.accept(sender, text);
				updateSenderHistory(sender);
			} else {
				if (!lastSenders.isEmpty()) {
					SendMessage prompt = new SendMessage(chatId, "Select IRC nick of message recipient.");
					ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
					List<KeyboardRow> keyboard = new ArrayList<>();
					
					for (String sender : lastSenders) {
						KeyboardRow row = new KeyboardRow();
						row.add(sender);
						keyboard.add(row);
					}
					
					keyboardMarkup.setKeyboard(keyboard).setResizeKeyboard(true).setOneTimeKeyboard(true);
					prompt.setReplyMarkup(keyboardMarkup);
					
					try {
						execute(prompt);
					} catch (TelegramApiException e) {
						e.printStackTrace();
					}
				} else {
					try {
						execute(new SendMessage(chatId, "No senders in history, try: /replyto <nick> <msg>"));
					} catch (TelegramApiException e) {
						e.printStackTrace();
					}
				}
			}
		} else if (message.hasText() && message.getText().startsWith("/clearsenders")) {
			lastSenders.clear();
			
			try {
				execute(new SendMessage(chatId, "Cleared!"));
			} catch (TelegramApiException e) {
				e.printStackTrace();
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
		execute(new SendMessage(chatId, text).setReplyMarkup(new ForceReplyKeyboard()));
		updateSenderHistory(sender);
	}
	
	private void updateSenderHistory(String sender) {
		lastSender = sender;
		
		if (lastSenders.size() >= SENDER_HISTORY_SIZE) {
			List<String> list = new ArrayList<>(lastSenders);
			Collections.shuffle(list, new Random());
			list = list.subList(0, SENDER_HISTORY_SIZE - 1);
			lastSenders.retainAll(list);
		}
		
		lastSenders.add(sender);
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
