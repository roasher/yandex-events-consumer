package com.example.telegrambot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Configuration for server-only mode (without Telegram bot)
 * This configuration is active when spring.profiles.active includes 'server-only'
 */
@Configuration
@Profile("server-only")
public class ServerOnlyConfiguration {
    // This configuration class marks the server-only profile
    // Telegram bot components will be disabled via @Profile("!server-only")
}
