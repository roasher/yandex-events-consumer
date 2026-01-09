package com.example.telegrambot.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserPreferencesService {

    private static final Logger logger = LoggerFactory.getLogger(UserPreferencesService.class);
    
    // Хранилище выбранного города для каждого пользователя (userId -> cityId)
    private final Map<Long, Integer> userCities = new ConcurrentHashMap<>();
    
    // Хранилище выбранных категорий для каждого пользователя (userId -> Set<categoryId>)
    private final Map<Long, Set<Integer>> userCategories = new ConcurrentHashMap<>();
    
    // Флаги для отслеживания состояния выбора (userId -> ожидаем выбор города/категорий)
    private final Map<Long, Boolean> awaitingCitySelection = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> awaitingCategorySelection = new ConcurrentHashMap<>();

    /**
     * Сохраняет выбранный город для пользователя
     */
    public void setCity(Long userId, Integer cityId) {
        if (cityId == null || cityId <= 0) {
            logger.warn("Attempted to set invalid city {} for user {}", cityId, userId);
            return;
        }
        userCities.put(userId, cityId);
        logger.info("City {} saved for user {}", cityId, userId);
    }

    /**
     * Получает выбранный город для пользователя
     */
    public Integer getCity(Long userId) {
        return userCities.get(userId);
    }

    /**
     * Проверяет, есть ли выбранный город у пользователя
     */
    public boolean hasCity(Long userId) {
        return userCities.containsKey(userId) && userCities.get(userId) != null;
    }

    /**
     * Сохраняет выбранные категории для пользователя
     */
    public void setCategories(Long userId, Set<Integer> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            // Разрешаем пустой набор категорий (пользователь может не выбрать ничего)
            userCategories.put(userId, new HashSet<>());
            logger.info("Empty categories set for user {} (user chose no categories)", userId);
            return;
        }
        userCategories.put(userId, new HashSet<>(categoryIds)); // Создаем копию для безопасности
        logger.info("Categories {} saved for user {}", categoryIds, userId);
    }

    /**
     * Получает выбранные категории для пользователя
     */
    public Set<Integer> getCategories(Long userId) {
        return userCategories.get(userId);
    }

    /**
     * Проверяет, выбрал ли пользователь категории (включая пустой выбор)
     */
    public boolean hasCategories(Long userId) {
        return userCategories.containsKey(userId);
    }

    /**
     * Устанавливает флаг ожидания выбора города
     */
    public void setAwaitingCitySelection(Long userId, boolean awaiting) {
        if (awaiting) {
            awaitingCitySelection.put(userId, true);
        } else {
            awaitingCitySelection.remove(userId);
        }
    }

    /**
     * Проверяет, ожидаем ли мы выбор города от пользователя
     */
    public boolean isAwaitingCitySelection(Long userId) {
        return awaitingCitySelection.containsKey(userId) && awaitingCitySelection.get(userId);
    }

    /**
     * Устанавливает флаг ожидания выбора категорий
     */
    public void setAwaitingCategorySelection(Long userId, boolean awaiting) {
        if (awaiting) {
            awaitingCategorySelection.put(userId, true);
        } else {
            awaitingCategorySelection.remove(userId);
        }
    }

    /**
     * Проверяет, ожидаем ли мы выбор категорий от пользователя
     */
    public boolean isAwaitingCategorySelection(Long userId) {
        return awaitingCategorySelection.containsKey(userId) && awaitingCategorySelection.get(userId);
    }

    /**
     * Удаляет все настройки пользователя (город и категории)
     */
    public void clearPreferences(Long userId) {
        userCities.remove(userId);
        userCategories.remove(userId);
        awaitingCitySelection.remove(userId);
        awaitingCategorySelection.remove(userId);
        logger.info("Preferences cleared for user {}", userId);
    }
}
