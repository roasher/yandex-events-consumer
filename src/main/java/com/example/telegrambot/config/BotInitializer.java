package com.example.telegrambot.config;

import com.example.telegrambot.bot.TelegramBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
@Profile("!server-only")
public class BotInitializer {

    private static final Logger logger = LoggerFactory.getLogger(BotInitializer.class);
    private final TelegramBot telegramBot;

    public BotInitializer(TelegramBot telegramBot) {
        this.telegramBot = telegramBot;
    }

    @EventListener({ContextRefreshedEvent.class})
    public void init() {
        try {
            // Clear any existing webhook before registering bot
            telegramBot.clearWebhook();
            
            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(telegramBot);
            logger.info("Telegram bot successfully registered");
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("terminated by other getUpdates request")) {
                logger.error("Another bot instance is already running. Please stop other instances first.", e);
            } else {
                logger.error("Failed to register Telegram bot", e);
            }
            throw new RuntimeException("Failed to register Telegram bot", e);
        }
    }
}

