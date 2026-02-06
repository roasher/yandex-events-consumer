package com.example.telegrambot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service that validates all environment variables and cookie on startup when running in server-only mode
 * If any required variable is missing or invalid, the server will crash
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

    @Value("${events.poll.names:}")
    private String pollEventNamesString;

    @Value("${events.poll.start-time:}")
    private String pollStartTimeString;

    @Value("${events.poll.end-time:}")
    private String pollEndTimeString;

    @Value("${events.default.city:}")
    private String defaultCityString;

    @Value("${events.default.categories:}")
    private String defaultCategoriesString;

    @Value("${events.hold.links:}")
    private String holdLinksString;

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
        logger.info("Validating all environment variables...");

        // Validate all environment variables
        validateAllEnvironmentVariables();

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
     * Validates all environment variables at startup
     * Exits the application if any required variable is missing or invalid
     */
    private void validateAllEnvironmentVariables() {
        boolean hasErrors = false;

        // Validate required: EVENTS_API_COOKIES
        if (apiCookies == null || apiCookies.trim().isEmpty() || apiCookies.trim().equals("your_cookies_here")) {
            logger.error("❌ EVENTS_API_COOKIES is not set or contains placeholder value.");
            logger.error("   Please set a valid cookie value in run-server-only.sh");
            hasErrors = true;
        } else {
            logger.info("✅ EVENTS_API_COOKIES is set (length: {} chars)", apiCookies.length());
        }

        // Validate required: EVENTS_POLL_NAMES
        String pollNames = pollEventNamesString != null ? pollEventNamesString.trim() : "";
        // Also check environment variable as fallback
        if (pollNames.isEmpty()) {
            pollNames = System.getenv("EVENTS_POLL_NAMES");
            if (pollNames != null) {
                pollNames = pollNames.trim();
            }
        }
        
        if (pollNames == null || pollNames.isEmpty() || pollNames.equals("event_name_1,event_name_2")) {
            logger.error("❌ EVENTS_POLL_NAMES is not set or contains placeholder value.");
            logger.error("   Please set event names to watch (comma-separated) in run-server-only.sh");
            hasErrors = true;
        } else {
            String[] names = pollNames.split(",");
            int validNames = (int) Arrays.stream(names)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
            if (validNames == 0) {
                logger.error("❌ EVENTS_POLL_NAMES contains no valid event names.");
                hasErrors = true;
            } else {
                logger.info("✅ EVENTS_POLL_NAMES is set with {} event name(s): {}", validNames, pollNames);
            }
        }

        // Validate optional: EVENTS_POLL_START_TIME
        if (pollStartTimeString != null && !pollStartTimeString.trim().isEmpty()) {
            if (!validateTimeFormat(pollStartTimeString.trim())) {
                logger.error("❌ EVENTS_POLL_START_TIME has invalid format: '{}'. Expected format: HH:mm (e.g., 09:00)", pollStartTimeString);
                hasErrors = true;
            } else {
                logger.info("✅ EVENTS_POLL_START_TIME is set: {}", pollStartTimeString.trim());
            }
        } else {
            logger.info("ℹ️  EVENTS_POLL_START_TIME is not set (polling will start immediately)");
        }

        // Validate optional: EVENTS_POLL_END_TIME
        if (pollEndTimeString != null && !pollEndTimeString.trim().isEmpty()) {
            if (!validateTimeFormat(pollEndTimeString.trim())) {
                logger.error("❌ EVENTS_POLL_END_TIME has invalid format: '{}'. Expected format: HH:mm (e.g., 18:00)", pollEndTimeString);
                hasErrors = true;
            } else {
                logger.info("✅ EVENTS_POLL_END_TIME is set: {}", pollEndTimeString.trim());
            }
        } else {
            logger.info("ℹ️  EVENTS_POLL_END_TIME is not set (polling will run indefinitely)");
        }

        // Validate start/end time logic
        if (pollStartTimeString != null && !pollStartTimeString.trim().isEmpty() &&
            pollEndTimeString != null && !pollEndTimeString.trim().isEmpty()) {
            try {
                LocalTime startTime = LocalTime.parse(pollStartTimeString.trim(), DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime endTime = LocalTime.parse(pollEndTimeString.trim(), DateTimeFormatter.ofPattern("HH:mm"));
                
                // Check if end time is before start time (spanning midnight is allowed)
                if (endTime.isBefore(startTime)) {
                    logger.info("ℹ️  Polling window spans midnight: {} - {}", startTime, endTime);
                } else if (startTime.equals(endTime)) {
                    logger.warn("⚠️  EVENTS_POLL_START_TIME and EVENTS_POLL_END_TIME are the same: {}", startTime);
                } else {
                    logger.info("✅ Polling window: {} - {}", startTime, endTime);
                }
            } catch (DateTimeParseException e) {
                // Already validated above, but just in case
                logger.error("❌ Error parsing time values: {}", e.getMessage());
                hasErrors = true;
            }
        }

        // Validate optional: EVENTS_DEFAULT_CITY
        if (defaultCityString != null && !defaultCityString.trim().isEmpty()) {
            try {
                Integer cityId = Integer.parseInt(defaultCityString.trim());
                if (cityId <= 0) {
                    logger.error("❌ EVENTS_DEFAULT_CITY must be a positive integer. Got: {}", cityId);
                    hasErrors = true;
                } else {
                    logger.info("✅ EVENTS_DEFAULT_CITY is set: {}", cityId);
                }
            } catch (NumberFormatException e) {
                logger.error("❌ EVENTS_DEFAULT_CITY has invalid format: '{}'. Expected a positive integer.", defaultCityString);
                hasErrors = true;
            }
        } else {
            logger.info("ℹ️  EVENTS_DEFAULT_CITY is not set (will use default: city ID 1)");
        }

        // Validate optional: EVENTS_DEFAULT_CATEGORIES
        if (defaultCategoriesString != null && !defaultCategoriesString.trim().isEmpty()) {
            try {
                String[] categoryStrings = defaultCategoriesString.split(",");
                boolean hasInvalid = false;
                for (String catStr : categoryStrings) {
                    try {
                        int catId = Integer.parseInt(catStr.trim());
                        if (catId <= 0) {
                            logger.error("❌ EVENTS_DEFAULT_CATEGORIES contains invalid category ID: {}. Must be positive integer.", catId);
                            hasInvalid = true;
                        }
                    } catch (NumberFormatException e) {
                        logger.error("❌ EVENTS_DEFAULT_CATEGORIES contains invalid format: '{}'. Expected comma-separated integers.", catStr);
                        hasInvalid = true;
                    }
                }
                if (!hasInvalid) {
                    Set<Integer> categoryIds = Arrays.stream(categoryStrings)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .collect(Collectors.toSet());
                    logger.info("✅ EVENTS_DEFAULT_CATEGORIES is set: {}", categoryIds);
                } else {
                    hasErrors = true;
                }
            } catch (Exception e) {
                logger.error("❌ EVENTS_DEFAULT_CATEGORIES has invalid format: '{}'. Expected comma-separated integers.", defaultCategoriesString);
                hasErrors = true;
            }
        } else {
            logger.info("ℹ️  EVENTS_DEFAULT_CATEGORIES is not set (will use all categories)");
        }

        // Validate optional: EVENTS_HOLD_LINKS (just log, no strict validation needed)
        if (holdLinksString != null && !holdLinksString.trim().isEmpty()) {
            logger.info("✅ EVENTS_HOLD_LINKS is set: {}", holdLinksString);
        } else {
            logger.info("ℹ️  EVENTS_HOLD_LINKS is not set (no events will be held)");
        }

        // Exit if any validation errors
        if (hasErrors) {
            logger.error("");
            logger.error("═══════════════════════════════════════════════════════════════");
            logger.error("❌ ENVIRONMENT VARIABLE VALIDATION FAILED");
            logger.error("═══════════════════════════════════════════════════════════════");
            logger.error("Please fix the errors above and update run-server-only.sh with correct values.");
            logger.error("Server cannot start with invalid configuration. Exiting...");
            logger.error("═══════════════════════════════════════════════════════════════");
            System.exit(1);
        }

        logger.info("");
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("✅ ALL ENVIRONMENT VARIABLES VALIDATED SUCCESSFULLY");
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    /**
     * Validates time format (HH:mm)
     */
    private boolean validateTimeFormat(String timeString) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime.parse(timeString, formatter);
            return true;
        } catch (DateTimeParseException e) {
            return false;
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
