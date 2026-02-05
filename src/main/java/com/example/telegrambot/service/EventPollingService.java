package com.example.telegrambot.service;

import com.example.telegrambot.bot.TelegramBot;
import com.example.telegrambot.dto.Event;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class EventPollingService {

    private static final Logger logger = LoggerFactory.getLogger(EventPollingService.class);
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36";

    // Флаг для включения/выключения опроса
    private volatile boolean pollingEnabled = false;

    // ID пользователя, для которого включен опрос
    private volatile Long pollingUserId = null;

    // ChatId пользователя для отправки уведомлений
    private volatile Long pollingChatId = null;

    // Список имен событий для отслеживания (из переменной окружения)
    // Используем String и парсим вручную, чтобы поддерживать и YAML массивы, и comma-separated env vars
    @Value("${events.poll.names:}")
    private String pollEventNamesString;
    
    // Прямое чтение из System.getenv() как fallback (для случаев когда Spring Boot не видит env var)
    private String getPollEventNamesFromEnv() {
        // Пробуем разные варианты имени переменной
        String value = System.getenv("EVENTS_POLL_NAMES");
        logger.debug("System.getenv('EVENTS_POLL_NAMES') = '{}'", value);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv("events_poll_names");
            logger.debug("System.getenv('events_poll_names') = '{}'", value);
        }
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv("events.poll.names");
            logger.debug("System.getenv('events.poll.names') = '{}'", value);
        }
        return value;
    }

    // Множество имен событий для отслеживания
    private Set<String> watchedEventNames = new HashSet<>();

    // Множество eventId, которые уже были забронированы (чтобы не пытаться повторно)
    private final Set<String> bookedEventIds = ConcurrentHashMap.newKeySet();

    private final EventsService eventsService;
    private final YandexEventsBookingService bookingService;
    private final UserCookieService userCookieService;
    private final EventHoldService eventHoldService;
    private final UserPreferencesService userPreferencesService;
    private final ApplicationContext applicationContext;

    public EventPollingService(
            EventsService eventsService,
            YandexEventsBookingService bookingService,
            UserCookieService userCookieService,
            EventHoldService eventHoldService,
            UserPreferencesService userPreferencesService,
            ApplicationContext applicationContext) {
        this.eventsService = eventsService;
        this.bookingService = bookingService;
        this.userCookieService = userCookieService;
        this.eventHoldService = eventHoldService;
        this.userPreferencesService = userPreferencesService;
        this.applicationContext = applicationContext;
        initializeWatchedEventNames();
    }

    /**
     * Инициализирует список имен событий для отслеживания из переменной окружения
     * Имена сохраняются в нижнем регистре для case-insensitive сравнения
     * Поддерживает как YAML массивы (Spring Boot конвертирует в comma-separated), так и comma-separated строки из env vars
     */
    private void initializeWatchedEventNames() {
        String namesToParse = null;
        
        // Логируем что получили из Spring Boot
        logger.debug("pollEventNamesString from @Value: '{}' (null: {}, empty: {})", 
            pollEventNamesString, 
            pollEventNamesString == null,
            pollEventNamesString != null && pollEventNamesString.trim().isEmpty());
        
        // Сначала пробуем получить из Spring Boot property
        if (pollEventNamesString != null && !pollEventNamesString.trim().isEmpty()) {
            namesToParse = pollEventNamesString;
            logger.info("Found event names from Spring property: {}", namesToParse);
        } else {
            // Если Spring Boot property пустое, пробуем прочитать напрямую из System.getenv()
            logger.debug("Spring property is empty, trying System.getenv()...");
            String envValue = getPollEventNamesFromEnv();
            logger.debug("System.getenv() returned: '{}'", envValue);
            if (envValue != null && !envValue.trim().isEmpty()) {
                namesToParse = envValue;
                logger.info("Found event names from System.getenv(): {}", namesToParse);
            }
        }
        
        if (namesToParse != null && !namesToParse.trim().isEmpty()) {
            // Разбиваем по запятой (работает и для YAML массивов, которые Spring Boot конвертирует в строку)
            String[] nameArray = namesToParse.split(",");
            for (String name : nameArray) {
                String trimmed = name != null ? name.trim() : "";
                if (!trimmed.isEmpty()) {
                    // Сохраняем в нижнем регистре для case-insensitive сравнения
                    watchedEventNames.add(trimmed.toLowerCase());
                }
            }
            logger.info("Initialized watched event names (case-insensitive): {}", watchedEventNames);
        } else {
            logger.warn("No event names configured for polling. " +
                "Set EVENTS_POLL_NAMES environment variable (comma-separated, e.g., 'EVENTS_POLL_NAMES=бег,другое событие'). " +
                "Note: When running from IntelliJ, set it in Run Configuration → Environment variables");
        }
    }

    /**
     * Включает опрос для указанного пользователя
     */
    public boolean startPolling(Long userId, Long chatId) {
        if (watchedEventNames.isEmpty()) {
            logger.warn("Cannot start polling: no event names configured");
            return false;
        }
        pollingEnabled = true;
        pollingUserId = userId;
        pollingChatId = chatId;
        bookedEventIds.clear(); // Очищаем список забронированных событий при старте
        logger.info("Event polling started for user {} (chatId: {})", userId, chatId);
        return true;
    }

    /**
     * Выключает опрос
     */
    public void stopPolling() {
        pollingEnabled = false;
        Long userId = pollingUserId;
        Long chatId = pollingChatId;
        pollingUserId = null;
        pollingChatId = null;
        logger.info("Event polling stopped for user {} (chatId: {})", userId, chatId);
    }

    /**
     * Проверяет, включен ли опрос
     */
    public boolean isPollingEnabled() {
        return pollingEnabled;
    }

    /**
     * Получает ID пользователя, для которого включен опрос
     */
    public Long getPollingUserId() {
        return pollingUserId;
    }

    /**
     * Проверяет события каждую секунду
     */
    @Scheduled(fixedRate = 1000)
    public void pollEvents() {
        if (!pollingEnabled || pollingUserId == null) {
            return;
        }

        try {
            // Получаем список событий для пользователя
            List<Event> events = eventsService.getEvents(pollingUserId);
            if (events == null || events.isEmpty()) {
                logger.debug("No events found for polling");
                return;
            }

            // Фильтруем события по именам из списка отслеживания (case-insensitive, substring matching)
            List<Event> watchedEvents = events.stream()
                .filter(event -> {
                    String eventTitle = event.getTitle();
                    if (eventTitle == null) {
                        return false;
                    }
                    String eventTitleLower = eventTitle.toLowerCase();
                    // Проверяем, содержит ли название события любое из отслеживаемых имен (substring matching)
                    return watchedEventNames.stream()
                        .anyMatch(watchedName -> eventTitleLower.contains(watchedName));
                })
                .collect(Collectors.toList());

            if (watchedEvents.isEmpty()) {
                logger.debug("No watched events found in current events list");
                return;
            }

            // Проверяем каждое отслеживаемое событие
            for (Event event : watchedEvents) {
                checkAndBookEvent(event);
            }
        } catch (Exception e) {
            logger.error("Error in event polling task", e);
        }
    }

    /**
     * Проверяет событие и бронирует его, если оно доступно
     */
    private void checkAndBookEvent(Event event) {
        String eventId = event.getId();
        String eventTitle = event.getTitle();

        try {
            // Если событие уже было забронировано, пропускаем
            if (bookedEventIds.contains(eventId)) {
                logger.debug("Event {} already booked, skipping", eventTitle);
                return;
            }

            // Если событие захолжено, не бронируем
            if (eventHoldService.isEventHeld(eventId)) {
                logger.debug("Event {} is held, skipping booking", eventTitle);
                return;
            }

            // Проверяем, есть ли свободные места
            if (!event.isHaveFreeSeats()) {
                logger.debug("Event {} has no free seats, skipping", eventTitle);
                return;
            }

            // Проверяем, не зарегистрирован ли уже пользователь
            String userCookie = userCookieService.getCookie(pollingUserId);
            if (userCookie == null || userCookie.isEmpty()) {
                logger.warn("No cookie found for user {}, cannot book event", pollingUserId);
                return;
            }

            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);

            // Проверяем, не зарегистрирован ли уже пользователь
            boolean isAlreadyBooked = bookingService.isUserBooked(eventId, userCookie, referer, DEFAULT_USER_AGENT);
            if (isAlreadyBooked) {
                logger.info("User {} is already booked for event {}", pollingUserId, eventTitle);
                bookedEventIds.add(eventId);
                return;
            }

            // Пытаемся забронировать событие
            logger.info("Attempting to book event: {} (ID: {})", eventTitle, eventId);

            // Получаем доступные слоты
            JsonNode slotsJson = bookingService.getTimeSlots(eventId, userCookie, referer, DEFAULT_USER_AGENT);
            Long slotId = extractFirstSlotId(slotsJson);

            if (slotId == null || slotId <= 0) {
                logger.warn("No available slots for event {}", eventTitle);
                return;
            }

            // Бронируем событие
            var response = bookingService.book(
                userCookie,
                referer,
                DEFAULT_USER_AGENT,
                new YandexEventsBookingService.BookingRequest(slotId, 0, 0)
            );

            // Проверяем успешность регистрации
            boolean registrationSuccessful = response.has("startDatetime") && response.get("startDatetime").asText() != null;

            if (registrationSuccessful) {
                logger.info("Successfully booked event: {} (ID: {})", eventTitle, eventId);
                bookedEventIds.add(eventId);
                
                // Отправляем уведомление пользователю
                sendBookingNotification(eventTitle, eventId);
            } else {
                logger.warn("Failed to book event: {} (ID: {}). Response: {}", eventTitle, eventId, response);
            }
        } catch (Exception e) {
            logger.error("Error checking/booking event {}: {}", eventTitle, e.getMessage(), e);
        }
    }

    /**
     * Извлекает ID первого доступного слота из JSON ответа
     */
    private Long extractFirstSlotId(JsonNode slotsJson) {
        if (slotsJson == null) {
            return null;
        }
        // Вариант 1: массив слотов
        if (slotsJson.isArray() && slotsJson.size() > 0) {
            JsonNode first = slotsJson.get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        // Вариант 2: объект с полем result
        if (slotsJson.has("result") && slotsJson.get("result").isArray() && slotsJson.get("result").size() > 0) {
            JsonNode first = slotsJson.get("result").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        // Вариант 3: объект с полем timeSlots/timeslots
        if (slotsJson.has("timeSlots") && slotsJson.get("timeSlots").isArray() && slotsJson.get("timeSlots").size() > 0) {
            JsonNode first = slotsJson.get("timeSlots").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        if (slotsJson.has("timeslots") && slotsJson.get("timeslots").isArray() && slotsJson.get("timeslots").size() > 0) {
            JsonNode first = slotsJson.get("timeslots").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        return null;
    }

    /**
     * Отправляет уведомление пользователю о успешной бронировке события
     */
    private void sendBookingNotification(String eventTitle, String eventId) {
        if (pollingChatId == null) {
            logger.warn("Cannot send booking notification: chatId is null");
            return;
        }

        try {
            // Check if TelegramBot bean is available (not available in server-only mode)
            if (!applicationContext.containsBean("telegramBot")) {
                logger.info("TelegramBot not available (server-only mode). Event {} booked successfully: {}", 
                    eventTitle, eventId);
                return;
            }

            TelegramBot telegramBot = applicationContext.getBean(TelegramBot.class);
            String eventUrl = String.format("https://events.yandex-team.ru/events/%s", eventId);
            String message = String.format(
                "🎉 *Событие забронировано автоматически!*\n\n" +
                "*%s*\n\n" +
                "[Открыть событие](%s)",
                eventTitle.replace("*", "\\*").replace("_", "\\_"),
                eventUrl
            );
            telegramBot.sendMessageWithMarkdown(pollingChatId, message);
            logger.info("Sent booking notification to chatId {} for event {}", pollingChatId, eventTitle);
        } catch (Exception e) {
            logger.error("Error sending booking notification to chatId {}: {}", pollingChatId, e.getMessage(), e);
        }
    }
}

