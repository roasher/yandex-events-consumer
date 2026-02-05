package com.example.telegrambot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service that validates cookie on startup when running in server-only mode
 * If cookie is invalid or outdated, the server will crash
 */
@Service
@Profile("server-only")
public class ServerStartupService implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ServerStartupService.class);

    private final EventsService eventsService;
    private final UserCookieService userCookieService;
    private final UserPreferencesService userPreferencesService;

    @Value("${events.api.cookies:}")
    private String apiCookies;

    @Value("${events.default.city:}")
    private String defaultCityString;

    @Value("${events.default.categories:}")
    private String defaultCategoriesString;

    // Dummy user ID for server-only mode
    public static final Long SERVER_USER_ID = 0L;

    public ServerStartupService(
            EventsService eventsService,
            UserCookieService userCookieService,
            UserPreferencesService userPreferencesService) {
        this.eventsService = eventsService;
        this.userCookieService = userCookieService;
        this.userPreferencesService = userPreferencesService;
    }

    @Override
    public void run(String... args) {
        logger.info("Starting server-only mode initialization...");

        // Validate cookie is provided
        if (apiCookies == null || apiCookies.trim().isEmpty()) {
            logger.error("EVENTS_API_COOKIES is not set or empty. Server cannot start without a valid cookie.");
            throw new IllegalStateException("EVENTS_API_COOKIES must be set for server-only mode");
        }

        // Store cookie for server user
        userCookieService.setCookie(SERVER_USER_ID, apiCookies);
        logger.info("Cookie stored for server user (length: {} chars)", apiCookies.length());

        // Set default preferences for server user
        setDefaultPreferences();

        // Validate cookie by fetching events
        logger.info("Validating cookie by fetching events...");
        try {
            eventsService.validateCookie(apiCookies);
            logger.info("Cookie validation successful!");
            logger.info("Server startup validation completed successfully.");
        } catch (Exception e) {
            logger.error("Cookie validation failed. Server cannot start with an invalid or expired cookie.", e);
            logger.error("Server cannot start without a valid cookie. Exiting...");
            System.exit(1);
        }
    }

    /**
     * Sets default preferences for server user from configuration
     */
    private void setDefaultPreferences() {
        // Set default city if provided
        if (defaultCityString != null && !defaultCityString.trim().isEmpty()) {
            try {
                Integer cityId = Integer.parseInt(defaultCityString.trim());
                userPreferencesService.setCity(SERVER_USER_ID, cityId);
                logger.info("Default city set for server user: {}", cityId);
            } catch (NumberFormatException e) {
                logger.warn("Invalid default city format: {}. Using default (city ID 1)", defaultCityString);
                userPreferencesService.setCity(SERVER_USER_ID, 1);
            }
        } else {
            // Use default city ID 1 (Moscow) if not specified
            userPreferencesService.setCity(SERVER_USER_ID, 1);
            logger.info("No default city specified, using default city ID 1");
        }

        // Set default categories if provided
        if (defaultCategoriesString != null && !defaultCategoriesString.trim().isEmpty()) {
            try {
                Set<Integer> categoryIds = Arrays.stream(defaultCategoriesString.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
                
                if (!categoryIds.isEmpty()) {
                    userPreferencesService.setCategories(SERVER_USER_ID, categoryIds);
                    logger.info("Default categories set for server user: {}", categoryIds);
                } else {
                    userPreferencesService.setCategories(SERVER_USER_ID, new HashSet<>());
                    logger.info("Empty categories list provided, using all categories");
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid default categories format: {}. Using all categories", defaultCategoriesString);
                userPreferencesService.setCategories(SERVER_USER_ID, new HashSet<>());
            }
        } else {
            // Use empty set to show all categories
            userPreferencesService.setCategories(SERVER_USER_ID, new HashSet<>());
            logger.info("No default categories specified, using all categories");
        }
    }
}
