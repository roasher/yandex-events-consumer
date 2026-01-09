package com.example.telegrambot.service;

import com.example.telegrambot.entity.WaitlistEntry;
import com.example.telegrambot.bot.TelegramBot;
import com.example.telegrambot.dto.Event;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WaitlistMonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(WaitlistMonitoringService.class);
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36";
    
    // Хранит информацию о предложенных слотах: eventId -> userId, кому предложено
    private final Map<String, Long> pendingOffers = new ConcurrentHashMap<>();
    
    // Хранит информацию о предложениях с временем создания для таймаута
    private final Map<String, Long> offerTimestamps = new ConcurrentHashMap<>();
    
    // Хранит информацию о том, кому уже было отправлено уведомление: eventId:userId -> true
    private final Map<String, Boolean> notifiedUsers = new ConcurrentHashMap<>();
    
    private static final long OFFER_TIMEOUT_MS = 60_000; // 1 минута таймаут на ответ

    private final WaitlistService waitlistService;
    private final YandexEventsBookingService bookingService;
    private final EventsService eventsService;
    private final UserCookieService userCookieService;
    private final EventHoldService eventHoldService;
    private final ApplicationContext applicationContext;

    public WaitlistMonitoringService(
            WaitlistService waitlistService,
            YandexEventsBookingService bookingService,
            EventsService eventsService,
            UserCookieService userCookieService,
            EventHoldService eventHoldService,
            ApplicationContext applicationContext) {
        this.waitlistService = waitlistService;
        this.bookingService = bookingService;
        this.eventsService = eventsService;
        this.userCookieService = userCookieService;
        this.eventHoldService = eventHoldService;
        this.applicationContext = applicationContext;
    }

    private TelegramBot getTelegramBot() {
        return applicationContext.getBean(TelegramBot.class);
    }

    /**
     * Проверяет события из листа ожидания каждые 5 секунд
     */
    @Scheduled(fixedRate = 5000)
    public void checkWaitlistEvents() {
        try {
            // Получаем все уникальные eventId из листа ожидания
            Set<String> eventIds = getAllEventIdsInWaitlist();
            
            for (String eventId : eventIds) {
                checkEventAvailability(eventId);
            }
            
            // Проверяем таймауты предложений
            checkOfferTimeouts();
        } catch (Exception e) {
            logger.error("Error in waitlist monitoring task", e);
        }
    }

    /**
     * Получает все уникальные eventId из листа ожидания
     */
    private Set<String> getAllEventIdsInWaitlist() {
        Set<String> eventIds = new HashSet<>();
        List<String> allEventIds = waitlistService.getAllEventIds();
        eventIds.addAll(allEventIds);
        return eventIds;
    }

    /**
     * Проверяет доступность слотов для события
     */
    private void checkEventAvailability(String eventId) {
        try {
            // Если событие захолжено, не проверяем доступность слотов
            if (eventHoldService.isEventHeld(eventId)) {
                logger.debug("Event {} is held, skipping availability check", eventId);
                return;
            }

            // Получаем список пользователей в листе ожидания (упорядоченный по позиции)
            List<WaitlistEntry> waitlist = waitlistService.getAllUsersInWaitlist(eventId);
            if (waitlist.isEmpty()) {
                return;
            }

            // Проверяем, есть ли активное pending offer для этого события
            Long pendingUserId = pendingOffers.get(eventId);
            if (pendingUserId != null) {
                // Проверяем, истек ли таймаут
                Long offerTimestamp = offerTimestamps.get(eventId);
                if (offerTimestamp != null && (System.currentTimeMillis() - offerTimestamp) > OFFER_TIMEOUT_MS) {
                    // Таймаут истек, очищаем предложение
                    logger.debug("Offer for event {} expired, clearing and moving to next user", eventId);
                    clearOffer(eventId);
                } else {
                    // Предложение еще активно, пропускаем
                    logger.debug("Event {} already has active pending offer, skipping", eventId);
                    return;
                }
            }

            // Находим первого пользователя, которому еще не было отправлено уведомление
            WaitlistEntry firstUser = null;
            for (WaitlistEntry entry : waitlist) {
                String notificationKey = eventId + ":" + entry.getUserId();
                if (!notifiedUsers.containsKey(notificationKey)) {
                    firstUser = entry;
                    break;
                }
            }
            
            // Если всем пользователям уже отправлено уведомление, пропускаем
            if (firstUser == null) {
                logger.debug("All users for event {} have been notified, skipping", eventId);
                return;
            }
            
            // Проверяем, есть ли у пользователя кука
            String userCookie = userCookieService.getCookie(firstUser.getUserId());
            if (userCookie == null || userCookie.isEmpty()) {
                logger.debug("User {} has no cookie, skipping event {}", firstUser.getUserId(), eventId);
                return;
            }

            // Получаем информацию о событии для формирования referer
            Event event = getEventById(eventId, firstUser.getUserId());
            if (event == null) {
                logger.warn("Could not find event {} for user {}", eventId, firstUser.getUserId());
                return;
            }

            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);

            // Проверяем доступность слотов через API
            JsonNode slotsJson = bookingService.getTimeSlots(
                eventId,
                userCookie,
                referer,
                DEFAULT_USER_AGENT
            );

            // Проверяем, есть ли доступные слоты
            Long slotId = extractFirstSlotId(slotsJson);
            if (slotId != null && slotId > 0) {
                // Есть доступный слот - предлагаем пользователю
                offerSlotToUser(eventId, firstUser, event, slotId);
            }
        } catch (Exception e) {
            logger.error("Error checking availability for event {}", eventId, e);
        }
    }

    /**
     * Предлагает слот пользователю
     */
    private void offerSlotToUser(String eventId, WaitlistEntry user, Event event, Long slotId) {
        try {
            String notificationKey = eventId + ":" + user.getUserId();
            
            // Проверяем, не было ли уже отправлено уведомление этому пользователю
            if (notifiedUsers.containsKey(notificationKey)) {
                logger.debug("User {} already notified for event {}, skipping", user.getUserId(), eventId);
                return;
            }
            
            // Сохраняем информацию о предложении
            pendingOffers.put(eventId, user.getUserId());
            offerTimestamps.put(eventId, System.currentTimeMillis());
            notifiedUsers.put(notificationKey, true);

            // Отправляем уведомление пользователю
            String eventTitle = event.getTitle() != null ? event.getTitle() : "событие";
            String message = String.format(
                "🎯 Доступен слот на событие!\n\n" +
                "Событие: *%s*\n\n" +
                "Вы первый в очереди. Хотите записаться на это событие?",
                escapeMarkdown(eventTitle)
            );

            getTelegramBot().sendSlotOfferNotification(user.getChatId(), user.getUserId(), eventId, eventTitle, message);
            
            logger.info("Offered slot for event {} to user {} (position 1)", eventId, user.getUserId());
        } catch (Exception e) {
            logger.error("Error offering slot to user {} for event {}", user.getUserId(), eventId, e);
            // Очищаем предложение при ошибке
            pendingOffers.remove(eventId);
            offerTimestamps.remove(eventId);
            String notificationKey = eventId + ":" + user.getUserId();
            notifiedUsers.remove(notificationKey);
        }
    }

    /**
     * Обрабатывает подтверждение участия пользователя
     */
    public boolean handleSlotConfirmation(String eventId, Long userId, Long chatId) {
        try {
            // Проверяем, что предложение действительно для этого пользователя
            Long offeredUserId = pendingOffers.get(eventId);
            if (offeredUserId == null || !offeredUserId.equals(userId)) {
                logger.warn("User {} tried to confirm offer for event {}, but offer is for user {}", 
                    userId, eventId, offeredUserId);
                return false;
            }

            // Получаем куку пользователя
            String userCookie = userCookieService.getCookie(userId);
            if (userCookie == null || userCookie.isEmpty()) {
                logger.warn("User {} has no cookie for booking event {}", userId, eventId);
                getTelegramBot().sendMessage(chatId, "❌ Кука не найдена. Пожалуйста, используйте /start для настройки.");
                return false;
            }

            // Получаем информацию о событии
            Event event = getEventById(eventId, userId);
            if (event == null) {
                logger.warn("Could not find event {} for user {}", eventId, userId);
                return false;
            }

            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);

            // Получаем доступные слоты
            JsonNode slotsJson = bookingService.getTimeSlots(
                eventId,
                userCookie,
                referer,
                DEFAULT_USER_AGENT
            );

            Long slotId = extractFirstSlotId(slotsJson);
            if (slotId == null || slotId <= 0) {
                logger.warn("No available slots for event {} at confirmation time", eventId);
                getTelegramBot().sendMessage(chatId, "❌ К сожалению, слот больше не доступен.");
                // Очищаем предложение и переходим к следующему
                clearOffer(eventId);
                return false;
            }

            // Выполняем регистрацию
            JsonNode response = bookingService.book(
                userCookie,
                referer,
                DEFAULT_USER_AGENT,
                new YandexEventsBookingService.BookingRequest(slotId, 0, 0)
            );

            // Проверяем успешность регистрации
            boolean registrationSuccessful = response.has("startDatetime") && 
                response.get("startDatetime").asText() != null;

            if (registrationSuccessful) {
                // Удаляем пользователя из листа ожидания
                waitlistService.removeFromWaitlist(eventId, userId);
                
                // Очищаем предложение
                clearOffer(eventId);

                // Отправляем подтверждение
                String eventTitle = event.getTitle() != null ? event.getTitle() : "событие";
                String eventUrl = eventsService.getEventUrl(event);
                String message = String.format(
                    "✅ Вы успешно записаны на [событие](%s) \"%s\"",
                    eventUrl,
                    eventTitle
                );
                getTelegramBot().sendMessageWithMarkdown(chatId, message);
                
                logger.info("User {} successfully booked event {}", userId, eventId);
                return true;
            } else {
                logger.warn("Booking failed for user {} on event {}", userId, eventId);
                String errorMessage = "❌ Не удалось выполнить регистрацию.";
                if (response.has("message") && response.get("message").asText() != null) {
                    errorMessage += " " + response.get("message").asText();
                }
                getTelegramBot().sendMessage(chatId, errorMessage);
                // Очищаем предложение и переходим к следующему
                clearOffer(eventId);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error handling slot confirmation for user {} on event {}", userId, eventId, e);
            getTelegramBot().sendMessage(chatId, "❌ Произошла ошибка при регистрации: " + 
                (e.getMessage() != null ? e.getMessage() : "ошибка"));
            // Очищаем предложение при ошибке
            clearOffer(eventId);
            return false;
        }
    }

    /**
     * Обрабатывает отказ пользователя от слота
     */
    public void handleSlotRejection(String eventId, Long userId, Long chatId) {
        try {
            // Проверяем, что предложение действительно для этого пользователя
            Long offeredUserId = pendingOffers.get(eventId);
            if (offeredUserId == null || !offeredUserId.equals(userId)) {
                logger.warn("User {} tried to reject offer for event {}, but offer is for user {}", 
                    userId, eventId, offeredUserId);
                return;
            }

            // Удаляем пользователя из листа ожидания
            waitlistService.removeFromWaitlist(eventId, userId);

            // Очищаем предложение
            clearOffer(eventId);

            // Отправляем подтверждение
            getTelegramBot().sendMessage(chatId, "✅ Вы отказались от предложения и вышли из листа ожидания.");
            
            logger.info("User {} rejected slot offer for event {}", userId, eventId);
        } catch (Exception e) {
            logger.error("Error handling slot rejection for user {} on event {}", userId, eventId, e);
        }
    }

    /**
     * Проверяет таймауты предложений
     */
    private void checkOfferTimeouts() {
        long currentTime = System.currentTimeMillis();
        List<String> expiredOffers = new ArrayList<>();
        
        for (Map.Entry<String, Long> entry : offerTimestamps.entrySet()) {
            String eventId = entry.getKey();
            long offerTime = entry.getValue();
            
            if (currentTime - offerTime > OFFER_TIMEOUT_MS) {
                expiredOffers.add(eventId);
            }
        }
        
        // Очищаем истекшие предложения
        for (String eventId : expiredOffers) {
            logger.info("Offer for event {} expired, clearing", eventId);
            clearOffer(eventId);
        }
    }

    /**
     * Очищает предложение для события
     */
    private void clearOffer(String eventId) {
        Long userId = pendingOffers.remove(eventId);
        offerTimestamps.remove(eventId);
        // Удаляем информацию об уведомлении для этого события и пользователя
        if (userId != null) {
            String notificationKey = eventId + ":" + userId;
            notifiedUsers.remove(notificationKey);
        }
    }

    /**
     * Извлекает ID первого доступного слота из JSON
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
        if (slotsJson.has("result") && slotsJson.get("result").isArray() && 
            slotsJson.get("result").size() > 0) {
            JsonNode first = slotsJson.get("result").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        // Вариант 3: объект с полем timeSlots/timeslots
        if (slotsJson.has("timeSlots") && slotsJson.get("timeSlots").isArray() && 
            slotsJson.get("timeSlots").size() > 0) {
            JsonNode first = slotsJson.get("timeSlots").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        if (slotsJson.has("timeslots") && slotsJson.get("timeslots").isArray() && 
            slotsJson.get("timeslots").size() > 0) {
            JsonNode first = slotsJson.get("timeslots").get(0);
            if (first.has("id") && first.get("id").canConvertToLong()) {
                return first.get("id").asLong();
            }
        }
        return null;
    }

    /**
     * Получает событие по ID для пользователя
     */
    private Event getEventById(String eventId, Long userId) {
        try {
            List<Event> events = eventsService.getEvents(userId);
            return events.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            logger.error("Error getting event {} for user {}", eventId, userId, e);
            return null;
        }
    }

    /**
     * Экранирует специальные символы Markdown
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
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!");
    }
}
