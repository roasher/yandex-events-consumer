package com.example.telegrambot;

import com.example.telegrambot.dto.Event;
import com.example.telegrambot.service.EventsService;
import com.example.telegrambot.service.UserCookieService;
import com.example.telegrambot.service.UserPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class EventsServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(EventsServiceTest.class);

    @Test
    public void testGetEventsAndPrintJson() {
        UserCookieService userCookieService = new UserCookieService();
        UserPreferencesService userPreferencesService = new UserPreferencesService();
        EventsService eventsService = new EventsService(userCookieService, userPreferencesService);
        // Используем userId = null, что означает отсутствие куки
        List<Event> events = eventsService.getEvents((Long) null);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try {
            String json = objectMapper.writeValueAsString(events);
            System.out.println("=== Список событий в формате JSON ===");
            System.out.println(json);
            System.out.println("\n=== Всего событий: " + events.size() + " ===");
            
            logger.info("Successfully retrieved {} events", events.size());
        } catch (Exception e) {
            logger.error("Error serializing events to JSON", e);
            System.err.println("Ошибка при сериализации в JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

