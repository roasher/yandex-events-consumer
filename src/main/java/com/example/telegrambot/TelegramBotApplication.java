package com.example.telegrambot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public class TelegramBotApplication {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotApplication.class);

    public static void main(String[] args) {
        ensureLogsDirectoryExists();
        SpringApplication.run(TelegramBotApplication.class, args);
    }

    private static void ensureLogsDirectoryExists() {
        try {
            Path logsDir = Path.of("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to create logs directory: " + e.getMessage());
        }
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Application is shutting down. Releasing database connections...");
        // Spring Boot will automatically close the DataSource and release database locks
    }
}

