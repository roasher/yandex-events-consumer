package com.example.telegrambot.service;

import com.example.telegrambot.exception.RateLimitException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class YandexEventsBookingService {

    private static final String BOOKING_URL = "https://events.yandex-team.ru/back/events/booking/";
    private static final Logger logger = LoggerFactory.getLogger(YandexEventsBookingService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YandexEventsBookingService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
        // Не падаем на 4xx, т.к. API может вернуть валидный JSON с причиной ошибки
        this.restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    public record BookingRequest(long timeSlot, int extraAdults, int extraChildren) {}

    public JsonNode book(String cookieHeader,
                         String referer,
                         String userAgent,
                         BookingRequest bookingRequest) {
        logger.info("Booking request: timeSlot={}, extraAdults={}, extraChildren={}, referer={}",
                bookingRequest.timeSlot(), bookingRequest.extraAdults(), bookingRequest.extraChildren(), referer);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept-Language", "ru");
        headers.set("Connection", "keep-alive");
        headers.set("Cookie", cookieHeader);
        headers.set("Origin", "https://events.yandex-team.ru");
        headers.set("Referer", referer);
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("User-Agent", userAgent);
        headers.set("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
        headers.set("sec-ch-ua-arch", "\"arm\"");
        headers.set("sec-ch-ua-bitness", "\"64\"");
        headers.set("sec-ch-ua-full-version-list", "\"Not)A;Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"138.0.7204.977\", \"YaBrowser\";v=\"25.8.5.977\", \"Yowser\";v=\"2.5\"");
        headers.set("sec-ch-ua-mobile", "?0");
        headers.set("sec-ch-ua-platform", "\"macOS\"");
        headers.set("sec-ch-ua-platform-version", "\"15.7.1\"");
        headers.set("sec-ch-ua-wow64", "?0");

        String payload;
        try {
            payload = objectMapper.writeValueAsString(bookingRequest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize booking request", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(payload, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(BOOKING_URL, entity, String.class);
        logger.info("Booking response status: {}", response.getStatusCode());
        logger.info("Booking response body: {}", response.getBody());

        if (response.getStatusCode().value() == 429) {
            throw new RateLimitException("Booking API rate limited (429). Retry after delay.");
        }

        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            logger.error("Failed to parse booking response body as JSON", e);
            throw new IllegalStateException("Booking API returned non-JSON body", e);
        }
    }

    public JsonNode getTimeSlots(String eventId,
                                 String cookieHeader,
                                 String referer,
                                 String userAgent) {
        String url = String.format("https://events.yandex-team.ru/back/events/%s/timeslots", eventId);
        logger.info("Fetching timeslots: eventId={}, referer={}", eventId, referer);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Accept-Language", "ru");
        headers.set("Connection", "keep-alive");
        headers.set("Cookie", cookieHeader);
        headers.set("Referer", referer);
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("User-Agent", userAgent);
        headers.set("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
        headers.set("sec-ch-ua-arch", "\"arm\"");
        headers.set("sec-ch-ua-bitness", "\"64\"");
        headers.set("sec-ch-ua-full-version-list", "\"Not)A;Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"138.0.7204.977\", \"YaBrowser\";v=\"25.8.5.977\", \"Yowser\";v=\"2.5\"");
        headers.set("sec-ch-ua-mobile", "?0");
        headers.set("sec-ch-ua-platform", "\"macOS\"");
        headers.set("sec-ch-ua-platform-version", "\"15.7.1\"");
        headers.set("sec-ch-ua-wow64", "?0");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        logger.info("Timeslots response status: {}", response.getStatusCode());
        logger.info("Timeslots response body: {}", response.getBody());

        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            logger.error("Failed to parse timeslots response body as JSON", e);
            throw new IllegalStateException("Timeslots API returned non-JSON body", e);
        }
    }

    /**
     * Получает все регистрации пользователя через API.
     *
     * @param cookieHeader Cookie с авторизацией пользователя
     * @param userAgent User-Agent для запроса
     * @return Set с ID событий, на которые пользователь зарегистрирован
     */
    public Set<String> getUserBookings(String cookieHeader, String userAgent) {
        try {
            // Используем текущую дату в UTC для фильтрации будущих событий
            // Формат: 2025-11-01T16:23:48.082Z (с миллисекундами и Z)
            // UriComponentsBuilder автоматически закодирует двоеточия как %3A
            Instant now = Instant.now();
            String endDateTimeParam = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(now);

            // Используем UriComponentsBuilder для правильного URL-кодирования
            // Он автоматически закодирует : как %3A и другие специальные символы
            String url = UriComponentsBuilder.fromHttpUrl("https://events.yandex-team.ru/back/events/bookings/")
                .queryParam("end_datetime__gte", endDateTimeParam)
                .toUriString();

            logger.info("Fetching user bookings: end_datetime__gte={}", endDateTimeParam);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("Accept-Language", "ru");
            headers.set("Connection", "keep-alive");
            headers.set("Cookie", cookieHeader);
            headers.set("Referer", "https://events.yandex-team.ru/my/tickets");
            headers.set("Sec-Fetch-Dest", "empty");
            headers.set("Sec-Fetch-Mode", "cors");
            headers.set("Sec-Fetch-Site", "same-origin");
            headers.set("User-Agent", userAgent);
            headers.set("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
            headers.set("sec-ch-ua-arch", "\"arm\"");
            headers.set("sec-ch-ua-bitness", "\"64\"");
            headers.set("sec-ch-ua-full-version-list", "\"Not)A;Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"138.0.7204.977\", \"YaBrowser\";v=\"25.8.5.977\", \"Yowser\";v=\"2.5\"");
            headers.set("sec-ch-ua-mobile", "?0");
            headers.set("sec-ch-ua-platform", "\"macOS\"");
            headers.set("sec-ch-ua-platform-version", "\"15.7.1\"");
            headers.set("sec-ch-ua-wow64", "?0");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            logger.info("User bookings response status: {}", response.getStatusCode());
            logger.info("User bookings response body: {}", response.getBody());

            Set<String> bookedEventIds = new HashSet<>();

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode bookingsJson = objectMapper.readTree(response.getBody());

                // Проверяем разные форматы ответа
                JsonNode results = bookingsJson;
                if (bookingsJson.has("result") && bookingsJson.get("result").isArray()) {
                    results = bookingsJson.get("result");
                } else if (bookingsJson.isArray()) {
                    results = bookingsJson;
                }

                if (results.isArray()) {
                    for (JsonNode booking : results) {
                        // Извлекаем event_id из регистрации
                        if (booking.has("event_id")) {
                            String eventId = booking.get("event_id").asText();
                            bookedEventIds.add(eventId);
                        } else if (booking.has("eventId")) {
                            String eventId = booking.get("eventId").asText();
                            bookedEventIds.add(eventId);
                        } else if (booking.has("event")) {
                            JsonNode eventNode = booking.get("event");
                            if (eventNode.isTextual()) {
                                bookedEventIds.add(eventNode.asText());
                            } else if (eventNode.has("id")) {
                                bookedEventIds.add(eventNode.get("id").asText());
                            }
                        }
                    }
                }
            }

            logger.info("Found {} user bookings", bookedEventIds.size());
            return bookedEventIds;
        } catch (Exception e) {
            logger.error("Failed to get user bookings", e);
            return new HashSet<>();
        }
    }

    /**
     * Проверяет статус регистрации пользователя на событие через API.
     * Использует список всех регистраций пользователя для проверки.
     *
     * @param eventId ID события
     * @param cookieHeader Cookie с авторизацией пользователя
     * @param referer Referer для запроса
     * @param userAgent User-Agent для запроса
     * @return true если пользователь зарегистрирован на событие, false в противном случае
     */
    public boolean isUserBooked(String eventId,
                               String cookieHeader,
                               String referer,
                               String userAgent) {
        Set<String> userBookings = getUserBookings(cookieHeader, userAgent);
        return userBookings.contains(eventId);
    }

    /**
     * Получает ID регистрации (bookingId) по ID события.
     * Использует метод getTimeSlots() для получения списка слотов и ищет booking.id в ответе.
     *
     * @param eventId ID события
     * @param cookieHeader Cookie с авторизацией пользователя
     * @param referer Referer для запроса
     * @param userAgent User-Agent для запроса
     * @return bookingId если регистрация найдена, null в противном случае
     */
    public String getBookingId(String eventId, String cookieHeader, String referer, String userAgent) {
        try {
            logger.info("Getting bookingId for eventId: {} from timeslots", eventId);
            
            // Используем существующий метод getTimeSlots()
            JsonNode timeslotsJson = getTimeSlots(eventId, cookieHeader, referer, userAgent);
            logger.debug("Timeslots response: {}", timeslotsJson);

            // Ответ может быть массивом слотов или объектом с полем result/result
            JsonNode slots = timeslotsJson;
            if (timeslotsJson.has("result") && timeslotsJson.get("result").isArray()) {
                slots = timeslotsJson.get("result");
            } else if (timeslotsJson.isArray()) {
                slots = timeslotsJson;
            }

            if (slots.isArray()) {
                logger.debug("Found {} timeslots", slots.size());
                
                // Ищем в массиве слотов тот, где есть booking.id (это означает, что пользователь зарегистрирован)
                for (JsonNode slot : slots) {
                    if (slot.has("booking") && slot.get("booking").has("id")) {
                        JsonNode booking = slot.get("booking");
                        String bookingId = null;
                        if (booking.get("id").isNumber()) {
                            bookingId = String.valueOf(booking.get("id").asLong());
                        } else {
                            bookingId = booking.get("id").asText();
                        }
                        
                        if (bookingId != null && !bookingId.isEmpty()) {
                            logger.info("Found bookingId: {} for eventId: {}", bookingId, eventId);
                            return bookingId;
                        }
                    }
                }
                
                logger.warn("No booking found in timeslots for eventId: {} (user may not be registered)", eventId);
            } else {
                logger.warn("Timeslots response is not an array: {}", slots);
            }
            
            return null;
        } catch (Exception e) {
            logger.error("Failed to get bookingId for event {}", eventId, e);
            return null;
        }
    }

    /**
     * Отменяет регистрацию пользователя на событие.
     *
     * @param eventId ID события
     * @param cookieHeader Cookie с авторизацией пользователя
     * @param referer Referer для запроса
     * @param userAgent User-Agent для запроса
     * @return true если отмена прошла успешно, false в противном случае
     */
    public boolean unbook(String eventId,
                          String cookieHeader,
                          String referer,
                          String userAgent) {
        try {
            // Сначала получаем bookingId по eventId из /timeslots
            String bookingId = getBookingId(eventId, cookieHeader, referer, userAgent);
            if (bookingId == null || bookingId.isEmpty()) {
                logger.warn("BookingId not found for eventId: {}", eventId);
                return false;
            }

            String url = String.format("https://events.yandex-team.ru/back/events/booking/%s", bookingId);
            logger.info("Unbooking request: eventId={}, bookingId={}, referer={}", eventId, bookingId, referer);

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("Accept-Language", "ru");
            headers.set("Connection", "keep-alive");
            headers.set("Cookie", cookieHeader);
            headers.set("Origin", "https://events.yandex-team.ru");
            headers.set("Referer", referer);
            headers.set("Sec-Fetch-Dest", "empty");
            headers.set("Sec-Fetch-Mode", "cors");
            headers.set("Sec-Fetch-Site", "same-origin");
            headers.set("User-Agent", userAgent);
            headers.set("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"YaBrowser\";v=\"25.8\", \"Yowser\";v=\"2.5\"");
            headers.set("sec-ch-ua-arch", "\"arm\"");
            headers.set("sec-ch-ua-bitness", "\"64\"");
            headers.set("sec-ch-ua-full-version-list", "\"Not)A;Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"138.0.7204.977\", \"YaBrowser\";v=\"25.8.5.977\", \"Yowser\";v=\"2.5\"");
            headers.set("sec-ch-ua-mobile", "?0");
            headers.set("sec-ch-ua-platform", "\"macOS\"");
            headers.set("sec-ch-ua-platform-version", "\"15.7.1\"");
            headers.set("sec-ch-ua-wow64", "?0");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);

            logger.info("Unbooking response status: {}", response.getStatusCode());
            logger.info("Unbooking response body: {}", response.getBody());

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Failed to unbook event {}", eventId, e);
            return false;
        }
    }
}


