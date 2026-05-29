package com.example.telegrambot.service;

import com.example.telegrambot.bot.TelegramBot;
import com.example.telegrambot.dto.Event;
import com.example.telegrambot.exception.RateLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    @Value("${events.poll.booking-delay-ms}")
    private long bookingDelayMs;

    @Value("${events.poll.rate-limit-retry-delay-ms}")
    private long rateLimitRetryDelayMs;

    @Value("${events.poll.rate-limit-retry-count}")
    private int rateLimitRetryCount;

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

    // Ordered poll targets (priority: first entry is tried first, sequentially)
    private List<PollTarget> pollTargets = new ArrayList<>();

    // Index of the current target in pollTargets (sequential booking)
    private volatile int currentTargetIndex = 0;

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
            pollTargets = PollTarget.parseList(namesToParse);
            logger.info("Initialized {} poll target(s) (sequential priority): {}", pollTargets.size(), pollTargets);
        } else {
            logger.warn("No event names configured for polling. " +
                "Set EVENTS_POLL_NAMES environment variable (comma-separated, e.g., 'EVENTS_POLL_NAMES=бег,другое событие' " +
                "or 'Плавание:3,Плавание:1,теннис' where :1-:7 is day of week Mon-Sun). " +
                "Note: When running from IntelliJ, set it in Run Configuration → Environment variables");
        }
    }

    /**
     * Включает опрос для указанного пользователя
     */
    public boolean startPolling(Long userId, Long chatId) {
        if (pollTargets.isEmpty()) {
            logger.warn("Cannot start polling: no event names configured");
            return false;
        }
        pollingEnabled = true;
        pollingUserId = userId;
        pollingChatId = chatId;
        currentTargetIndex = 0;
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

        if (currentTargetIndex >= pollTargets.size()) {
            logger.debug("All poll targets completed ({} total)", pollTargets.size());
            return;
        }

        try {
            List<Event> events = eventsService.getEvents(pollingUserId);
            if (events == null || events.isEmpty()) {
                logger.debug("No events found for polling");
                return;
            }

            PollTarget target = pollTargets.get(currentTargetIndex);
            List<Event> matchingEvents = events.stream()
                .filter(target::matchesEvent)
                .collect(Collectors.toList());

            if (matchingEvents.isEmpty()) {
                logger.debug("Poll target [{}] ({}/{}): no matching events in list yet",
                    target, currentTargetIndex + 1, pollTargets.size());
                return;
            }

            logger.info("Poll target [{}] ({}/{}): {} matching event(s): {}",
                target, currentTargetIndex + 1, pollTargets.size(), matchingEvents.size(),
                matchingEvents.stream()
                    .map(e -> String.format("%s (id=%s, haveFreeSeats=%s, freeSeats=%d)",
                        e.getTitle(), e.getId(), e.isHaveFreeSeats(), e.getFreeSeats()))
                    .collect(Collectors.joining("; ")));

            boolean anyFreeSeats = matchingEvents.stream().anyMatch(Event::isHaveFreeSeats);
            if (!anyFreeSeats) {
                logger.info("Poll target [{}]: all matching events have no free seats — target lost, advancing",
                    target);
                advanceToNextTarget("no free seats");
                return;
            }

            for (Event event : matchingEvents) {
                if (!event.isHaveFreeSeats()) {
                    continue;
                }
                BookingAttemptResult result = checkAndBookEvent(event);
                if (result == BookingAttemptResult.BOOKED || result == BookingAttemptResult.ALREADY_BOOKED) {
                    advanceToNextTarget(result == BookingAttemptResult.BOOKED ? "booked" : "already booked");
                    return;
                }
                if (result == BookingAttemptResult.RATE_LIMITED) {
                    return;
                }
            }
        } catch (Exception e) {
            logger.error("Error in event polling task", e);
        }
    }

    private void advanceToNextTarget(String reason) {
        if (currentTargetIndex >= pollTargets.size()) {
            return;
        }
        PollTarget completed = pollTargets.get(currentTargetIndex);
        currentTargetIndex++;
        if (currentTargetIndex < pollTargets.size()) {
            logger.info("Poll target [{}] done ({}). Next target: [{}] ({}/{})",
                completed, reason, pollTargets.get(currentTargetIndex),
                currentTargetIndex + 1, pollTargets.size());
        } else {
            logger.info("Poll target [{}] done ({}). All {} poll target(s) completed.",
                completed, reason, pollTargets.size());
        }
    }

    private enum BookingAttemptResult {
        BOOKED,
        ALREADY_BOOKED,
        SKIPPED,
        RATE_LIMITED,
        FAILED
    }

    /**
     * Проверяет событие и бронирует его, если оно доступно
     */
    private BookingAttemptResult checkAndBookEvent(Event event) {
        String eventId = event.getId();
        String eventTitle = event.getTitle();

        try {
            // Если событие уже было забронировано, пропускаем
            if (bookedEventIds.contains(eventId)) {
                logger.info("Skipping {} (id={}): already booked in this session", eventTitle, eventId);
                return BookingAttemptResult.ALREADY_BOOKED;
            }

            if (eventHoldService.isEventHeld(eventId)) {
                logger.info("Skipping {} (id={}): event is held", eventTitle, eventId);
                return BookingAttemptResult.SKIPPED;
            }

            if (!event.isHaveFreeSeats()) {
                logger.info("Skipping {} (id={}): no free seats (freeSeats={})", eventTitle, eventId, event.getFreeSeats());
                return BookingAttemptResult.SKIPPED;
            }

            String userCookie = userCookieService.getCookie(pollingUserId);
            if (userCookie == null || userCookie.isEmpty()) {
                logger.warn("No cookie found for user {}, cannot book event", pollingUserId);
                return BookingAttemptResult.FAILED;
            }

            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);

            // Проверяем, не зарегистрирован ли уже пользователь
            boolean isAlreadyBooked = bookingService.isUserBooked(eventId, userCookie, referer, DEFAULT_USER_AGENT);
            if (isAlreadyBooked) {
                logger.info("User {} is already booked for event {}", pollingUserId, eventTitle);
                bookedEventIds.add(eventId);
                return BookingAttemptResult.ALREADY_BOOKED;
            }

            // Пытаемся забронировать событие
            logger.info("Attempting to book event: {} (ID: {}, haveFreeSeats={}, freeSeats={})", 
                eventTitle, eventId, event.isHaveFreeSeats(), event.getFreeSeats());

            // Получаем доступные слоты
            JsonNode slotsJson = bookingService.getTimeSlots(eventId, userCookie, referer, DEFAULT_USER_AGENT);
            Long slotId = extractFirstSlotId(slotsJson);

            if (slotId == null || slotId <= 0) {
                logger.warn("No available slots for event {}", eventTitle);
                return BookingAttemptResult.FAILED;
            }

            // Бронируем событие с retry при 429
            JsonNode response = null;
            int maxRetries = rateLimitRetryCount;
            boolean infiniteRetries = maxRetries < 0;
            int attempt = 1;

            while (infiniteRetries || attempt <= maxRetries) {
                try {
                    response = bookingService.book(
                        userCookie,
                        referer,
                        DEFAULT_USER_AGENT,
                        new YandexEventsBookingService.BookingRequest(slotId, 0, 0)
                    );
                    break;
                } catch (RateLimitException e) {
                    if (!infiniteRetries && attempt >= maxRetries) {
                        logger.warn("Rate limited (429) for event {} after {} retries, skipping for this cycle",
                            eventTitle, maxRetries);
                        throw e;
                    }
                    if (infiniteRetries) {
                        logger.warn("Rate limited (429) for event {}, waiting {} ms before retry {} (no limit)",
                            eventTitle, rateLimitRetryDelayMs, attempt);
                    } else {
                        logger.warn("Rate limited (429) for event {}, waiting {} ms before retry {}/{}",
                            eventTitle, rateLimitRetryDelayMs, attempt, maxRetries);
                    }
                    Thread.sleep(rateLimitRetryDelayMs);
                    attempt++;
                }
            }

            if (response == null) {
                return BookingAttemptResult.FAILED;
            }

            boolean registrationSuccessful = response.has("startDatetime") && response.get("startDatetime").asText() != null;

            if (registrationSuccessful) {
                logger.info("Successfully booked event: {} (ID: {})", eventTitle, eventId);
                bookedEventIds.add(eventId);
                sendBookingNotification(eventTitle, eventId);

                if (bookingDelayMs > 0) {
                    Thread.sleep(bookingDelayMs);
                }
                return BookingAttemptResult.BOOKED;
            }

            logger.warn("Failed to book event: {} (ID: {}). Response: {}", eventTitle, eventId, response);
            return BookingAttemptResult.FAILED;
        } catch (RateLimitException e) {
            logger.warn("Rate limited for event {}, will retry current poll target: {}", eventTitle, e.getMessage());
            return BookingAttemptResult.RATE_LIMITED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for rate limit retry or booking delay");
            return BookingAttemptResult.FAILED;
        } catch (Exception e) {
            logger.error("Error checking/booking event {}: {}", eventTitle, e.getMessage(), e);
            return BookingAttemptResult.FAILED;
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

