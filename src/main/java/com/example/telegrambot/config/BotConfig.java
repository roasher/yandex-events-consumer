package com.example.telegrambot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BotConfig {
    
    @Value("${telegram.bot.token}")
    private String botToken;
    
    @Value("${telegram.bot.username}")
    private String botUsername;

    public String getBotToken() {
        if (botToken == null || botToken.isEmpty()) {
            throw new IllegalStateException("telegram.bot.token property is not set");
        }
        return botToken;
    }

    public String getBotUsername() {
        if (botUsername == null || botUsername.isEmpty()) {
            throw new IllegalStateException("telegram.bot.username property is not set");
        }
        return botUsername;
    }
}

