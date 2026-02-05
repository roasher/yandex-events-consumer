package com.example.telegrambot.service;

import com.example.telegrambot.bot.TelegramBot;
import com.example.telegrambot.dto.Category;
import com.example.telegrambot.dto.Event;
import com.example.telegrambot.entity.NextWeekSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@org.springframework.context.annotation.Profile("!server-only")
public class NextWeekMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(NextWeekMonitoringService.class);
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36";
    
    // Track which subscriptions have been processed to avoid duplicate bookings
    private final Set<Long> processedSubscriptions = new HashSet<>();
    
    private final NextWeekSubscriptionService subscriptionService;
    private final EventsService eventsService;
    private final YandexEventsBookingService bookingService;
    private final UserCookieService userCookieService;
    private final ApplicationContext applicationContext;

    public NextWeekMonitoringService(
            NextWeekSubscriptionService subscriptionService,
            EventsService eventsService,
            YandexEventsBookingService bookingService,
            UserCookieService userCookieService,
            ApplicationContext applicationContext) {
        this.subscriptionService = subscriptionService;
        this.eventsService = eventsService;
        this.bookingService = bookingService;
        this.userCookieService = userCookieService;
        this.applicationContext = applicationContext;
    }

    private TelegramBot getTelegramBot() {
        return applicationContext.getBean(TelegramBot.class);
    }

    /**
     * Checks for similar events next week every 5 seconds
     */
    @Scheduled(fixedRate = 5000)
    public void checkNextWeekEvents() {
        try {
            List<NextWeekSubscription> subscriptions = subscriptionService.getAllActiveSubscriptions();
            
            for (NextWeekSubscription subscription : subscriptions) {
                // Skip if already processed (will be cleared when subscription is deactivated)
                if (processedSubscriptions.contains(subscription.getId())) {
                    continue;
                }
                
                checkSubscription(subscription);
            }
        } catch (Exception e) {
            logger.error("Error in next week monitoring task", e);
        }
    }

    /**
     * Checks if there's a similar event available for a subscription
     */
    private void checkSubscription(NextWeekSubscription subscription) {
        try {
            Long userId = subscription.getUserId();
            String userCookie = userCookieService.getCookie(userId);
            
            if (userCookie == null || userCookie.isEmpty()) {
                logger.debug("User {} has no cookie, skipping subscription {}", userId, subscription.getId());
                return;
            }

            // Get current events for the user
            List<Event> currentEvents = eventsService.getEvents(userId);
            
            // Find similar events
            List<Event> similarEvents = findSimilarEvents(subscription, currentEvents);
            
            if (!similarEvents.isEmpty()) {
                // Found similar event - try to book it
                Event targetEvent = similarEvents.get(0); // Take first match
                logger.info("Found similar event {} for subscription {}", targetEvent.getId(), subscription.getId());
                
                boolean booked = tryBookEvent(subscription, targetEvent, userCookie);
                
                if (booked) {
                    // Mark subscription as processed and deactivate
                    processedSubscriptions.add(subscription.getId());
                    subscriptionService.deactivateSubscription(subscription.getId());
                    
                    // Notify user
                    String message = String.format(
                        "✅ Автоматически записал вас на событие на следующей неделе!\n\n" +
                        "Событие: *%s*\n\n" +
                        "Это похожее событие на то, на которое вы подписались.",
                        escapeMarkdown(targetEvent.getTitle())
                    );
                    getTelegramBot().sendMessageWithMarkdown(subscription.getChatId(), message);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking subscription {}", subscription.getId(), e);
        }
    }

    /**
     * Finds events similar to the subscription
     */
    private List<Event> findSimilarEvents(NextWeekSubscription subscription, List<Event> events) {
        List<Event> similar = new ArrayList<>();
        
        for (Event event : events) {
            if (isSimilarEvent(subscription, event)) {
                similar.add(event);
            }
        }
        
        return similar;
    }

    /**
     * Checks if an event is similar to the subscription
     * Matches by: title, category, city, and time pattern (same day/time next week)
     */
    private boolean isSimilarEvent(NextWeekSubscription subscription, Event event) {
        // Check title similarity (exact match or very similar)
        String subscriptionTitle = subscription.getEventTitle().toLowerCase().trim();
        String eventTitle = event.getTitle() != null ? event.getTitle().toLowerCase().trim() : "";
        
        // Title should be very similar (exact match or contains key words)
        if (!subscriptionTitle.equals(eventTitle) && 
            !eventTitle.contains(subscriptionTitle) && 
            !subscriptionTitle.contains(eventTitle)) {
            // Try fuzzy match - check if at least 70% of words match
            String[] subWords = subscriptionTitle.split("\\s+");
            String[] eventWords = eventTitle.split("\\s+");
            int matchingWords = 0;
            for (String subWord : subWords) {
                if (subWord.length() > 3) { // Only check words longer than 3 chars
                    for (String eventWord : eventWords) {
                        if (eventWord.contains(subWord) || subWord.contains(eventWord)) {
                            matchingWords++;
                            break;
                        }
                    }
                }
            }
            double similarity = subWords.length > 0 ? (double) matchingWords / subWords.length : 0;
            if (similarity < 0.5) {
                return false;
            }
        }
        
        // Check city
        if (subscription.getEventCityId() != null && event.getCity() != null) {
            if (!subscription.getEventCityId().equals(event.getCity().getId())) {
                return false;
            }
        }
        
        // Check categories (at least one category should match)
        if (subscription.getEventCategoryIds() != null && !subscription.getEventCategoryIds().isEmpty()) {
            Set<Integer> subCategoryIds = parseCategoryIds(subscription.getEventCategoryIds());
            if (event.getCategory() != null && !event.getCategory().isEmpty()) {
                Set<Integer> eventCategoryIds = event.getCategory().stream()
                    .map(Category::getId)
                    .collect(Collectors.toSet());
                
                // Check if there's at least one matching category
                boolean hasMatchingCategory = subCategoryIds.stream()
                    .anyMatch(eventCategoryIds::contains);
                
                if (!hasMatchingCategory && !subCategoryIds.isEmpty()) {
                    return false;
                }
            }
        }
        
        // Check time pattern - should be approximately same time next week
        if (subscription.getOriginalEventDate() != null && !subscription.getOriginalEventDate().isEmpty() &&
            event.getDatesOf() != null && !event.getDatesOf().isEmpty()) {
            
            try {
                // Parse original event date
                String originalDateStr = subscription.getOriginalEventDate();
                ZonedDateTime originalDate = parseDate(originalDateStr);
                
                if (originalDate != null) {
                    // Check if any event date is approximately same time next week (within 7-10 days)
                    for (String eventDateStr : event.getDatesOf()) {
                        ZonedDateTime eventDate = parseDate(eventDateStr);
                        if (eventDate != null) {
                            long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(originalDate, eventDate);
                            // Should be between 6-10 days later (approximately next week)
                            if (daysDiff >= 6 && daysDiff <= 10) {
                                // Check if time is similar (within 2 hours)
                                int originalHour = originalDate.getHour();
                                int eventHour = eventDate.getHour();
                                if (Math.abs(originalHour - eventHour) <= 2) {
                                    return true; // Good match
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error comparing dates: {}", e.getMessage());
            }
        }
        
        // If we got here and title matches, consider it similar
        return true;
    }

    /**
     * Parses comma-separated category IDs
     */
    private Set<Integer> parseCategoryIds(String categoryIdsStr) {
        Set<Integer> ids = new HashSet<>();
        if (categoryIdsStr == null || categoryIdsStr.isEmpty()) {
            return ids;
        }
        
        String[] parts = categoryIdsStr.split(",");
        for (String part : parts) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                logger.debug("Failed to parse category ID: {}", part);
            }
        }
        
        return ids;
    }

    /**
     * Parses ISO 8601 date string
     */
    private ZonedDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        
        try {
            if (dateStr.endsWith("Z")) {
                return ZonedDateTime.parse(dateStr);
            } else {
                LocalDateTime localDateTime = LocalDateTime.parse(dateStr.replace("Z", ""));
                return localDateTime.atZone(ZoneId.of("Europe/Moscow"));
            }
        } catch (Exception e) {
            logger.debug("Failed to parse date: {}", dateStr);
            return null;
        }
    }

    /**
     * Tries to book an event for a subscription
     */
    private boolean tryBookEvent(NextWeekSubscription subscription, Event event, String userCookie) {
        try {
            String eventId = event.getId();
            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);
            
            // Check if event has free seats
            if (!event.isHaveFreeSeats()) {
                logger.debug("Event {} has no free seats", eventId);
                return false;
            }
            
            // Get available slots
            JsonNode slotsJson = bookingService.getTimeSlots(
                eventId,
                userCookie,
                referer,
                DEFAULT_USER_AGENT
            );
            
            // Extract first slot
            Long slotId = extractFirstSlotId(slotsJson);
            if (slotId == null || slotId <= 0) {
                logger.debug("No available slots for event {}", eventId);
                return false;
            }
            
            // Try to book
            JsonNode response = bookingService.book(
                userCookie,
                referer,
                DEFAULT_USER_AGENT,
                new YandexEventsBookingService.BookingRequest(slotId, 0, 0)
            );
            
            // Check if booking was successful
            boolean success = response.has("startDatetime") && 
                response.get("startDatetime").asText() != null;
            
            if (success) {
                logger.info("Successfully auto-booked event {} for subscription {}", eventId, subscription.getId());
            } else {
                logger.warn("Failed to auto-book event {} for subscription {}", eventId, subscription.getId());
            }
            
            return success;
        } catch (Exception e) {
            logger.error("Error trying to book event for subscription {}", subscription.getId(), e);
            return false;
        }
    }

    /**
     * Extracts first available slot ID from JSON
     */
    private Long extractFirstSlotId(JsonNode slotsJson) {
        if (slotsJson == null) {
            return null;
        }
        // Similar to WaitlistMonitoringService
        if (slotsJson.isArray() && slotsJson.size() > 0) {
            JsonNode first = slotsJson.get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        if (slotsJson.has("result") && slotsJson.get("result").isArray() && 
            slotsJson.get("result").size() > 0) {
            JsonNode first = slotsJson.get("result").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        if (slotsJson.has("timeSlots") && slotsJson.get("timeSlots").isArray() && 
            slotsJson.get("timeSlots").size() > 0) {
            JsonNode first = slotsJson.get("timeSlots").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        return null;
    }

    /**
     * Escapes Markdown special characters
     */
    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]");
    }
}

