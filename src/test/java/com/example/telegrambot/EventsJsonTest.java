package com.example.telegrambot;

import com.example.telegrambot.dto.Event;
import com.example.telegrambot.service.EventsService;
import com.example.telegrambot.service.UserCookieService;
import com.example.telegrambot.service.UserPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/**
 * Простой тест для получения списка событий и вывода в формате JSON.
 * Можно запустить напрямую без тестового фреймворка.
 */
public class EventsJsonTest {
    public static void main(String[] args) {
        System.out.println("Запрашиваю список событий...\n");
        
        // Получаем cookies из аргументов или системной переменной
        String cookies = null;
        if (args.length > 0) {
            cookies = args[0];
        } else {
            cookies = System.getProperty("events.api.cookies");
        }
        
        if (cookies == null || cookies.isEmpty()) {
            System.err.println("ОШИБКА: Не указаны cookies для аутентификации!");
            System.err.println("Используйте:");
            System.err.println("  - Переменную окружения: -Devents.api.cookies=\"ваши_cookies\"");
            System.err.println("  - Или аргумент командной строки: java EventsJsonTest \"ваши_cookies\"");
            System.err.println("\nПолучить cookies можно из браузера (DevTools -> Network -> Headers -> Cookie)");
            return;
        }
        
        UserCookieService userCookieService = new UserCookieService();
        UserPreferencesService userPreferencesService = new UserPreferencesService();
        EventsService eventsService = new EventsService(userCookieService, userPreferencesService);
        List<Event> events = eventsService.getEvents(cookies);

        if (events.isEmpty()) {
            System.out.println("События не найдены.");
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try {
            String json = objectMapper.writeValueAsString(events);
            System.out.println("=== Список событий в формате JSON ===");
            System.out.println(json);
            System.out.println("\n=== Всего событий: " + events.size() + " ===");
            
            // Также выведем полный ответ API
            System.out.println("\n=== Первое событие для проверки ===");
            if (!events.isEmpty()) {
                Event firstEvent = events.get(0);
                String firstEventJson = objectMapper.writeValueAsString(firstEvent);
                System.out.println(firstEventJson);
            }
            
        } catch (Exception e) {
            System.err.println("Ошибка при сериализации в JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

