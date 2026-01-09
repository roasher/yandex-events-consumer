package com.example.telegrambot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для управления захолженными событиями (для тестирования листа ожидания).
 * Позволяет эмулировать ситуацию, когда запись на событие невозможна.
 */
@Service
public class EventHoldService implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(EventHoldService.class);
    
    // Список eventId или ссылок на события для захолда (из переменной окружения)
    // Поддерживает как прямые eventId, так и URL событий
    @Value("${events.hold.links:}")
    private List<String> holdEventLinks;
    
    // Хранилище захолженных eventId
    private final Set<String> heldEvents = ConcurrentHashMap.newKeySet();

    /**
     * Инициализирует захолженные события из переменной окружения при старте приложения
     * Поддерживает как прямые eventId, так и URL событий
     */
    @Override
    public void afterPropertiesSet() {
        if (holdEventLinks != null && !holdEventLinks.isEmpty()) {
            for (String link : holdEventLinks) {
                String trimmed = link != null ? link.trim() : "";
                if (!trimmed.isEmpty()) {
                    // Извлекаем eventId из URL или используем как есть, если это уже ID
                    String eventId = extractEventIdFromUrl(trimmed);
                    if (eventId == null) {
                        // Если не удалось извлечь из URL, считаем что это прямой ID
                        eventId = trimmed;
                    }
                    if (!eventId.isEmpty()) {
                        heldEvents.add(eventId);
                        logger.info("Event {} initialized as held from environment variable (from: {})", eventId, trimmed);
                    } else {
                        logger.warn("Could not extract event ID from: {}", trimmed);
                    }
                }
            }
            logger.info("Initialized {} events as held from environment variable", heldEvents.size());
        } else {
            logger.info("No events configured for hold (events.hold.links is empty)");
        }
    }

    /**
     * Извлекает eventId из URL события
     * Поддерживает форматы:
     * - https://events.yandex-team.ru/?city=1&eventId=b27b9fb8-895a-4b1d-bc56-704e92f46457
     * - https://events.yandex-team.ru/events/b27b9fb8-895a-4b1d-bc56-704e92f46457?city=1
     * Если переданная строка не является URL, возвращает null
     */
    private String extractEventIdFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        // Если строка не содержит http/https, считаем что это прямой ID
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
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

    /**
     * Проверяет, захолжено ли событие
     */
    public boolean isEventHeld(String eventId) {
        return heldEvents.contains(eventId);
    }

    /**
     * Захолживает событие (делает запись невозможной)
     */
    public void holdEvent(String eventId) {
        heldEvents.add(eventId);
        logger.info("Event {} is now held (booking disabled)", eventId);
    }

    /**
     * Снимает захолд с события
     */
    public void unholdEvent(String eventId) {
        boolean removed = heldEvents.remove(eventId);
        if (removed) {
            logger.info("Event {} is no longer held (booking enabled)", eventId);
        }
    }

    /**
     * Снимает захолд со всех событий
     */
    public void unholdAll() {
        int count = heldEvents.size();
        heldEvents.clear();
        logger.info("Removed hold from {} events", count);
    }

    /**
     * Получает количество захолженных событий
     */
    public int getHeldEventsCount() {
        return heldEvents.size();
    }

    /**
     * Получает все захолженные eventId
     */
    public Set<String> getAllHeldEvents() {
        return new HashSet<>(heldEvents);
    }
}
