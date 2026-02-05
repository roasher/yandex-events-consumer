package com.example.telegrambot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PreDestroy;

@SpringBootApplication
@EnableScheduling
public class TelegramBotApplication {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TelegramBotApplication.class, args);
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Application is shutting down. Releasing database connections...");
        // Spring Boot will automatically close the DataSource and release database locks
    }
}

