package com.example.telegrambot.service;

import com.example.telegrambot.dto.Category;
import com.example.telegrambot.dto.City;
import com.example.telegrambot.dto.Event;
import com.example.telegrambot.dto.EventsResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class EventsService {

    private static final Logger logger = LoggerFactory.getLogger(EventsService.class);
    private static final String EVENTS_API_URL_BASE = "https://events.yandex-team.ru/back/events/?_limit=100&_page=1&category__id=%s&city__id=%d&group__id=";
    private static final String EVENT_BY_ID_API_URL = "https://events.yandex-team.ru/back/events/%s/";
    private static final String CITIES_API_URL = "https://events.yandex-team.ru/back/events/cities/";
    private static final String CATEGORIES_API_URL = "https://events.yandex-team.ru/back/events/categories/";

    private final RestTemplate restTemplate;
    private final UserCookieService userCookieService;
    private final UserPreferencesService userPreferencesService;
    private final ObjectMapper objectMapper;

    public EventsService(UserCookieService userCookieService, UserPreferencesService userPreferencesService) {
        this.restTemplate = new RestTemplate();
        this.userCookieService = userCookieService;
        this.userPreferencesService = userPreferencesService;
        this.objectMapper = new ObjectMapper();
    }

    public List<Event> getEvents(Long userId) {
        String cookies = userId != null ? userCookieService.getCookie(userId) : null;
        Integer cityId = userId != null ? userPreferencesService.getCity(userId) : null;
        Set<Integer> categoryIds = userId != null ? userPreferencesService.getCategories(userId) : null;
        return getEvents(cookies, cityId, categoryIds);
    }

    public List<Event> getEvents(String cookies) {
        return getEvents(cookies, null, null);
    }

    public List<Event> getEvents(String cookies, Integer cityId, Set<Integer> categoryIds) {
        try {
            // Используем сохраненный город пользователя или дефолтный (1)
            int selectedCityId = (cityId != null && cityId > 0) ? cityId : 1;

            // Формируем строку категорий для URL
            String categoryParam = "";
            if (categoryIds != null && !categoryIds.isEmpty()) {
                // Преобразуем Set в строку через запятую
                categoryParam = categoryIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            }

            String eventsUrl = String.format(EVENTS_API_URL_BASE, categoryParam, selectedCityId);

            HttpHeaders headers = createHeaders(cookies, selectedCityId);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            if (cookies != null && !cookies.isEmpty()) {
                logger.debug("Making request with cookies (length: {}), city {}, categories {}",
                    cookies.length(), selectedCityId, categoryParam.isEmpty() ? "none" : categoryParam);
            } else {
                logger.warn("Making request without cookies - this may result in authorization error");
            }

            ResponseEntity<String> rawResponse = restTemplate.exchange(
                eventsUrl,
                HttpMethod.GET,
                entity,
                String.class
            );

            // Проверяем, что ответ действительно JSON
            String responseBody = rawResponse.getBody();
            if (responseBody != null && responseBody.trim().startsWith("<")) {
                logger.error("API returned HTML instead of JSON. Response starts with: {}",
                    responseBody.substring(0, Math.min(200, responseBody.length())));
                logger.error("This usually means the cookie is invalid or expired");
                return new ArrayList<>();
            }

            // Парсим JSON вручную для лучшей обработки ошибок
            ResponseEntity<EventsResponse> response = restTemplate.exchange(
                eventsUrl,
                HttpMethod.GET,
                entity,
                EventsResponse.class
            );

            EventsResponse eventsResponse = response.getBody();
            List<Event> events = new ArrayList<>();
            if (eventsResponse != null && eventsResponse.getResult() != null) {
                events = eventsResponse.getResult();
                logger.info("Successfully retrieved {} events for city {} and categories {}",
                    events.size(), selectedCityId, categoryParam.isEmpty() ? "all" : categoryParam);
            }

            return events;
        } catch (RestClientException e) {
            logger.error("Error fetching events from API", e);
            if (e.getMessage() != null && e.getMessage().contains("text/html")) {
                logger.error("API returned HTML page - cookie is likely invalid or expired. User needs to provide a fresh cookie.");
            }
            return new ArrayList<>();
        }
    }

    private HttpHeaders createHeaders(String cookies, int cityId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 YaBrowser/25.8.0.0 Safari/537.36");
        headers.set("Accept", "application/json");
        headers.set("Accept-Language", "ru");
        headers.set("Referer", String.format("https://events.yandex-team.ru/?city=%d", cityId));
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-origin");

        if (cookies != null && !cookies.isEmpty()) {
            // Нормализуем куку: удаляем лишние пробелы, но сохраняем структуру
            String normalizedCookie = cookies.trim();
            headers.set("Cookie", normalizedCookie);
        }

        return headers;
    }

    public String formatEventsMessage(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return "Событий не найдено.";
        }

        StringBuilder message = new StringBuilder();
        message.append("📅 *Список событий:*\n\n");

        for (int i = 0; i < Math.min(events.size(), 10); i++) {
            Event event = events.get(i);
            message.append("*").append(i + 1).append(". ").append(event.getTitle()).append("*\n");

            if (event.getShortDescription() != null && !event.getShortDescription().trim().isEmpty()) {
                String description = event.getShortDescription()
                    .replace("\r\n", " ")
                    .replace("\n", " ")
                    .trim();
                if (description.length() > 100) {
                    description = description.substring(0, 97) + "...";
                }
                message.append(description).append("\n");
            }

            // Категории
            if (event.getCategory() != null && !event.getCategory().isEmpty()) {
                String categories = event.getCategory().stream()
                    .map(cat -> cat.getName())
                    .collect(Collectors.joining(", "));
                message.append("📌 ").append(categories).append("\n");
            }

            // Даты
            if (event.getDatesOf() != null && !event.getDatesOf().isEmpty()) {
                String dates = event.getDatesOf().stream()
                    .map(dateStr -> formatDate(dateStr))
                    .collect(Collectors.joining(", "));
                message.append("📆 ").append(dates).append("\n");
            }

            // Места
            if (event.isHaveFreeSeats()) {
                message.append("✅ Свободных мест: ").append(event.getFreeSeats()).append("\n");
            } else {
                message.append("❌ Мест нет\n");
            }

            message.append("\n");
        }

        if (events.size() > 10) {
            message.append("... и еще ").append(events.size() - 10).append(" событий");
        }

        return message.toString();
    }

    public String formatSingleEvent(Event event) {
        return formatSingleEvent(event, false);
    }

    public String formatSingleEvent(Event event, boolean isBooked) {
        if (event == null) {
            return "Информация о событии недоступна.";
        }

        StringBuilder message = new StringBuilder();

        // Экранируем специальные символы Markdown в заголовке
        String escapedTitle = escapeMarkdown(event.getTitle());

        // Если пользователь записан, выделяем зелеными индикаторами
        if (isBooked) {
            message.append("🟢🟢🟢 ").append("*").append(escapedTitle).append("*").append(" 🟢🟢🟢\n\n");
        } else {
            message.append("*").append(escapedTitle).append("*\n\n");
        }

        if (event.getShortDescription() != null && !event.getShortDescription().trim().isEmpty()) {
            String description = event.getShortDescription()
                .replace("\r\n", " ")
                .replace("\n", " ")
                .trim();
            if (description.length() > 200) {
                description = description.substring(0, 197) + "...";
            }
            message.append(description).append("\n\n");
        }

        // Категории
        if (event.getCategory() != null && !event.getCategory().isEmpty()) {
            String categories = event.getCategory().stream()
                .map(cat -> cat.getName())
                .collect(Collectors.joining(", "));
            message.append("📌 ").append(categories).append("\n");
        }

        // Даты
        if (event.getDatesOf() != null && !event.getDatesOf().isEmpty()) {
            String dates = event.getDatesOf().stream()
                .map(dateStr -> formatDate(dateStr))
                .collect(Collectors.joining(", "));
            message.append("📆 ").append(dates).append("\n");
        }

        // Места
        if (event.isHaveFreeSeats()) {
            message.append("✅ Свободных мест: ").append(event.getFreeSeats());
        } else {
            message.append("❌ Мест нет");
        }

        return message.toString();
    }

    public String getEventUrl(Event event) {
        String eventId = event.getId();
        int cityId = 1;
        if (event.getCity() != null) {
            cityId = event.getCity().getId();
        }
        return String.format("https://events.yandex-team.ru/events/%s?city=%d&eventId=%s",
            eventId, cityId, eventId);
    }

    /**
     * Экранирует специальные символы Markdown в тексте
     * Для заголовка, обернутого в *, нужно экранировать только символы, которые могут сломать форматирование
     */
    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        // Экранируем символы, которые могут сломать форматирование внутри *текста*
        // Не экранируем * сам, так как он используется для обёртки
        return text
            .replace("\\", "\\\\")
            .replace("_", "\\_");
    }

    /**
     * Получает список доступных городов из API
     */
    public List<City> getCities(Long userId) {
        String cookies = userId != null ? userCookieService.getCookie(userId) : null;
        return getCities(cookies);
    }

    /**
     * Получает список доступных городов из API
     */
    public List<City> getCities(String cookies) {
        try {
            HttpHeaders headers = createHeaders(cookies, 1); // Используем дефолтный город для headers
            HttpEntity<String> entity = new HttpEntity<>(headers);

            if (cookies != null && !cookies.isEmpty()) {
                logger.debug("Making request for cities with cookies (length: {})", cookies.length());
            } else {
                logger.warn("Making request for cities without cookies - this may result in authorization error");
            }

            ResponseEntity<String> rawResponse = restTemplate.exchange(
                CITIES_API_URL,
                HttpMethod.GET,
                entity,
                String.class
            );

            // Проверяем, что ответ действительно JSON
            String responseBody = rawResponse.getBody();
            if (responseBody != null && responseBody.trim().startsWith("<")) {
                logger.error("Cities API returned HTML instead of JSON. Response starts with: {}",
                    responseBody.substring(0, Math.min(200, responseBody.length())));
                logger.error("This usually means the cookie is invalid or expired");
                return new ArrayList<>();
            }

            // Парсим JSON как массив городов
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                List<City> cities = objectMapper.readValue(responseBody,
                    new TypeReference<List<City>>() {});
                logger.info("Successfully retrieved {} cities", cities.size());
                return cities;
            }

            return new ArrayList<>();
        } catch (RestClientException e) {
            logger.error("Error fetching cities from API", e);
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Error parsing cities response", e);
            return new ArrayList<>();
        }
    }

    /**
     * Получает список доступных категорий из API
     */
    public List<Category> getCategories(Long userId) {
        String cookies = userId != null ? userCookieService.getCookie(userId) : null;
        return getCategories(cookies);
    }

    /**
     * Получает список доступных категорий из API
     */
    public List<Category> getCategories(String cookies) {
        try {
            HttpHeaders headers = createHeaders(cookies, 1); // Используем дефолтный город для headers
            HttpEntity<String> entity = new HttpEntity<>(headers);

            if (cookies != null && !cookies.isEmpty()) {
                logger.debug("Making request for categories with cookies (length: {})", cookies.length());
            } else {
                logger.warn("Making request for categories without cookies - this may result in authorization error");
            }

            ResponseEntity<String> rawResponse = restTemplate.exchange(
                CATEGORIES_API_URL,
                HttpMethod.GET,
                entity,
                String.class
            );

            // Проверяем, что ответ действительно JSON
            String responseBody = rawResponse.getBody();
            if (responseBody != null && responseBody.trim().startsWith("<")) {
                logger.error("Categories API returned HTML instead of JSON. Response starts with: {}",
                    responseBody.substring(0, Math.min(200, responseBody.length())));
                logger.error("This usually means the cookie is invalid or expired");
                return new ArrayList<>();
            }

            // Парсим JSON как массив категорий
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                List<Category> categories = objectMapper.readValue(responseBody,
                    new TypeReference<List<Category>>() {});
                logger.info("Successfully retrieved {} categories", categories.size());
                return categories;
            }

            return new ArrayList<>();
        } catch (RestClientException e) {
            logger.error("Error fetching categories from API", e);
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Error parsing categories response", e);
            return new ArrayList<>();
        }
    }

    /**
     * Получает событие по ID из API
     *
     * @param eventId ID события
     * @param userId ID пользователя для получения куки
     * @return Event объект или null, если событие не найдено
     */
    public Event getEventById(String eventId, Long userId) {
        String cookies = userId != null ? userCookieService.getCookie(userId) : null;
        return getEventById(eventId, cookies);
    }

    /**
     * Получает событие по ID из API
     * Использует альтернативные методы, так как прямой endpoint /back/events/{eventId}/ не работает
     *
     * @param eventId ID события
     * @param cookies Куки для авторизации
     * @return Event объект или null, если событие не найдено
     */
    public Event getEventById(String eventId, String cookies) {
        logger.debug("Fetching event by ID: {}", eventId);

        // Пробуем через query параметры
        Event event = getEventByIdViaQueryParam(eventId, cookies);
        if (event != null) {
            return event;
        }

        // Если не получилось, ищем в списке событий
        logger.debug("Event {} not found via query params, trying events list", eventId);
        return getEventByIdFromList(eventId, cookies);
    }

    /**
     * Получает событие по ID через API с query параметром eventId
     * Альтернативный способ, когда прямой endpoint возвращает 404
     *
     * @param eventId ID события
     * @param cookies Куки для авторизации
     * @return Event объект или null, если событие не найдено
     */
    private Event getEventByIdViaQueryParam(String eventId, String cookies) {
        try {
            // Пробуем использовать query параметр eventId или event__id
            String[] queryParams = {
                String.format("https://events.yandex-team.ru/back/events/?eventId=%s", eventId),
                String.format("https://events.yandex-team.ru/back/events/?event__id=%s", eventId),
                String.format("https://events.yandex-team.ru/back/events/?id=%s", eventId)
            };

            HttpHeaders headers = createHeaders(cookies, 1);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            for (String url : queryParams) {
                try {
                    logger.debug("Trying to fetch event {} via query param URL: {}", eventId, url);
                    ResponseEntity<String> rawResponse = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        String.class
                    );

                    String responseBody = rawResponse.getBody();
                    if (responseBody != null && !responseBody.trim().startsWith("<") && !responseBody.trim().isEmpty()) {
                        // Пробуем парсить как список событий или одно событие
                        try {
                            // Сначала пробуем как EventsResponse (список)
                            EventsResponse eventsResponse = objectMapper.readValue(responseBody, EventsResponse.class);
                            if (eventsResponse != null && eventsResponse.getResult() != null && !eventsResponse.getResult().isEmpty()) {
                                Event foundEvent = eventsResponse.getResult().stream()
                                    .filter(event -> eventId.equals(event.getId()))
                                    .findFirst()
                                    .orElse(null);
                                if (foundEvent != null) {
                                    logger.debug("Found event {} via query param URL: {}", eventId, url);
                                    return foundEvent;
                                }
                            }
                        } catch (Exception e) {
                            // Если не получилось как список, пробуем как одно событие
                            try {
                                Event event = objectMapper.readValue(responseBody, Event.class);
                                if (event != null && eventId.equals(event.getId())) {
                                    logger.debug("Found event {} via query param URL: {}", eventId, url);
                                    return event;
                                }
                            } catch (Exception e2) {
                                logger.debug("Failed to parse response from {}: {}", url, e2.getMessage());
                            }
                        }
                    }
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() != 404) {
                        logger.debug("HTTP error {} for URL {}: {}", e.getStatusCode(), url, e.getMessage());
                    }
                    // Продолжаем пробовать другие варианты
                } catch (Exception e) {
                    logger.debug("Error trying URL {}: {}", url, e.getMessage());
                }
            }

            return null;
        } catch (Exception e) {
            logger.debug("Error fetching event {} via query param: {}", eventId, e.getMessage());
            return null;
        }
    }

    /**
     * Получает событие по ID, ища его в списке всех событий
     * Используется как fallback, когда прямой endpoint возвращает 404
     *
     * @param eventId ID события
     * @param cookies Куки для авторизации
     * @return Event объект или null, если событие не найдено
     */
    private Event getEventByIdFromList(String eventId, String cookies) {
        try {
            logger.debug("Trying to find event {} in events list", eventId);
            // Получаем все события (без фильтрации по категориям)
            List<Event> events = getEvents(cookies, null, null);

            // Ищем событие по ID
            Event foundEvent = events.stream()
                .filter(event -> eventId.equals(event.getId()))
                .findFirst()
                .orElse(null);

            if (foundEvent != null) {
                logger.debug("Found event {} in events list: {}", eventId, foundEvent.getTitle());
                return foundEvent;
            } else {
                logger.warn("Event {} not found in events list either", eventId);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error searching for event {} in events list", eventId, e);
            return null;
        }
    }

    private String formatDate(String dateString) {
        try {
            // Parse ISO 8601 format: 2025-11-05T18:00:00Z
            String dateStr = dateString;
            if (dateStr.endsWith("Z")) {
                dateStr = dateStr.replace("Z", "");
            }
            LocalDateTime dateTime = LocalDateTime.parse(dateStr);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            return dateTime.format(formatter);
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse date: {}", dateString);
            return dateString;
        }
    }

}

