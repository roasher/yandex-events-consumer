package com.example.telegrambot.bot;

import com.example.telegrambot.config.BotConfig;
import com.example.telegrambot.dto.Category;
import com.example.telegrambot.dto.City;
import com.example.telegrambot.dto.Event;
import com.example.telegrambot.service.EventsService;
import com.example.telegrambot.service.EventHoldService;
import com.example.telegrambot.service.EventPollingService;
import com.example.telegrambot.service.UserCookieService;
import com.example.telegrambot.service.UserPreferencesService;
import com.example.telegrambot.service.WaitlistService;
import com.example.telegrambot.service.WaitlistMonitoringService;
import com.example.telegrambot.service.YandexEventsBookingService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

@Component
public class TelegramBot extends TelegramLongPollingBot {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBot.class);
    private final BotConfig botConfig;
    private final EventsService eventsService;
    private final WaitlistService waitlistService;

    // Кеш для хранения соответствия между (chatId, eventId) и messageId
    // Используется для обновления кнопок при изменении листа ожидания
    private final Map<String, Integer> eventMessageCache = new ConcurrentHashMap<>();
    private final YandexEventsBookingService bookingService;
    private final UserCookieService userCookieService;
    private final UserPreferencesService userPreferencesService;
    private final EventHoldService eventHoldService;
    private final ApplicationContext applicationContext;
    private final EventPollingService eventPollingService;

    // Хранилище всех messageId для каждого chatId (для удаления сообщений при /start)
    private final Map<Long, List<Integer>> chatMessages = new ConcurrentHashMap<>();

    @Value("${booking.default.timeslot:0}")
    private long defaultTimeSlot;

    @Value("${events.api.cookies:}")
    private String defaultCookie;

    @Value("${events.default.city:}")
    private String defaultCityId;

    @Value("${events.default.categories:}")
    private List<Integer> defaultCategories;

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36";

    public TelegramBot(BotConfig botConfig, EventsService eventsService, WaitlistService waitlistService, YandexEventsBookingService bookingService, UserCookieService userCookieService, UserPreferencesService userPreferencesService, EventHoldService eventHoldService, ApplicationContext applicationContext, EventPollingService eventPollingService) {
        this.botConfig = botConfig;
        this.eventsService = eventsService;
        this.waitlistService = waitlistService;
        this.bookingService = bookingService;
        this.userCookieService = userCookieService;
        this.userPreferencesService = userPreferencesService;
        this.eventHoldService = eventHoldService;
        this.applicationContext = applicationContext;
        this.eventPollingService = eventPollingService;
    }

    /**
     * Извлекает eventId из URL события
     * Поддерживает форматы:
     * - https://events.yandex-team.ru/?city=1&eventId=b27b9fb8-895a-4b1d-bc56-704e92f46457
     * - https://events.yandex-team.ru/events/b27b9fb8-895a-4b1d-bc56-704e92f46457?city=1
     */
    private String extractEventIdFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        try {
            // Извлекаем параметр eventId из query string
            if (url.contains("eventId=")) {
                int startIndex = url.indexOf("eventId=") + "eventId=".length();
                int endIndex = url.indexOf("&", startIndex);
                if (endIndex == -1) {
                    endIndex = url.length();
                }
                String eventId = url.substring(startIndex, endIndex);
                // Убираем возможные фрагменты (#)
                if (eventId.contains("#")) {
                    eventId = eventId.substring(0, eventId.indexOf("#"));
                }
                return eventId.trim();
            }

            // Пытаемся извлечь из пути /events/{eventId}
            if (url.contains("/events/")) {
                int startIndex = url.indexOf("/events/") + "/events/".length();
                int endIndex = url.indexOf("?", startIndex);
                if (endIndex == -1) {
                    endIndex = url.indexOf("#", startIndex);
                }
                if (endIndex == -1) {
                    endIndex = url.length();
                }
                String eventId = url.substring(startIndex, endIndex);
                // Убираем слеш в конце, если есть
                if (eventId.endsWith("/")) {
                    eventId = eventId.substring(0, eventId.length() - 1);
                }
                return eventId.trim();
            }
        } catch (Exception e) {
            logger.error("Error extracting eventId from URL: {}", url, e);
        }

        return null;
    }

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

    @Override
    public void onUpdateReceived(Update update) {
        // Обработка callback от кнопок
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
            return;
        }

        // Обработка текстовых сообщений
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userName = update.getMessage().getFrom().getUserName();
            String firstName = update.getMessage().getFrom().getFirstName();

            if (messageText.equals("/start")) {
                // Удаляем все предыдущие сообщения бота для этого чата
                deleteAllBotMessages(chatId);

                Long userId = update.getMessage().getFrom().getId();

                // Проверяем, есть ли кука в переменных окружения
                if (defaultCookie != null && !defaultCookie.trim().isEmpty()) {
                    // Используем куку из переменной окружения
                    String cookie = defaultCookie.trim();
                    if (validateCookie(cookie)) {
                        userCookieService.setCookie(userId, cookie);
                        logger.info("Using cookie from environment variable for user {}", userId);
                        
                        // Пытаемся загрузить события для проверки куки
                        try {
                            var events = eventsService.getEvents(userId);
                            if (events == null || events.isEmpty()) {
                                sendMessage(chatId, "✅ Кука из переменной окружения применена, но не удалось загрузить события. Возможно, кука недействительна.");
                            } else {
                                sendMessage(chatId, "✅ Кука из переменной окружения применена!");
                                // Проверяем, нужно ли выбрать город
                                if (!userPreferencesService.hasCity(userId)) {
                                    // Проверяем, есть ли дефолтный город в переменных окружения
                                    if (applyDefaultCityIfSet(chatId, userId)) {
                                        // Если дефолтный город применен, проверяем категории
                                        applyDefaultCategoriesIfSet(chatId, userId);
                                        // Загружаем события с примененными настройками
                                        events = eventsService.getEvents(userId);
                                        if (events != null && !events.isEmpty()) {
                                            sendEventsWithButtons(chatId, userId, events);
                                        } else {
                                            sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
                                        }
                                    } else {
                                        // Если дефолтного города нет, запрашиваем выбор
                                        requestCitySelection(chatId, userId);
                                    }
                                } else {
                                    // Если город уже выбран, отправляем события
                                    sendEventsWithButtons(chatId, userId, events);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Error getting events with environment cookie", e);
                            sendMessage(chatId, "✅ Кука из переменной окружения применена, но произошла ошибка при загрузке событий. Попробуйте позже.");
                        }
                    } else {
                        logger.warn("Cookie from environment variable is invalid, falling back to user input");
                        requestCookie(chatId);
                    }
                } else {
                    // При /start запрашиваем куку у пользователя
                    requestCookie(chatId);
                }
                return;
            } else if (messageText.equals("/help")) {
                showHelp(chatId);
                return;
            } else if (messageText.equals("/refresh")) {
                Long userId = update.getMessage().getFrom().getId();

                if (!ensureCookieIsSet(chatId, userId)) {
                    return;
                }

                if (!ensureCityIsSet(chatId, userId)) {
                    return;
                }

                // Применяем категории из переменной окружения, если они есть (категории не обязательны)
                ensureCategoriesAreSet(chatId, userId);

                // Обновляем список событий
                try {
                    sendMessage(chatId, "🔄 Обновляю список событий...");
                    var events = eventsService.getEvents(userId);
                    if (events != null && !events.isEmpty()) {
                        sendEventsWithButtons(chatId, userId, events);
                    } else {
                        sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
                    }
                } catch (Exception e) {
                    logger.error("Error refreshing events", e);
                    sendMessage(chatId, "❌ Произошла ошибка при загрузке событий. Попробуйте позже.");
                }
                return;
            } else if (messageText.equals("/reset_categories")) {
                Long userId = update.getMessage().getFrom().getId();

                if (!ensureCookieIsSet(chatId, userId)) {
                    return;
                }

                if (!ensureCityIsSet(chatId, userId)) {
                    return;
                }

                // Применяем категории из переменной окружения, если они есть (категории не обязательны)
                ensureCategoriesAreSet(chatId, userId);

                // Сбрасываем выбор категорий и запрашиваем заново
                userPreferencesService.setCategories(userId, new HashSet<>());
                requestCategorySelection(chatId, userId);
                return;
            } else if (messageText.startsWith("/hold_event_link")) {
                // Обработка команды /hold_event_link + ссылка
                Long userId = update.getMessage().getFrom().getId();

                if (!ensureCookieIsSet(chatId, userId)) {
                    return;
                }

                if (!ensureCityIsSet(chatId, userId)) {
                    return;
                }

                // Применяем категории из переменной окружения, если они есть (категории не обязательны)
                ensureCategoriesAreSet(chatId, userId);

                String[] parts = messageText.split("\\s+", 2);
                if (parts.length < 2) {
                    sendMessage(chatId, "❌ Использование: /hold_event_link <ссылка на событие>\n\nПример: /hold_event_link https://events.yandex-team.ru/?city=1&eventId=b27b9fb8-895a-4b1d-bc56-704e92f46457");
                    return;
                }
                String eventUrl = parts[1].trim();
                String eventId = extractEventIdFromUrl(eventUrl);
                if (eventId == null || eventId.isEmpty()) {
                    sendMessage(chatId, "❌ Не удалось извлечь ID события из ссылки.\n\nПроверьте формат ссылки. Должен быть параметр eventId.");
                    return;
                }

                eventHoldService.holdEvent(eventId);
                int heldCount = eventHoldService.getHeldEventsCount();
                sendMessage(chatId, String.format("✅ Событие захолжено (ID: %s)\n\nЗахолжено событий: %d\n\nЗапись на это событие теперь невозможна.", eventId, heldCount));

                // Обновляем список событий, чтобы кнопки отразили новое состояние
                try {
                    var events = eventsService.getEvents(userId);
                    if (events != null && !events.isEmpty()) {
                        sendMessage(chatId, "🔄 Обновляю список событий...");
                        sendEventsWithButtons(chatId, userId, events);
                    }
                } catch (Exception e) {
                    logger.error("Error refreshing events after hold", e);
                }
                return;
            } else if (messageText.equals("/unhold")) {
                // Снимаем захолд со всех событий
                Long userId = update.getMessage().getFrom().getId();

                if (!ensureCookieIsSet(chatId, userId)) {
                    return;
                }

                if (!ensureCityIsSet(chatId, userId)) {
                    return;
                }

                // Применяем категории из переменной окружения, если они есть (категории не обязательны)
                ensureCategoriesAreSet(chatId, userId);

                int count = eventHoldService.getHeldEventsCount();
                eventHoldService.unholdAll();
                sendMessage(chatId, String.format("✅ Снят захолд со всех событий (%d событий)\n\nЗапись на все события снова доступна.", count));

                // Обновляем список событий, чтобы кнопки отразили новое состояние
                try {
                    var events = eventsService.getEvents(userId);
                    if (events != null && !events.isEmpty()) {
                        sendMessage(chatId, "🔄 Обновляю список событий...");
                        sendEventsWithButtons(chatId, userId, events);
                    }
                } catch (Exception e) {
                    logger.error("Error refreshing events after unhold", e);
                }
                return;
            } else if (messageText.equals("/start_poll")) {
                // Запускает опрос событий
                Long userId = update.getMessage().getFrom().getId();

                if (!ensureCookieIsSet(chatId, userId)) {
                    return;
                }

                if (!ensureCityIsSet(chatId, userId)) {
                    return;
                }

                // Применяем категории из переменной окружения, если они есть (категории не обязательны)
                ensureCategoriesAreSet(chatId, userId);

                if (eventPollingService.isPollingEnabled()) {
                    Long currentUserId = eventPollingService.getPollingUserId();
                    if (currentUserId != null && currentUserId.equals(userId)) {
                        sendMessage(chatId, "✅ Опрос уже запущен для вашего аккаунта.");
                    } else {
                        sendMessage(chatId, "❌ Опрос уже запущен для другого пользователя. Используйте /end_poll, чтобы остановить его.");
                    }
                    return;
                }

                boolean started = eventPollingService.startPolling(userId, chatId);
                if (started) {
                    sendMessage(chatId, "✅ Опрос событий запущен!\n\nБот будет проверять доступность событий каждые 5 секунд и автоматически бронировать их, когда они станут доступны.");
                } else {
                    sendMessage(chatId, "❌ Не удалось запустить опрос. Убедитесь, что в переменных окружения задан список имен событий (EVENTS_POLL_NAMES).");
                }
                return;
            } else if (messageText.equals("/end_poll")) {
                // Останавливает опрос событий
                Long userId = update.getMessage().getFrom().getId();

                if (!eventPollingService.isPollingEnabled()) {
                    sendMessage(chatId, "❌ Опрос не запущен.");
                    return;
                }

                Long pollingUserId = eventPollingService.getPollingUserId();
                if (pollingUserId != null && !pollingUserId.equals(userId)) {
                    sendMessage(chatId, "❌ Опрос запущен для другого пользователя. Только он может остановить опрос.");
                    return;
                }

                eventPollingService.stopPolling();
                sendMessage(chatId, "✅ Опрос событий остановлен.");
                return;
            } else {
                // Обработка ввода куки, города или других сообщений
                Long userId = update.getMessage().getFrom().getId();

                // Проверяем, ожидаем ли мы выбор города
                if (userPreferencesService.isAwaitingCitySelection(userId)) {
                    // Пытаемся обработать как выбор города (по ID или имени)
                    handleCitySelection(chatId, userId, messageText.trim());
                    return;
                }

                // Проверяем, ожидаем ли мы куку от этого пользователя
                if (!userCookieService.hasCookie(userId)) {
                    // Пытаемся обработать как куку
                    String cookie = messageText.trim();

                    // Валидация куки
                    if (!validateCookie(cookie)) {
                        sendMessage(chatId, "❌ Кука недействительна. Проверьте, что вы скопировали куку полностью.\n\nПопробуйте снова или используйте /start");
                        return;
                    }

                    // Сохраняем куку
                    userCookieService.setCookie(userId, cookie);

                    try {
                        var events = eventsService.getEvents(userId);
                        if (events == null || events.isEmpty()) {
                            sendMessage(chatId, "✅ Кука сохранена, но не удалось загрузить события. Возможно, кука недействительна.\n\nПроверьте куку и попробуйте снова.");
                        } else {
                            sendMessage(chatId, "✅ Кука успешно сохранена!");
                            // Проверяем, нужно ли выбрать город
                            if (!userPreferencesService.hasCity(userId)) {
                                // Проверяем, есть ли дефолтный город в переменных окружения
                                if (applyDefaultCityIfSet(chatId, userId)) {
                                    // Если дефолтный город применен, проверяем категории
                                    applyDefaultCategoriesIfSet(chatId, userId);
                                    // Загружаем события с примененными настройками
                                    events = eventsService.getEvents(userId);
                                    if (events != null && !events.isEmpty()) {
                                        sendEventsWithButtons(chatId, userId, events);
                                    } else {
                                        sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
                                    }
                                } else {
                                    // Если дефолтного города нет, запрашиваем выбор
                                    requestCitySelection(chatId, userId);
                                }
                            } else {
                                // Если город уже выбран, отправляем события
                                sendEventsWithButtons(chatId, userId, events);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error getting events", e);
                        sendMessage(chatId, "✅ Кука сохранена, но произошла ошибка при загрузке событий. Попробуйте позже.");
                    }
                }
            }
        }
    }

    private void deleteAllBotMessages(long chatId) {
        List<Integer> messageIds = chatMessages.get(chatId);
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        // Удаляем все сообщения
        for (Integer messageId : messageIds) {
            try {
                DeleteMessage deleteMessage = new DeleteMessage();
                deleteMessage.setChatId(String.valueOf(chatId));
                deleteMessage.setMessageId(messageId);
                execute(deleteMessage);
            } catch (TelegramApiException e) {
                // Игнорируем ошибки удаления (сообщение могло быть уже удалено или быть старше 48 часов)
                logger.debug("Could not delete message {} for chat {}: {}", messageId, chatId, e.getMessage());
            }
        }

        // Очищаем список сообщений для этого чата
        chatMessages.remove(chatId);

        // Очищаем кеш кнопок событий для этого чата
        eventMessageCache.entrySet().removeIf(entry -> entry.getKey().startsWith(chatId + ":"));
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        String callbackQueryId = update.getCallbackQuery().getId();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        Long userId = update.getCallbackQuery().getFrom().getId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        try {
            // Обработка показа всех городов
            if (callbackData.equals("show_all_cities")) {
                // Удаляем предыдущее сообщение
                try {
                    DeleteMessage deleteMessage = new DeleteMessage();
                    deleteMessage.setChatId(String.valueOf(chatId));
                    deleteMessage.setMessageId(messageId);
                    execute(deleteMessage);
                } catch (Exception e) {
                    logger.debug("Could not delete city selection message: {}", e.getMessage());
                }

                // Показываем все города
                requestCitySelection(chatId, userId, true);
                answerCallbackQuery(callbackQueryId, "");
                return;
            }

            // Обработка выбора города
            if (callbackData.startsWith("city:")) {
                String cityIdStr = callbackData.substring("city:".length());
                try {
                    int cityId = Integer.parseInt(cityIdStr);
                    userPreferencesService.setCity(userId, cityId);
                    userPreferencesService.setAwaitingCitySelection(userId, false);
                    answerCallbackQuery(callbackQueryId, "Город выбран");

                    // Удаляем сообщение с выбором города
                    try {
                        DeleteMessage deleteMessage = new DeleteMessage();
                        deleteMessage.setChatId(String.valueOf(chatId));
                        deleteMessage.setMessageId(messageId);
                        execute(deleteMessage);
                    } catch (Exception e) {
                        logger.debug("Could not delete city selection message: {}", e.getMessage());
                    }

                    // После выбора города проверяем, есть ли дефолтные категории
                    if (applyDefaultCategoriesIfSet(chatId, userId)) {
                        // Если дефолтные категории применены, загружаем события
                        try {
                            var events = eventsService.getEvents(userId);
                            if (events != null && !events.isEmpty()) {
                                sendEventsWithButtons(chatId, userId, events);
                            } else {
                                sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
                            }
                        } catch (Exception ex) {
                            logger.error("Error getting events after default categories", ex);
                            sendMessage(chatId, "❌ Произошла ошибка при загрузке событий. Попробуйте позже.");
                        }
                    } else {
                        // Если дефолтных категорий нет, запрашиваем выбор
                        requestCategorySelection(chatId, userId);
                    }
                } catch (NumberFormatException e) {
                    answerCallbackQuery(callbackQueryId, "Ошибка выбора города");
                    sendMessage(chatId, "❌ Произошла ошибка при выборе города. Попробуйте снова.");
                }
                return;
            }

            // Обработка выбора категорий
            if (callbackData.startsWith("category:")) {
                String categoryIdStr = callbackData.substring("category:".length());
                handleCategoryToggle(chatId, userId, categoryIdStr, callbackQueryId, messageId);
                return;
            }

            // Обработка подтверждения выбора категорий
            if (callbackData.equals("categories_done")) {
                handleCategoriesDone(chatId, userId, callbackQueryId);
                return;
            }

            // Обработка кнопки записи на событие
            if (callbackData.startsWith("book:")) {
                String eventId = callbackData.substring("book:".length());

                // Получаем событие, чтобы корректно собрать Referer (нужен город)
                var events = eventsService.getEvents(userId);
                Event event = events.stream()
                    .filter(e -> e.getId().equals(eventId))
                    .findFirst()
                    .orElse(null);

                int cityId = 1;
                if (event != null && event.getCity() != null) {
                    cityId = event.getCity().getId();
                }

                String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);

                // Получаем персональную куку пользователя
                String userCookie = userCookieService.getCookie(userId);
                if (userCookie == null || userCookie.isEmpty()) {
                    answerCallbackQuery(callbackQueryId, "Кука не найдена");
                    sendMessage(chatId, "❌ Кука не найдена. Пожалуйста, используйте /start для настройки.");
                    return;
                }

                // Проверяем, не захолжено ли событие
                if (eventHoldService.isEventHeld(eventId)) {
                    answerCallbackQuery(callbackQueryId, "Событие захолжено");
                    sendMessage(chatId, "❌ Запись на это событие временно недоступна (событие захолжено для тестирования).");
                    return;
                }

                try {
                    // 1) Запрашиваем доступные слоты
                    var slotsJson = bookingService.getTimeSlots(eventId,
                        userCookie,
                        referer,
                        DEFAULT_USER_AGENT);

                    // 2) Выбираем первый доступный слот (id)
                    Long slotId = extractFirstSlotId(slotsJson);
                    logger.info("Timeslots for event {}: {}", eventId, slotsJson);
                    logger.info("Selected slot id: {}", slotId);
                    if (slotId == null || slotId <= 0) {
                        answerCallbackQuery(callbackQueryId, "Нет доступных слотов");
                        sendMessage(chatId, "❌ Для этого события нет доступных слотов для записи.");
                        return;
                    }

                    // 3) Пытаемся записаться
                    var response = bookingService.book(
                        userCookie,
                        referer,
                        DEFAULT_USER_AGENT,
                        new YandexEventsBookingService.BookingRequest(slotId, 0, 0)
                    );

                    // Формируем понятное сообщение о регистрации
                    String eventTitle = event != null && event.getTitle() != null ? event.getTitle() : "событие";
                    String formattedTime = "время неизвестно";
                    String eventUrl = event != null ? eventsService.getEventUrl(event) : null;

                    if (response.has("startDatetime") && response.get("startDatetime").asText() != null) {
                        try {
                            String dateTimeStr = response.get("startDatetime").asText();
                            // Парсим дату в формате ISO 8601 (например, "2025-11-19T15:00:00Z")
                            ZonedDateTime dateTime = ZonedDateTime.parse(dateTimeStr);
                            // Конвертируем в московское время
                            ZonedDateTime moscowTime = dateTime.withZoneSameInstant(ZoneId.of("Europe/Moscow"));
                            // Форматируем дату и время на русском языке
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'в' HH:mm", new Locale("ru", "RU"));
                            formattedTime = moscowTime.format(formatter);
                        } catch (Exception e) {
                            logger.warn("Failed to parse startDatetime: {}", response.get("startDatetime"), e);
                        }
                    }

                    // Проверяем успешность регистрации (наличие startDatetime означает успех)
                    boolean registrationSuccessful = response.has("startDatetime") && response.get("startDatetime").asText() != null;

                    if (registrationSuccessful) {
                        // Формируем сообщение со ссылкой на событие
                        String message;
                        if (eventUrl != null && !eventUrl.isEmpty()) {
                            // Делаем слово "событие" кликабельным
                            message = "✅ Вы успешно записаны на [событие](" + eventUrl + ") \"" + eventTitle + "\" " + formattedTime;
                        } else {
                            message = "✅ Вы успешно записаны на событие \"" + eventTitle + "\" " + formattedTime;
                        }

                        answerCallbackQuery(callbackQueryId, "Запрос на регистрацию отправлен");
                        sendMessageWithMarkdown(chatId, message);

                        // Обновляем кнопку на "Отменить регистрацию" и текст с зелеными индикаторами
                        // Передаем true явно, так как регистрация только что прошла успешно
                        updateEventButtonAfterBooking(chatId, messageId, eventId, userId, true);
                    } else {
                        // Регистрация не удалась
                        answerCallbackQuery(callbackQueryId, "Не удалось выполнить регистрацию");
                        String errorMessage = "❌ Не удалось выполнить регистрацию.";
                        if (response.has("message") && response.get("message").asText() != null) {
                            errorMessage += " " + response.get("message").asText();
                        }
                        sendMessage(chatId, errorMessage);
                    }
                } catch (Exception ex) {
                    logger.error("Booking failed for event {}", eventId, ex);
                    answerCallbackQuery(callbackQueryId, "Ошибка при регистрации");
                    sendMessage(chatId, "❌ Не удалось выполнить регистрацию: " + (ex.getMessage() != null ? ex.getMessage() : "ошибка"));
                }
            }
            // Обработка кнопки отмены регистрации
            else if (callbackData.startsWith("unbook:")) {
                String eventId = callbackData.substring("unbook:".length());

                // Получаем событие, чтобы корректно собрать Referer (нужен город)
                var events = eventsService.getEvents(userId);
                Event event = events.stream()
                    .filter(e -> e.getId().equals(eventId))
                    .findFirst()
                    .orElse(null);

                int cityId = 1;
                if (event != null && event.getCity() != null) {
                    cityId = event.getCity().getId();
                }

                // Используем формат URL события для Referer, как в curl запросе
                String referer = String.format("https://events.yandex-team.ru/events/%s?city=%d&eventId=%s", eventId, cityId, eventId);

                // Получаем персональную куку пользователя
                String userCookie = userCookieService.getCookie(userId);
                if (userCookie == null || userCookie.isEmpty()) {
                    answerCallbackQuery(callbackQueryId, "Кука не найдена");
                    sendMessage(chatId, "❌ Кука не найдена. Пожалуйста, используйте /start для настройки.");
                    return;
                }

                try {
                    // Отменяем регистрацию через API
                    boolean unbooked = bookingService.unbook(
                        eventId,
                        userCookie,
                        referer,
                        DEFAULT_USER_AGENT
                    );

                    if (unbooked) {
                        answerCallbackQuery(callbackQueryId, "Регистрация отменена");
                        sendMessage(chatId, "✅ Регистрация на событие отменена.");

                        // Обновляем кнопку обратно на "Записаться на событие" и убираем зеленые индикаторы
                        updateEventButtonAfterBooking(chatId, messageId, eventId, userId, false);
                    } else {
                        answerCallbackQuery(callbackQueryId, "Не удалось отменить регистрацию");
                        sendMessage(chatId, "❌ Не удалось отменить регистрацию. Возможно, вы не были зарегистрированы на это событие.");
                    }
                } catch (Exception ex) {
                    logger.error("Unbooking failed for event {}", eventId, ex);
                    answerCallbackQuery(callbackQueryId, "Ошибка при отмене регистрации");
                    sendMessage(chatId, "❌ Произошла ошибка при отмене регистрации: " + (ex.getMessage() != null ? ex.getMessage() : "ошибка"));
                }
            }
            // Обработка кнопки листа ожидания
            else if (callbackData.startsWith("waitlist:")) {
                String eventId = callbackData.substring("waitlist:".length());

                WaitlistService.AddToWaitlistResult result = waitlistService.addToWaitlist(eventId, userId, chatId);

                if (result.isSuccess()) {
                    int peopleAhead = result.getPeopleAhead();
                    String message;
                    if (peopleAhead == 0) {
                        message = "✅ Вы записаны в лист ожидания!\n\nВы первый в очереди.";
                    } else {
                        message = "✅ Вы записаны в лист ожидания!\n\nВ очереди перед вами: " + peopleAhead + " " +
                                 formatPeople(peopleAhead);
                    }
                    answerCallbackQuery(callbackQueryId, "Записаны в лист ожидания");
                    sendMessage(chatId, message);

                    // Обновляем кнопку в исходном сообщении для пользователя, который добавился
                    updateEventButton(chatId, messageId, eventId, true);

                    // Обновляем кнопки у всех других пользователей, которые просматривают это событие
                    updateAllEventButtonsForEvent(eventId, userId);
                } else {
                    answerCallbackQuery(callbackQueryId, result.getMessage());
                    sendMessage(chatId, "❌ " + result.getMessage());
                }
            }
            // Обработка кнопки выхода из листа ожидания
            else if (callbackData.startsWith("leave_waitlist:")) {
                String eventId = callbackData.substring("leave_waitlist:".length());

                WaitlistService.RemoveFromWaitlistResult result = waitlistService.removeFromWaitlist(eventId, userId);

                if (result.isSuccess()) {
                    answerCallbackQuery(callbackQueryId, "Вы вышли из листа ожидания");
                    sendMessage(chatId, "✅ " + result.getMessage());

                    // Обновляем кнопку в исходном сообщении для пользователя, который вышел
                    updateEventButton(chatId, messageId, eventId, false);

                    // Обновляем кнопки у всех других пользователей, которые просматривают это событие
                    updateAllEventButtonsForEvent(eventId, userId);

                    // Отправляем уведомления всем пользователям, чьи позиции изменились
                    notifyUsersAboutPositionChange(eventId, result.getPositionUpdates());
                } else {
                    answerCallbackQuery(callbackQueryId, result.getMessage());
                    sendMessage(chatId, "❌ " + result.getMessage());
                }
            }
            // Обработка подтверждения предложенного слота
            else if (callbackData.startsWith("confirm_slot:")) {
                String eventId = callbackData.substring("confirm_slot:".length());

                // Получаем WaitlistMonitoringService через ApplicationContext для избежания циклической зависимости
                WaitlistMonitoringService monitoringService =
                    applicationContext.getBean(WaitlistMonitoringService.class);

                boolean success = monitoringService.handleSlotConfirmation(eventId, userId, chatId);

                if (success) {
                    answerCallbackQuery(callbackQueryId, "Регистрация выполнена");
                    // Удаляем сообщение с предложением
                    try {
                        DeleteMessage deleteMessage = new DeleteMessage();
                        deleteMessage.setChatId(String.valueOf(chatId));
                        deleteMessage.setMessageId(messageId);
                        execute(deleteMessage);
                    } catch (Exception e) {
                        logger.debug("Could not delete slot offer message: {}", e.getMessage());
                    }
                } else {
                    answerCallbackQuery(callbackQueryId, "Не удалось выполнить регистрацию");
                }
            }
            // Обработка отказа от предложенного слота
            else if (callbackData.startsWith("reject_slot:")) {
                String eventId = callbackData.substring("reject_slot:".length());

                // Получаем WaitlistMonitoringService через ApplicationContext для избежания циклической зависимости
                WaitlistMonitoringService monitoringService =
                    applicationContext.getBean(WaitlistMonitoringService.class);

                monitoringService.handleSlotRejection(eventId, userId, chatId);

                answerCallbackQuery(callbackQueryId, "Вы отказались от предложения");

                // Удаляем сообщение с предложением
                try {
                    DeleteMessage deleteMessage = new DeleteMessage();
                    deleteMessage.setChatId(String.valueOf(chatId));
                    deleteMessage.setMessageId(messageId);
                    execute(deleteMessage);
                } catch (Exception e) {
                    logger.debug("Could not delete slot offer message: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error handling callback query", e);
            try {
                answerCallbackQuery(callbackQueryId, "Произошла ошибка");
                sendMessage(chatId, "Произошла ошибка при обработке запроса. Попробуйте позже.");
            } catch (Exception ex) {
                logger.error("Error sending error message", ex);
            }
        }
    }

    private void notifyUsersAboutPositionChange(String eventId, List<WaitlistService.PositionUpdate> positionUpdates) {
        for (WaitlistService.PositionUpdate update : positionUpdates) {
            try {
                int positionChange = update.getOldPosition() - update.getNewPosition();
                String message;
                if (positionChange == 1) {
                    message = String.format(
                        "📢 Обновление листа ожидания\n\n" +
                        "Кто-то вышел из листа ожидания.\n" +
                        "Ваша позиция в очереди изменилась: %d → %d (вы продвинулись на 1 позицию)",
                        update.getOldPosition(),
                        update.getNewPosition()
                    );
                } else {
                    message = String.format(
                        "📢 Обновление листа ожидания\n\n" +
                        "Кто-то вышел из листа ожидания.\n" +
                        "Ваша позиция в очереди изменилась: %d → %d (вы продвинулись на %d %s)",
                        update.getOldPosition(),
                        update.getNewPosition(),
                        positionChange,
                        formatPeople(positionChange)
                    );
                }
                sendMessage(update.getChatId(), message);
            } catch (Exception e) {
                logger.error("Error sending notification to user {}", update.getUserId(), e);
            }
        }
    }

    private void updateEventButton(long chatId, Integer messageId, String eventId, boolean isInWaitlist) {
        try {
            // Сохраняем messageId в кеш для возможного обновления в будущем
            String cacheKey = chatId + ":" + eventId;
            eventMessageCache.put(cacheKey, messageId);

            // Находим userId через waitlist (для booking используем API)
            Long userId = findUserIdByChatIdAndEventId(chatId, eventId);

            // Получаем информацию о событии для создания правильной кнопки
            var events = eventsService.getEvents(userId);
            Event event = events.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);

            if (event == null) {
                logger.warn("Event {} not found for button update", eventId);
                return;
            }

            // Проверяем статус регистрации через API
            String userCookie = userCookieService.getCookie(userId);
            if (userCookie == null || userCookie.isEmpty()) {
                // Если куки нет, считаем, что пользователь не зарегистрирован
                return;
            }
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s",
                event.getCity() != null ? event.getCity().getId() : 1, eventId);
            boolean isBooked = bookingService.isUserBooked(
                eventId,
                userCookie,
                referer,
                DEFAULT_USER_AGENT
            );

            // Создаем новый текст сообщения с зелеными индикаторами
            String updatedMessageText = eventsService.formatSingleEvent(event, isBooked);

            // Создаем новую кнопку
            InlineKeyboardMarkup keyboard = createEventKeyboardForUser(event, isInWaitlist, isBooked, userId);

            // Обновляем текст сообщения и кнопку
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText editMessage =
                new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
            editMessage.setChatId(String.valueOf(chatId));
            editMessage.setMessageId(messageId);
            editMessage.setText(updatedMessageText);
            editMessage.setParseMode("Markdown");
            editMessage.setReplyMarkup(keyboard);

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                // Если не удалось обновить с Markdown, пробуем без форматирования
                logger.debug("Failed to update message with Markdown, trying without formatting", e);
                String plainText = updatedMessageText.replace("*", "").replace("_", "");
                editMessage.setParseMode(null);
                editMessage.setText(plainText);
                try {
                    execute(editMessage);
                } catch (TelegramApiException ex) {
                    logger.error("Error updating event message", ex);
                }
            }
        } catch (Exception e) {
            logger.error("Error updating event button", e);
        }
    }

    private Long findUserIdByChatIdAndEventId(long chatId, String eventId) {
        // Ищем userId в waitlist записях (для booking используем API)
        var waitlistEntries = waitlistService.getAllUsersInWaitlist(eventId);
        return waitlistEntries.stream()
            .filter(entry -> entry.getChatId().equals(chatId))
            .map(entry -> entry.getUserId())
            .findFirst()
            .orElse(null);
    }

    private void updateEventButtonAfterBooking(long chatId, Integer messageId, String eventId, Long userId) {
        updateEventButtonAfterBooking(chatId, messageId, eventId, userId, null);
    }

    private void updateEventButtonAfterBooking(long chatId, Integer messageId, String eventId, Long userId, Boolean forcedBookingStatus) {
        try {
            // Сохраняем messageId в кеш для возможного обновления в будущем
            String cacheKey = chatId + ":" + eventId;
            eventMessageCache.put(cacheKey, messageId);

            // Получаем информацию о событии для создания правильной кнопки
            var events = eventsService.getEvents(userId);
            Event event = events.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);

            if (event == null) {
                logger.warn("Event {} not found for button update", eventId);
                return;
            }

            // Проверяем, находится ли пользователь в листе ожидания
            boolean isInWaitlist = userId != null && waitlistService.getPositionInWaitlist(eventId, userId) > 0;

            // Определяем статус регистрации
            boolean isBooked;
            if (forcedBookingStatus != null) {
                // Если передан явный статус (для отмены регистрации), используем его
                isBooked = forcedBookingStatus;
            } else {
                // Проверяем через API, если не указан явный статус
                String userCookie = userCookieService.getCookie(userId);
                if (userCookie != null && !userCookie.isEmpty()) {
                    int cityId = event.getCity() != null ? event.getCity().getId() : 1;
                    String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);
                    isBooked = bookingService.isUserBooked(eventId, userCookie, referer, DEFAULT_USER_AGENT);
                } else {
                    // Если нет куки, считаем что не зарегистрирован
                    isBooked = false;
                }
            }

            // Создаем новый текст сообщения с зелеными индикаторами
            String updatedMessageText = eventsService.formatSingleEvent(event, isBooked);

            logger.debug("Updating event message for chatId={}, messageId={}, eventId={}, isBooked={}",
                chatId, messageId, eventId, isBooked);

            // Создаем новую кнопку
            InlineKeyboardMarkup keyboard = createEventKeyboardForUser(event, isInWaitlist, isBooked, userId);

            // Обновляем текст сообщения и кнопку
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText editMessage =
                new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
            editMessage.setChatId(String.valueOf(chatId));
            editMessage.setMessageId(messageId);
            editMessage.setText(updatedMessageText);
            editMessage.setParseMode("Markdown");
            editMessage.setReplyMarkup(keyboard);

            try {
                execute(editMessage);
                logger.debug("Successfully updated event message for chatId={}, messageId={}, eventId={}",
                    chatId, messageId, eventId);
            } catch (TelegramApiRequestException e) {
                // Если сообщение не изменилось (кнопка уже в правильном состоянии), это не ошибка
                if (e.getErrorCode() == 400 && e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                    logger.debug("Message already in correct state, no update needed");
                } else {
                    logger.warn("Error updating message (TelegramApiRequestException): errorCode={}, message={}",
                        e.getErrorCode(), e.getMessage(), e);
                    throw e;
                }
            } catch (TelegramApiException e) {
                // Если не удалось обновить с Markdown, пробуем без форматирования
                logger.warn("Failed to update message with Markdown, trying without formatting: {}", e.getMessage(), e);
                String plainText = updatedMessageText.replace("*", "").replace("_", "");
                editMessage.setParseMode(null);
                editMessage.setText(plainText);
                try {
                    execute(editMessage);
                    logger.debug("Successfully updated event message without Markdown for chatId={}, messageId={}",
                        chatId, messageId);
                } catch (TelegramApiException ex) {
                    logger.error("Error updating event message after booking: {}", ex.getMessage(), ex);
                }
            }
        } catch (Exception e) {
            logger.error("Error updating event button after booking: {}", e.getMessage(), e);
        }
    }

    private void updateAllEventButtonsForEvent(String eventId, Long excludeUserId) {
        try {
            // Получаем информацию о событии
            var events = eventsService.getEvents(excludeUserId);
            Event event = events.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);

            if (event == null) {
                return;
            }

            // Получаем всех пользователей из листа ожидания для этого события
            var waitlistUsers = waitlistService.getAllUsersInWaitlist(eventId);

            // Получаем всех пользователей, которые просматривают это событие (по кешу)
            // и обновляем их кнопки
            for (Map.Entry<String, Integer> cacheEntry : eventMessageCache.entrySet()) {
                String cacheKey = cacheEntry.getKey();
                if (!cacheKey.endsWith(":" + eventId)) {
                    continue;
                }

                try {
                    String[] parts = cacheKey.split(":");
                    if (parts.length < 2) {
                        continue;
                    }
                    long chatId = Long.parseLong(parts[0]);
                    Integer messageId = cacheEntry.getValue();

                    // Определяем, находится ли этот пользователь в листе ожидания
                    // Проверяем по chatId из записей листа ожидания
                    boolean isInWaitlist = waitlistUsers.stream()
                        .anyMatch(waitlistEntry -> waitlistEntry.getChatId().equals(chatId));

                    // Получаем userId из листа ожидания для этого chatId (если есть)
                    Long targetUserId = waitlistUsers.stream()
                        .filter(waitlistEntry -> waitlistEntry.getChatId().equals(chatId))
                        .map(waitlistEntry -> waitlistEntry.getUserId())
                        .findFirst()
                        .orElse(excludeUserId); // Если не нашли, используем excludeUserId

                    // Обновляем кнопку (не вызываем updateEventButton, чтобы избежать рекурсии)
                    // Используем прямой вызов без сохранения в кеш
                    updateEventButtonDirectly(chatId, messageId, eventId, isInWaitlist, targetUserId);
                } catch (Exception e) {
                    logger.debug("Error updating button for cache key {}: {}", cacheKey, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error updating all event buttons for event {}", eventId, e);
        }
    }

    private void updateEventButtonDirectly(long chatId, Integer messageId, String eventId, boolean isInWaitlist, Long userId) {
        try {
            // Получаем информацию о событии для создания правильной кнопки
            var events = eventsService.getEvents(userId);
            Event event = events.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);

            if (event == null) {
                return;
            }

            // Проверяем статус регистрации через API
            String userCookie = userCookieService.getCookie(userId);
            if (userCookie == null || userCookie.isEmpty()) {
                // Если куки нет, считаем, что пользователь не зарегистрирован
                return;
            }
            int cityId = event.getCity() != null ? event.getCity().getId() : 1;
            String referer = String.format("https://events.yandex-team.ru/?city=%d&eventId=%s", cityId, eventId);
            boolean isBooked = bookingService.isUserBooked(
                eventId,
                userCookie,
                referer,
                DEFAULT_USER_AGENT
            );

            // Создаем новый текст сообщения с зелеными индикаторами
            String updatedMessageText = eventsService.formatSingleEvent(event, isBooked);

            // Создаем новую кнопку
            InlineKeyboardMarkup keyboard = createEventKeyboardForUser(event, isInWaitlist, isBooked, userId);

            // Обновляем текст сообщения и кнопку
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText editMessage =
                new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
            editMessage.setChatId(String.valueOf(chatId));
            editMessage.setMessageId(messageId);
            editMessage.setText(updatedMessageText);
            editMessage.setParseMode("Markdown");
            editMessage.setReplyMarkup(keyboard);

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                // Если не удалось обновить с Markdown, пробуем без форматирования
                logger.debug("Failed to update message with Markdown, trying without formatting", e);
                String plainText = updatedMessageText.replace("*", "").replace("_", "");
                editMessage.setParseMode(null);
                editMessage.setText(plainText);
                try {
                    execute(editMessage);
                } catch (TelegramApiException ex) {
                    logger.debug("Error updating event message directly for chatId {}: {}", chatId, ex.getMessage());
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки при обновлении кнопок других пользователей
            // (например, если сообщение было удалено)
            logger.debug("Error updating event button directly for chatId {}: {}", chatId, e.getMessage());
        }
    }

    private InlineKeyboardMarkup createEventKeyboardForUser(Event event, boolean isInWaitlist) {
        return createEventKeyboardForUser(event, isInWaitlist, false, null);
    }

    private InlineKeyboardMarkup createEventKeyboardForUser(Event event, boolean isInWaitlist, boolean isBooked, Long userId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        String eventId = event.getId();
        String eventUrl = eventsService.getEventUrl(event);
        int cityId = 1;
        if (event.getCity() != null) {
            cityId = event.getCity().getId();
        }

        InlineKeyboardButton button = new InlineKeyboardButton();

        // Проверяем, захолжено ли событие
        boolean isHeld = eventHoldService.isEventHeld(eventId);

        // isBooked уже проверен через API, используем его напрямую
        if (isBooked) {
            // Пользователь уже зарегистрирован - показываем кнопку отмены
            button.setText(normalizeButtonText("❌ Отменить регистрацию"));
            button.setCallbackData("unbook:" + eventId);
        } else if (isHeld || !event.isHaveFreeSeats()) {
            // Событие захолжено или нет свободных мест - показываем лист ожидания
            if (isInWaitlist) {
                button.setText(normalizeButtonText("❌ Выйти из листа ожидания"));
                button.setCallbackData("leave_waitlist:" + eventId);
            } else {
                int waitlistSize = waitlistService.getWaitlistSize(eventId);
                String buttonText = getWaitlistButtonText(waitlistSize);
                button.setText(normalizeButtonText(buttonText));
                button.setCallbackData("waitlist:" + eventId);
            }
        } else {
            // Есть свободные места и событие не захолжено - можно записаться
            button.setText(normalizeButtonText("✅ Записаться на событие"));
            // Вместо перехода по ссылке вызываем callback для регистрации
            button.setCallbackData("book:" + eventId);
        }

        row.add(button);
        keyboard.add(row);

        // Добавляем кнопку "Открыть событие на ивентах" во второй ряд
        List<InlineKeyboardButton> secondRow = new ArrayList<>();
        InlineKeyboardButton viewEventButton = new InlineKeyboardButton();
        viewEventButton.setText(normalizeButtonText("🔗 Открыть событие на ивентах"));
        viewEventButton.setUrl(eventUrl);
        secondRow.add(viewEventButton);
        keyboard.add(secondRow);

        markup.setKeyboard(keyboard);

        return markup;
    }

    // Максимальная длина текста кнопки (вычисляется для всех возможных вариантов)
    private static final int BUTTON_TEXT_MAX_LENGTH = calculateMaxButtonLength();

    private static int calculateMaxButtonLength() {
        // Вычисляем максимальную длину среди всех возможных текстов кнопок
        int maxLength = 0;

        // Варианты текстов кнопок
        String[] buttonTexts = {
            "✅ Записаться на событие",
            "❌ Выйти из листа ожидания",
            "❌ Отменить регистрацию",
            "🔗 Открыть событие на ивентах"
        };

        // Находим максимум среди фиксированных текстов
        for (String text : buttonTexts) {
            maxLength = Math.max(maxLength, text.length());
        }

        // Проверяем все возможные варианты текста для листа ожидания
        // "⏳ Встать в лист ожидания " + порядковое числительное
        String baseText = "⏳ Встать в лист ожидания ";
        String[] ordinalTexts = {
            "первым", "вторым", "третьим", "четвертым", "пятым",
            "шестым", "седьмым", "восьмым", "девятым", "десятым"
        };

        for (String ordinal : ordinalTexts) {
            maxLength = Math.max(maxLength, (baseText + ordinal).length());
        }

        // Для больших чисел: "⏳ Встать в лист ожидания 123-м"
        // Берем пример с трехзначным числом как максимальный
        maxLength = Math.max(maxLength, (baseText + "999-м").length());

        return maxLength;
    }

    private String normalizeButtonText(String text) {
        // Выравниваем текст до максимальной длины, добавляя неразрывные пробелы
        // Используем неразрывный пробел (U+00A0) для более надежного выравнивания в Telegram
        int currentLength = text.length();
        if (currentLength < BUTTON_TEXT_MAX_LENGTH) {
            int spacesNeeded = BUTTON_TEXT_MAX_LENGTH - currentLength;
            // Используем неразрывный пробел для выравнивания
            String padding = "\u00A0".repeat(spacesNeeded);
            return text + padding;
        }
        return text;
    }

    private String getWaitlistButtonText(int waitlistSize) {
        int position = waitlistSize + 1; // Позиция, которую займет пользователь
        String ordinalPosition = getOrdinalPosition(position);
        return "⏳ Встать в лист ожидания " + ordinalPosition;
    }

    private String getOrdinalPosition(int position) {
        // Преобразуем число в порядковое числительное в творительном падеже
        switch (position) {
            case 1:
                return "первым";
            case 2:
                return "вторым";
            case 3:
                return "третьим";
            case 4:
                return "четвертым";
            case 5:
                return "пятым";
            case 6:
                return "шестым";
            case 7:
                return "седьмым";
            case 8:
                return "восьмым";
            case 9:
                return "девятым";
            case 10:
                return "десятым";
            default:
                // Для чисел больше 10 используем числительное с суффиксом
                return position + "-м";
        }
    }

    private String formatPeople(int count) {
        int lastDigit = count % 10;
        int lastTwoDigits = count % 100;

        if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
            return "человек";
        }

        if (lastDigit == 1) {
            return "человек";
        } else if (lastDigit >= 2 && lastDigit <= 4) {
            return "человека";
        } else {
            return "человек";
        }
    }

    private void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery answer =
                new org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackQueryId);
            answer.setText(text);
            answer.setShowAlert(false);
            execute(answer);
        } catch (TelegramApiException e) {
            logger.error("Error answering callback query", e);
        }
    }

    private void sendEventsWithButtons(long chatId, Long userId, List<Event> events) {
        if (events == null || events.isEmpty()) {
            sendMessage(chatId, "Событий не найдено.");
            return;
        }

        // Удаляем старые сообщения с событиями для этого chatId
        deleteEventMessages(chatId);

        // Получаем все регистрации пользователя один раз
        String userCookie = userCookieService.getCookie(userId);
        Set<String> userBookings = null;
        if (userCookie != null && !userCookie.isEmpty()) {
            try {
                userBookings = bookingService.getUserBookings(
                    userCookie,
                    DEFAULT_USER_AGENT
                );
            } catch (Exception e) {
                logger.error("Error getting user bookings", e);
            }
        }

        // Создаем список всех событий для отображения
        List<Event> allEvents = new ArrayList<>(events != null ? events : new ArrayList<>());

        // Добавляем зарегистрированные события, которых нет в основном списке
        if (userBookings != null && !userBookings.isEmpty()) {
            Set<String> existingEventIds = allEvents.stream()
                .map(Event::getId)
                .collect(java.util.stream.Collectors.toSet());

            for (String bookedEventId : userBookings) {
                // Если события нет в основном списке, получаем его отдельно
                if (!existingEventIds.contains(bookedEventId)) {
                    try {
                        Event bookedEvent = eventsService.getEventById(bookedEventId, userId);
                        if (bookedEvent != null) {
                            allEvents.add(bookedEvent);
                            logger.debug("Added booked event {} to the list", bookedEventId);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to fetch booked event {}: {}", bookedEventId, e.getMessage());
                    }
                }
            }
        }

        if (allEvents.isEmpty()) {
            sendMessage(chatId, "Событий не найдено.");
            return;
        }

        // Ограничиваем количество событий для отображения
        int eventsToShow = Math.min(allEvents.size(), 10);
        for (int i = 0; i < eventsToShow; i++) {
            Event event = allEvents.get(i);
            boolean isInWaitlist = waitlistService.getPositionInWaitlist(event.getId(), userId) > 0;

            // Проверяем статус регистрации из кешированного списка
            boolean isBooked = userBookings != null && userBookings.contains(event.getId());

            sendEventWithButton(chatId, event, isInWaitlist, isBooked, userId);
        }

        if (allEvents.size() > 10) {
            sendMessage(chatId, "... и еще " + (allEvents.size() - 10) + " событий");
        }
    }

    /**
     * Удаляет все сообщения с событиями для указанного chatId
     */
    private void deleteEventMessages(long chatId) {
        // Находим все messageId для событий этого chatId в кеше
        List<Integer> eventMessageIds = new ArrayList<>();
        String chatIdPrefix = chatId + ":";

        for (Map.Entry<String, Integer> entry : eventMessageCache.entrySet()) {
            if (entry.getKey().startsWith(chatIdPrefix)) {
                eventMessageIds.add(entry.getValue());
            }
        }

        // Удаляем найденные сообщения
        for (Integer messageId : eventMessageIds) {
            try {
                DeleteMessage deleteMessage = new DeleteMessage();
                deleteMessage.setChatId(String.valueOf(chatId));
                deleteMessage.setMessageId(messageId);
                execute(deleteMessage);
            } catch (TelegramApiException e) {
                // Игнорируем ошибки удаления (сообщение могло быть уже удалено или быть старше 48 часов)
                logger.debug("Could not delete event message {} for chat {}: {}", messageId, chatId, e.getMessage());
            }
        }

        // Очищаем кеш кнопок событий для этого chatId
        eventMessageCache.entrySet().removeIf(entry -> entry.getKey().startsWith(chatIdPrefix));

        // Удаляем messageId из списка для удаления при /start (если они там есть)
        List<Integer> chatMessagesList = chatMessages.get(chatId);
        if (chatMessagesList != null) {
            chatMessagesList.removeAll(eventMessageIds);
        }
    }

    private void sendEventWithButton(long chatId, Event event, boolean isInWaitlist) {
        sendEventWithButton(chatId, event, isInWaitlist, false, null);
    }

    private void sendEventWithButton(long chatId, Event event, boolean isInWaitlist, boolean isBooked, Long userId) {
        String messageText = eventsService.formatSingleEvent(event, isBooked);
        InlineKeyboardMarkup keyboard = createEventKeyboardForUser(event, isInWaitlist, isBooked, userId);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(messageText);
        message.setParseMode("Markdown");
        message.setReplyMarkup(keyboard);

        try {
            org.telegram.telegrambots.meta.api.objects.Message sentMessage = execute(message);
            // Сохраняем messageId в кеш для возможного обновления кнопки в будущем
            if (sentMessage != null && sentMessage.getMessageId() != null) {
                String cacheKey = chatId + ":" + event.getId();
                eventMessageCache.put(cacheKey, sentMessage.getMessageId());
                // Сохраняем messageId для возможного удаления
                saveMessageId(chatId, sentMessage.getMessageId());
            }
        } catch (TelegramApiException e) {
            logger.warn("Failed to send event with button, trying without formatting", e);
            // Fallback without markdown - удаляем markdown разметку
            String plainText = messageText
                .replace("*", "")
                .replace("_", "");
            message.setParseMode(null);
            message.setText(plainText);
            try {
                org.telegram.telegrambots.meta.api.objects.Message sentMessage = execute(message);
                if (sentMessage != null && sentMessage.getMessageId() != null) {
                    String cacheKey = chatId + ":" + event.getId();
                    eventMessageCache.put(cacheKey, sentMessage.getMessageId());
                    // Сохраняем messageId для возможного удаления
                    saveMessageId(chatId, sentMessage.getMessageId());
                }
            } catch (TelegramApiException ex) {
                logger.error("Error sending event message", ex);
            }
        }
    }

    private InlineKeyboardMarkup createEventKeyboard(Event event) {
        return createEventKeyboardForUser(event, false);
    }

    /**
     * Публичный метод для отправки сообщения (для использования из других сервисов)
     */
    public void sendMessage(long chatId, String text) {
        sendMessageInternal(chatId, text);
    }

    private void sendMessageInternal(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);

        try {
            org.telegram.telegrambots.meta.api.objects.Message sentMessage = execute(message);
            // Сохраняем messageId для возможного удаления
            if (sentMessage != null && sentMessage.getMessageId() != null) {
                saveMessageId(chatId, sentMessage.getMessageId());
            }
        } catch (TelegramApiException e) {
            logger.error("Error sending message", e);
        }
    }

    /**
     * Публичный метод для отправки сообщения с Markdown (для использования из других сервисов)
     */
    public void sendMessageWithMarkdown(long chatId, String text) {
        sendMessageWithMarkdownInternal(chatId, text);
    }

    private void sendMessageWithMarkdownInternal(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown");

        try {
            org.telegram.telegrambots.meta.api.objects.Message sentMessage = execute(message);
            // Сохраняем messageId для возможного удаления
            if (sentMessage != null && sentMessage.getMessageId() != null) {
                saveMessageId(chatId, sentMessage.getMessageId());
            }
        } catch (TelegramApiException e) {
            logger.warn("Failed to send message with markdown, trying without formatting", e);
            // Fallback to plain text if markdown fails
            sendMessageInternal(chatId, text.replace("*", "").replace("_", ""));
        }
    }

    /**
     * Отправляет уведомление пользователю о доступном слоте с кнопками подтверждения/отказа
     */
    public void sendSlotOfferNotification(long chatId, Long userId, String eventId, String eventTitle, String message) {
        try {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText(message);
            sendMessage.setParseMode("Markdown");

            // Создаем кнопки для подтверждения/отказа
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboardRows = new ArrayList<>();

            List<InlineKeyboardButton> row = new ArrayList<>();

            // Кнопка подтверждения
            InlineKeyboardButton confirmButton = new InlineKeyboardButton();
            confirmButton.setText("✅ Подтвердить");
            confirmButton.setCallbackData("confirm_slot:" + eventId);
            row.add(confirmButton);

            // Кнопка отказа
            InlineKeyboardButton rejectButton = new InlineKeyboardButton();
            rejectButton.setText("❌ Отказаться");
            rejectButton.setCallbackData("reject_slot:" + eventId);
            row.add(rejectButton);

            keyboardRows.add(row);
            keyboard.setKeyboard(keyboardRows);
            sendMessage.setReplyMarkup(keyboard);

            execute(sendMessage);
            logger.info("Sent slot offer notification to user {} for event {}", userId, eventId);
        } catch (TelegramApiException e) {
            logger.error("Error sending slot offer notification to user {} for event {}", userId, eventId, e);
            // Fallback: send message without buttons
            try {
                sendMessageWithMarkdownInternal(chatId, message + "\n\nИспользуйте команды бота для ответа.");
            } catch (Exception ex) {
                logger.error("Error sending fallback message", ex);
            }
        }
    }

    private void saveMessageId(long chatId, Integer messageId) {
        chatMessages.computeIfAbsent(chatId, k -> new CopyOnWriteArrayList<>()).add(messageId);
    }

    /**
     * Проверяет, есть ли у пользователя кука, и если нет - пытается применить куку из переменной окружения
     * @param chatId ID чата для отправки сообщений
     * @param userId ID пользователя
     * @return true если у пользователя есть кука, false если нет
     */
    private boolean ensureCookieIsSet(long chatId, Long userId) {
        // Если у пользователя уже есть кука, возвращаем true
        if (userCookieService.hasCookie(userId)) {
            return true;
        }

        // Если куки нет, проверяем переменную окружения
        logger.debug("User {} has no cookie, checking environment variable. defaultCookie is null: {}, empty: {}", 
            userId, defaultCookie == null, defaultCookie != null && defaultCookie.trim().isEmpty());
        
        if (defaultCookie != null && !defaultCookie.trim().isEmpty()) {
            String cookie = defaultCookie.trim();
            logger.debug("Found cookie in environment variable, length: {}", cookie.length());
            if (validateCookie(cookie)) {
                userCookieService.setCookie(userId, cookie);
                logger.info("Auto-applied cookie from environment variable for user {}", userId);
                return true;
            } else {
                logger.warn("Cookie from environment variable is invalid for user {} (length: {})", userId, cookie.length());
                sendMessage(chatId, "❌ Кука из переменной окружения недействительна. Используйте /start для настройки.");
                return false;
            }
        }

        // Если куки нет ни у пользователя, ни в переменной окружения
        logger.debug("No cookie found for user {} and no environment variable cookie available", userId);
        sendMessage(chatId, "❌ Сначала необходимо сохранить куку. Используйте /start");
        return false;
    }

    private boolean validateCookie(String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            return false;
        }

        // Базовая валидация: кука должна содержать хотя бы одну пару name=value
        // Типичная кука выглядит как: name1=value1; name2=value2; ...
        String trimmed = cookie.trim();

        // Проверяем, что это не команда бота
        if (trimmed.startsWith("/")) {
            return false;
        }

        // Проверяем, что есть хотя бы один знак равенства (базовая структура куки)
        if (!trimmed.contains("=")) {
            return false;
        }

        // Минимальная длина куки (обычно куки довольно длинные, содержат много параметров)
        // Рабочая кука из run.sh была ~500+ символов
        if (trimmed.length() < 50) {
            logger.warn("Cookie seems too short ({} chars), might be missing important cookies", trimmed.length());
            // Не блокируем, но предупреждаем - возможно пользователь скопировал только часть
        }

        // Проверяем наличие важных кук (Session_id часто критична для авторизации)
        if (!trimmed.contains("Session_id") && !trimmed.contains("sessionid")) {
            logger.warn("Cookie doesn't contain Session_id - might not work for authentication");
            // Не блокируем, но предупреждаем
        }

        return true;
    }

    private void requestCookie(long chatId) {
        String instructions = """
🔐 Для работы бота необходима ваша персональная кука

Как получить куку:
1. Откройте https://events.yandex-team.ru и войдите в аккаунт
2. F12 → вкладка Network (Сеть)
3. Обновите страницу (F5)
4. Найдите любой запрос к events.yandex-team.ru
5. Кликните на запрос → вкладка Headers
6. В разделе "Request Headers" найдите "Cookie:"
7. Скопируйте ВСЁ значение после "Cookie: "
   (правой кнопкой на значении → Copy value)

Отправьте скопированную куку следующим сообщением.
        """;
        sendMessage(chatId, instructions);
    }

    /**
     * Проверяет, есть ли у пользователя город, и если нет - пытается применить город из переменной окружения
     * @param chatId ID чата для отправки сообщений
     * @param userId ID пользователя
     * @return true если у пользователя есть город, false если нет
     */
    private boolean ensureCityIsSet(long chatId, Long userId) {
        // Если у пользователя уже есть город, возвращаем true
        if (userPreferencesService.hasCity(userId)) {
            return true;
        }

        // Если города нет, проверяем переменную окружения
        if (defaultCityId != null && !defaultCityId.trim().isEmpty()) {
            try {
                int cityId = Integer.parseInt(defaultCityId.trim());
                userPreferencesService.setCity(userId, cityId);
                logger.info("Auto-applied default city {} from environment variable for user {}", cityId, userId);
                return true;
            } catch (NumberFormatException e) {
                logger.warn("Invalid default city ID in environment variable: {}", defaultCityId);
                sendMessage(chatId, "❌ Неверный ID города в переменной окружения. Используйте /start для настройки.");
                return false;
            }
        }

        // Если города нет ни у пользователя, ни в переменной окружения
        sendMessage(chatId, "❌ Сначала необходимо выбрать город. Используйте /start");
        return false;
    }

    /**
     * Применяет дефолтный город из переменных окружения, если он задан
     * @param chatId ID чата для отправки сообщений
     * @param userId ID пользователя
     * @return true если город был применен, false если не задан
     */
    private boolean applyDefaultCityIfSet(long chatId, Long userId) {
        if (defaultCityId != null && !defaultCityId.trim().isEmpty()) {
            try {
                int cityId = Integer.parseInt(defaultCityId.trim());
                userPreferencesService.setCity(userId, cityId);
                logger.info("Applied default city {} from environment variable for user {}", cityId, userId);
                
                // Пытаемся получить название города для более информативного сообщения
                String cityName = null;
                try {
                    List<City> cities = eventsService.getCities(userId);
                    if (cities != null) {
                        cityName = cities.stream()
                            .filter(c -> c.getId() == cityId)
                            .map(City::getName)
                            .findFirst()
                            .orElse(null);
                    }
                } catch (Exception e) {
                    logger.debug("Could not fetch city name for ID {}", cityId, e);
                }
                
                if (cityName != null) {
                    sendMessage(chatId, "✅ Город из переменной окружения применен: \"" + cityName + "\" (ID: " + cityId + ")");
                } else {
                    sendMessage(chatId, "✅ Город из переменной окружения применен (ID: " + cityId + ")");
                }
                return true;
            } catch (NumberFormatException e) {
                logger.warn("Invalid default city ID in environment variable: {}", defaultCityId);
                return false;
            }
        }
        return false;
    }

    /**
     * Проверяет, есть ли у пользователя категории, и если нет - пытается применить категории из переменной окружения
     * Если категории не заданы в переменной окружения, это не ошибка - пользователь может видеть все категории
     * @param chatId ID чата для отправки сообщений
     * @param userId ID пользователя
     * @return true всегда (категории не обязательны)
     */
    private boolean ensureCategoriesAreSet(long chatId, Long userId) {
        // Если у пользователя уже есть категории, возвращаем true
        Set<Integer> userCategories = userPreferencesService.getCategories(userId);
        if (userCategories != null && !userCategories.isEmpty()) {
            return true;
        }

        // Если категорий нет, проверяем переменную окружения
        if (defaultCategories != null && !defaultCategories.isEmpty()) {
            Set<Integer> categoryIds = new HashSet<>(defaultCategories);
            userPreferencesService.setCategories(userId, categoryIds);
            logger.info("Auto-applied default categories {} from environment variable for user {}", categoryIds, userId);
            return true;
        }

        // Если категорий нет ни у пользователя, ни в переменной окружения - это нормально
        // Пользователь будет видеть все категории
        return true;
    }

    /**
     * Применяет дефолтные категории из переменных окружения, если они заданы
     * @param chatId ID чата для отправки сообщений
     * @param userId ID пользователя
     * @return true если категории были применены, false если не заданы
     */
    private boolean applyDefaultCategoriesIfSet(long chatId, Long userId) {
        if (defaultCategories != null && !defaultCategories.isEmpty()) {
            Set<Integer> categoryIds = new HashSet<>(defaultCategories);
            userPreferencesService.setCategories(userId, categoryIds);
            logger.info("Applied default categories {} from environment variable for user {}", categoryIds, userId);
            
            // Пытаемся получить названия категорий для более информативного сообщения
            List<String> categoryNames = new ArrayList<>();
            try {
                List<Category> categories = eventsService.getCategories(userId);
                if (categories != null) {
                    for (Category cat : categories) {
                        if (categoryIds.contains(cat.getId())) {
                            categoryNames.add(cat.getName());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not fetch category names for IDs {}", categoryIds, e);
            }
            
            if (!categoryNames.isEmpty()) {
                String categoriesText = String.join(", ", categoryNames);
                sendMessage(chatId, "✅ Категории из переменной окружения применены: " + categoriesText + " (ID: " + categoryIds + ")");
            } else {
                sendMessage(chatId, "✅ Категории из переменной окружения применены (ID: " + categoryIds + ")");
            }
            return true;
        }
        return false;
    }

    private void showHelp(long chatId) {
        String helpMessage = """
📋 *Что умеет этот бот:*

🎯 *Основные возможности:*
• Просмотр списка событий с Яндекса
• Фильтрация событий по городу
• Фильтрация событий по категориям
• Запись на события прямо из бота
• Лист ожидания для событий без свободных мест
• Автоматическое предложение слотов пользователям из листа ожидания
• Автоматическое бронирование событий при их появлении (опрос событий)

🔧 *Команды:*
• `/start` - Начать работу с ботом (настройка куки, города и категорий)
• `/help` - Показать это сообщение
• `/refresh` - Обновить список событий
• `/reset_categories` - Перевыбрать категории событий для фильтрации
• `/start_poll` - Запустить автоматический опрос событий (бронирование при появлении)
• `/end_poll` - Остановить автоматический опрос событий

🧪 *Команды для тестирования:*
• `/hold_event_link <ссылка>` - Захолдить событие (эмулировать недоступность записи)
  Пример: `/hold_event_link https://events.yandex-team.ru/?city=1&eventId=b27b9fb8-895a-4b1d-bc56-704e92f46457`
• `/unhold` - Снять захолд со всех событий

📝 *Как начать:*
1. Используйте `/start` для настройки
2. Укажите свою куку от events.yandex-team.ru
3. Выберите город для фильтрации событий
4. Выберите категории событий (или пропустите, чтобы видеть все)

💡 *Советы:*
• После настройки бот будет показывать события согласно вашим фильтрам
• Используйте `/refresh` для обновления списка событий в любой момент
• Кнопки под событиями позволяют записаться или встать в лист ожидания
• Вы можете перевыбрать категории в любой момент командой `/reset_categories`
• Бот автоматически проверяет доступность слотов для событий из листа ожидания каждую секунду
• Используйте `/start_poll` для автоматического бронирования событий: настройте список имен событий в переменной окружения `EVENTS_POLL_NAMES`, и бот будет проверять их доступность каждую секунду
        """;
        sendMessageWithMarkdown(chatId, helpMessage);
    }

    private void requestCitySelection(long chatId, Long userId) {
        requestCitySelection(chatId, userId, false);
    }

    private void requestCitySelection(long chatId, Long userId, boolean showAll) {
        try {
            // Получаем список городов из API
            List<City> citiesList = eventsService.getCities(userId);
            if (citiesList == null || citiesList.isEmpty()) {
                sendMessage(chatId, "❌ Не удалось загрузить список городов. Попробуйте позже.");
                return;
            }

            // Преобразуем в список для возможности переупорядочивания
            List<Map.Entry<Integer, String>> citiesEntries = new ArrayList<>();
            for (City city : citiesList) {
                citiesEntries.add(Map.entry(city.getId(), city.getName()));
            }

            if (citiesEntries.isEmpty()) {
                sendMessage(chatId, "❌ Не найдено доступных городов.");
                return;
            }

            // Находим Москву и перемещаем её в начало
            Map.Entry<Integer, String> moscowEntry = null;
            for (Map.Entry<Integer, String> entry : citiesEntries) {
                String cityName = entry.getValue().toLowerCase();
                if (cityName.contains("москва") || cityName.equals("moscow")) {
                    moscowEntry = entry;
                    break;
                }
            }

            // Если нашли Москву, перемещаем её в начало
            if (moscowEntry != null) {
                citiesEntries.remove(moscowEntry);
                citiesEntries.add(0, moscowEntry);
            }

            userPreferencesService.setAwaitingCitySelection(userId, true);

            // Создаем клавиатуру с кнопками городов
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboardRows = new ArrayList<>();

            int citiesToShow = showAll ? citiesEntries.size() : Math.min(5, citiesEntries.size());

            for (int i = 0; i < citiesToShow; i++) {
                Map.Entry<Integer, String> entry = citiesEntries.get(i);
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText("📍 " + entry.getValue());
                button.setCallbackData("city:" + entry.getKey());
                row.add(button);
                keyboardRows.add(row);
            }

            // Если есть ещё города и мы показываем не все, добавляем кнопку "Показать ещё"
            if (!showAll && citiesEntries.size() > 5) {
                List<InlineKeyboardButton> showMoreRow = new ArrayList<>();
                InlineKeyboardButton showMoreButton = new InlineKeyboardButton();
                showMoreButton.setText("📋 Показать ещё (" + (citiesEntries.size() - 5) + ")");
                showMoreButton.setCallbackData("show_all_cities");
                showMoreRow.add(showMoreButton);
                keyboardRows.add(showMoreRow);
            }

            keyboard.setKeyboard(keyboardRows);

            String message = "🏙️ Выберите город для фильтрации событий:";
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText(message);
            sendMessage.setReplyMarkup(keyboard);

            execute(sendMessage);
        } catch (Exception e) {
            logger.error("Error requesting city selection", e);
            sendMessage(chatId, "❌ Произошла ошибка при загрузке списка городов. Попробуйте позже.");
        }
    }

    private void handleCitySelection(long chatId, Long userId, String cityInput) {
        try {
            // Пытаемся распарсить как число (ID города)
            int cityId = Integer.parseInt(cityInput);
            userPreferencesService.setCity(userId, cityId);
            userPreferencesService.setAwaitingCitySelection(userId, false);
            sendMessage(chatId, "✅ Город выбран!");
            requestCategorySelection(chatId, userId);
        } catch (NumberFormatException e) {
            // Если не число, пытаемся найти по имени из списка городов
            List<City> citiesList = eventsService.getCities(userId);
            if (citiesList == null || citiesList.isEmpty()) {
                sendMessage(chatId, "❌ Не удалось найти город. Используйте кнопки для выбора.");
                return;
            }

            // Ищем город по имени (без учета регистра)
            City foundCity = null;
            for (City city : citiesList) {
                if (city.getName().equalsIgnoreCase(cityInput)) {
                    foundCity = city;
                    break;
                }
            }

            if (foundCity != null) {
                userPreferencesService.setCity(userId, foundCity.getId());
                userPreferencesService.setAwaitingCitySelection(userId, false);
                sendMessage(chatId, "✅ Город \"" + foundCity.getName() + "\" выбран!");
                // Проверяем, есть ли дефолтные категории
                if (applyDefaultCategoriesIfSet(chatId, userId)) {
                    // Если дефолтные категории применены, загружаем события
                    try {
                        var events = eventsService.getEvents(userId);
                        if (events != null && !events.isEmpty()) {
                            sendEventsWithButtons(chatId, userId, events);
                        } else {
                            sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
                        }
                    } catch (Exception ex) {
                        logger.error("Error getting events after default categories", ex);
                        sendMessage(chatId, "❌ Произошла ошибка при загрузке событий. Попробуйте позже.");
                    }
                } else {
                    // Если дефолтных категорий нет, запрашиваем выбор
                    requestCategorySelection(chatId, userId);
                }
            } else {
                sendMessage(chatId, "❌ Город не найден. Используйте кнопки для выбора.");
            }
        }
    }

    private void requestCategorySelection(long chatId, Long userId) {
        try {
            // Получаем список категорий из API для проверки наличия
            List<Category> categoriesList = eventsService.getCategories(userId);
            if (categoriesList == null || categoriesList.isEmpty()) {
                sendMessage(chatId, "✅ Настройка завершена! Категории не найдены - будут показываться все события.");
                // Если категорий нет, отправляем события
                var events = eventsService.getEvents(userId);
                if (events != null && !events.isEmpty()) {
                    sendEventsWithButtons(chatId, userId, events);
                }
                return;
            }

            userPreferencesService.setAwaitingCategorySelection(userId, true);

            // Создаем клавиатуру с кнопками категорий
            InlineKeyboardMarkup keyboard = createCategoryKeyboard(userId);

            String message = "📌 Выберите категории событий для фильтрации:\n\n" +
                           "Можно выбрать несколько категорий или не выбирать ничего.\n" +
                           "Нажмите на категорию, чтобы выбрать/снять выбор.\n" +
                           "Когда закончите, нажмите \"Готово\".";
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText(message);
            sendMessage.setReplyMarkup(keyboard);

            execute(sendMessage);
        } catch (Exception e) {
            logger.error("Error requesting category selection", e);
            sendMessage(chatId, "❌ Произошла ошибка при загрузке списка категорий. Попробуйте позже.");
        }
    }

    private InlineKeyboardMarkup createCategoryKeyboard(Long userId) {
        try {
            // Получаем список категорий из API
            List<Category> categoriesList = eventsService.getCategories(userId);
            if (categoriesList == null || categoriesList.isEmpty()) {
                return new InlineKeyboardMarkup();
            }

            // Преобразуем в Map для удобства
            Map<Integer, String> categories = new LinkedHashMap<>();
            for (Category category : categoriesList) {
                categories.put(category.getId(), category.getName());
            }

            // Получаем уже выбранные категории (если есть)
            Set<Integer> selectedCategories = userPreferencesService.getCategories(userId);
            if (selectedCategories == null) {
                selectedCategories = new HashSet<>();
            }

            // Создаем клавиатуру с кнопками категорий
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboardRows = new ArrayList<>();

            for (Map.Entry<Integer, String> entry : categories.entrySet()) {
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton button = new InlineKeyboardButton();
                boolean isSelected = selectedCategories.contains(entry.getKey());
                button.setText((isSelected ? "✅ " : "⬜ ") + entry.getValue());
                button.setCallbackData("category:" + entry.getKey());
                row.add(button);
                keyboardRows.add(row);
            }

            // Добавляем кнопку "Готово"
            List<InlineKeyboardButton> actionRow = new ArrayList<>();
            InlineKeyboardButton doneButton = new InlineKeyboardButton();
            doneButton.setText("✅ Готово");
            doneButton.setCallbackData("categories_done");
            actionRow.add(doneButton);

            keyboardRows.add(actionRow);
            keyboard.setKeyboard(keyboardRows);

            return keyboard;
        } catch (Exception e) {
            logger.error("Error creating category keyboard", e);
            return new InlineKeyboardMarkup();
        }
    }

    private void handleCategoryToggle(long chatId, Long userId, String categoryIdStr, String callbackQueryId, Integer messageId) {
        try {
            int categoryId = Integer.parseInt(categoryIdStr);
            Set<Integer> selectedCategories = userPreferencesService.getCategories(userId);
            if (selectedCategories == null) {
                selectedCategories = new HashSet<>();
            } else {
                // Создаем новый HashSet для возможности изменения
                selectedCategories = new HashSet<>(selectedCategories);
            }

            // Переключаем выбор категории
            boolean wasSelected = selectedCategories.contains(categoryId);
            if (wasSelected) {
                selectedCategories.remove(categoryId);
            } else {
                selectedCategories.add(categoryId);
            }

            // Сохраняем промежуточное состояние (еще не завершено)
            userPreferencesService.setCategories(userId, selectedCategories);

            // Обновляем только клавиатуру в существующем сообщении
            if (messageId != null) {
                try {
                    InlineKeyboardMarkup keyboard = createCategoryKeyboard(userId);

                    EditMessageReplyMarkup editMarkup = new EditMessageReplyMarkup();
                    editMarkup.setChatId(String.valueOf(chatId));
                    editMarkup.setMessageId(messageId);
                    editMarkup.setReplyMarkup(keyboard);

                    execute(editMarkup);
                } catch (Exception e) {
                    logger.debug("Could not update category selection keyboard: {}", e.getMessage());
                }
            }

            // Отвечаем на callback без показа alert
            answerCallbackQuery(callbackQueryId, "");
        } catch (NumberFormatException e) {
            answerCallbackQuery(callbackQueryId, "Ошибка выбора категории");
            logger.error("Error parsing category ID: {}", categoryIdStr, e);
        }
    }

    private void handleCategoriesDone(long chatId, Long userId, String callbackQueryId) {
        userPreferencesService.setAwaitingCategorySelection(userId, false);

        Set<Integer> selectedCategories = userPreferencesService.getCategories(userId);
        if (selectedCategories == null || selectedCategories.isEmpty()) {
            sendMessage(chatId, "✅ Настройка завершена! Категории не выбраны - будут показываться все события.");
        } else {
            sendMessage(chatId, "✅ Настройка завершена! Выбрано категорий: " + selectedCategories.size());
        }

        answerCallbackQuery(callbackQueryId, "Выбор категорий завершен");

        // Отправляем события с учетом фильтров
        try {
            var events = eventsService.getEvents(userId);
            if (events != null && !events.isEmpty()) {
                sendEventsWithButtons(chatId, userId, events);
            } else {
                sendMessage(chatId, "📅 События не найдены по вашим фильтрам.");
            }
        } catch (Exception e) {
            logger.error("Error getting events after category selection", e);
            sendMessage(chatId, "❌ Произошла ошибка при загрузке событий. Попробуйте позже.");
        }
    }

    @Override
    public String getBotUsername() {
        return botConfig.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return botConfig.getBotToken();
    }

    @Override
    public void clearWebhook() {
        try {
            super.clearWebhook();
        } catch (TelegramApiException e) {
            // Handle 404 error gracefully - it's normal if no webhook was previously set
            if (e instanceof TelegramApiRequestException) {
                TelegramApiRequestException apiException = (TelegramApiRequestException) e;
                if (apiException.getErrorCode() == 404) {
                    logger.debug("No existing webhook to clear (this is normal)");
                    return; // Ignore 404 error
                }
            }
            // For other exceptions, log but don't throw (since interface doesn't allow it)
            logger.warn("Error clearing webhook: {}", e.getMessage());
        }
    }
}


