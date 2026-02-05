package com.example.telegrambot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Service that automatically starts and stops event polling at configured times
 * Only active in server-only mode
 */
@Service
@Profile("server-only")
public class ScheduledPollingService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledPollingService.class);

    private final EventPollingService eventPollingService;
    private volatile boolean pollingStarted = false;

    // Dummy user ID and chat ID for server-only mode
    private static final Long SERVER_USER_ID = ServerStartupService.SERVER_USER_ID;
    private static final Long SERVER_CHAT_ID = 0L;

    @Value("${events.poll.start-time:}")
    private String startTimeString;

    @Value("${events.poll.end-time:}")
    private String endTimeString;

    private LocalTime startTime;
    private LocalTime endTime;
    private boolean timesConfigured = false;

    public ScheduledPollingService(EventPollingService eventPollingService) {
        this.eventPollingService = eventPollingService;
    }

    @PostConstruct
    public void initialize() {
        // Parse times if configured
        if (startTimeString != null && !startTimeString.trim().isEmpty()) {
            try {
                startTime = parseTime(startTimeString.trim());
                timesConfigured = true;
                logger.info("Poll start time configured: {}", startTime);
            } catch (Exception e) {
                logger.error("Failed to parse start time: {}", e.getMessage());
            }
        }

        if (endTimeString != null && !endTimeString.trim().isEmpty()) {
            try {
                endTime = parseTime(endTimeString.trim());
                timesConfigured = true;
                logger.info("Poll end time configured: {}", endTime);
            } catch (Exception e) {
                logger.error("Failed to parse end time: {}", e.getMessage());
            }
        }

        // If times are not configured, start polling immediately
        if (!timesConfigured) {
            logger.info("No poll start/end times configured. Starting polling immediately...");
            startPolling();
        } else {
            // Check if we're within the time window and should start immediately
            checkAndStartIfWithinWindow();
        }
    }

    /**
     * Checks every minute if it's time to start or stop polling
     */
    @Scheduled(cron = "0 * * * * ?") // Every minute at second 0
    public void checkPollingSchedule() {
        if (!timesConfigured) {
            return; // Times not configured, polling already started or will be handled elsewhere
        }

        LocalTime currentTime = LocalTime.now();

        // Check if we should start polling
        if (!pollingStarted && startTime != null) {
            if (isTimeWithinWindow(currentTime, startTime, endTime)) {
                logger.info("Current time {} is within polling window ({} - {}). Starting event polling...", 
                    currentTime, startTime, endTime != null ? endTime : "no end");
                startPolling();
            } else if (currentTime.getHour() == startTime.getHour() && 
                      currentTime.getMinute() == startTime.getMinute()) {
                // Exact match with start time
                logger.info("Scheduled polling start time reached: {}. Starting event polling...", startTime);
                startPolling();
            }
        }

        // Check if we should stop polling
        if (pollingStarted && endTime != null) {
            if (currentTime.getHour() == endTime.getHour() && 
                currentTime.getMinute() == endTime.getMinute()) {
                logger.info("Scheduled polling end time reached: {}. Stopping event polling...", endTime);
                stopPolling();
            } else if (!isTimeWithinWindow(currentTime, startTime, endTime)) {
                // We've passed the end time
                logger.info("Current time {} is outside polling window ({} - {}). Stopping event polling...", 
                    currentTime, startTime, endTime);
                stopPolling();
            }
        }
    }

    /**
     * Checks if current time is within the polling window on startup
     */
    private void checkAndStartIfWithinWindow() {
        if (startTime == null) {
            return;
        }

        LocalTime currentTime = LocalTime.now();
        if (isTimeWithinWindow(currentTime, startTime, endTime)) {
            logger.info("Application started at {} which is within polling window ({} - {}). Starting polling immediately...", 
                currentTime, startTime, endTime != null ? endTime : "no end");
            startPolling();
        } else {
            logger.info("Application started at {}. Polling will start at {}", currentTime, startTime);
        }
    }

    /**
     * Checks if a given time is within the polling window
     * Handles cases where end time might be on the next day
     */
    private boolean isTimeWithinWindow(LocalTime currentTime, LocalTime windowStart, LocalTime windowEnd) {
        if (windowStart == null) {
            return false;
        }

        if (windowEnd == null) {
            // No end time, check if we've reached start time
            return !currentTime.isBefore(windowStart);
        }

        // If end time is before start time, window spans midnight
        if (windowEnd.isBefore(windowStart)) {
            // Window spans midnight: e.g., 22:00 - 06:00
            return !currentTime.isBefore(windowStart) || !currentTime.isAfter(windowEnd);
        } else {
            // Normal window: e.g., 09:00 - 18:00
            return !currentTime.isBefore(windowStart) && !currentTime.isAfter(windowEnd);
        }
    }

    /**
     * Starts polling immediately (can be called manually or by schedule)
     */
    public void startPolling() {
        if (pollingStarted) {
            logger.warn("Polling already started, skipping");
            return;
        }

        boolean started = eventPollingService.startPolling(SERVER_USER_ID, SERVER_CHAT_ID);
        if (started) {
            pollingStarted = true;
            logger.info("Event polling started successfully via scheduled service");
        } else {
            logger.error("Failed to start event polling via scheduled service. " +
                "Make sure EVENTS_POLL_NAMES is configured.");
        }
    }

    /**
     * Parses time string in HH:mm format
     */
    private LocalTime parseTime(String timeString) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            return LocalTime.parse(timeString, formatter);
        } catch (DateTimeParseException e) {
            logger.error("Invalid time format: {}. Expected format: HH:mm (e.g., 09:30 or 14:00)", timeString);
            throw new IllegalArgumentException("Invalid time format: " + timeString + ". Expected HH:mm", e);
        }
    }

    /**
     * Stops polling
     */
    public void stopPolling() {
        if (!pollingStarted) {
            logger.warn("Polling not started, cannot stop");
            return;
        }

        eventPollingService.stopPolling();
        pollingStarted = false;
        logger.info("Event polling stopped via scheduled service");
    }

    /**
     * Checks if polling has been started
     */
    public boolean isPollingStarted() {
        return pollingStarted;
    }
}
