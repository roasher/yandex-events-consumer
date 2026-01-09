package com.example.telegrambot.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserCookieService {

    private static final Logger logger = LoggerFactory.getLogger(UserCookieService.class);
    
    // Хранилище кук для каждого пользователя (userId -> cookie)
    private final Map<Long, String> userCookies = new ConcurrentHashMap<>();

    /**
     * Сохраняет куку для пользователя
     */
    public void setCookie(Long userId, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            logger.warn("Attempted to set empty cookie for user {}", userId);
            return;
        }
        String trimmed = cookie.trim();
        userCookies.put(userId, trimmed);
        logger.info("Cookie saved for user {} (length: {} chars)", userId, trimmed.length());
        // Логируем первые несколько символов для отладки (безопасно)
        if (trimmed.length() > 50) {
            logger.debug("Cookie preview: {}...", trimmed.substring(0, 50));
        }
    }

    /**
     * Получает куку для пользователя
     */
    public String getCookie(Long userId) {
        return userCookies.get(userId);
    }

    /**
     * Проверяет, есть ли кука у пользователя
     */
    public boolean hasCookie(Long userId) {
        return userCookies.containsKey(userId) && 
               userCookies.get(userId) != null && 
               !userCookies.get(userId).trim().isEmpty();
    }

    /**
     * Удаляет куку пользователя
     */
    public void removeCookie(Long userId) {
        userCookies.remove(userId);
        logger.info("Cookie removed for user {}", userId);
    }
}

