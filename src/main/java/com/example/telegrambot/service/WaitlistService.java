package com.example.telegrambot.service;

import com.example.telegrambot.entity.WaitlistEntry;
import com.example.telegrambot.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WaitlistService {

    private static final Logger logger = LoggerFactory.getLogger(WaitlistService.class);
    private static final int MAX_WAITLIST_SIZE = 10;

    private final WaitlistRepository waitlistRepository;

    public WaitlistService(WaitlistRepository waitlistRepository) {
        this.waitlistRepository = waitlistRepository;
    }

    @Transactional
    public AddToWaitlistResult addToWaitlist(String eventId, Long userId, Long chatId) {
        // Проверяем, не записан ли уже пользователь в лист ожидания
        Optional<WaitlistEntry> existingEntry = waitlistRepository.findByEventIdAndUserId(eventId, userId);
        if (existingEntry.isPresent()) {
            int position = existingEntry.get().getPosition();
            int peopleAhead = position - 1;
            return new AddToWaitlistResult(false, "Вы уже записаны в лист ожидания на это событие.", peopleAhead);
        }

        // Проверяем, заполнен ли лист ожидания
        long currentSize = waitlistRepository.countByEventId(eventId);
        if (currentSize >= MAX_WAITLIST_SIZE) {
            return new AddToWaitlistResult(false, "Лист ожидания заполнен (максимум " + MAX_WAITLIST_SIZE + " человек).", -1);
        }

        // Добавляем пользователя в лист ожидания
        int newPosition = (int) currentSize + 1;
        WaitlistEntry entry = new WaitlistEntry(eventId, userId, chatId, newPosition);
        waitlistRepository.save(entry);

        int peopleAhead = newPosition - 1;
        logger.info("User {} added to waitlist for event {} at position {}", userId, eventId, newPosition);

        return new AddToWaitlistResult(true, "Вы успешно записаны в лист ожидания!", peopleAhead);
    }

    public int getPositionInWaitlist(String eventId, Long userId) {
        Optional<WaitlistEntry> entry = waitlistRepository.findByEventIdAndUserId(eventId, userId);
        return entry.map(WaitlistEntry::getPosition).orElse(-1);
    }

    public int getWaitlistSize(String eventId) {
        return (int) waitlistRepository.countByEventId(eventId);
    }

    public boolean isWaitlistFull(String eventId) {
        return waitlistRepository.countByEventId(eventId) >= MAX_WAITLIST_SIZE;
    }

    public List<WaitlistEntry> getAllUsersInWaitlist(String eventId) {
        return waitlistRepository.findByEventIdOrderByPositionAsc(eventId);
    }

    /**
     * Получает все записи из листа ожидания (для мониторинга)
     */
    public List<WaitlistEntry> getAllEntries() {
        return waitlistRepository.findAll();
    }

    /**
     * Получает все уникальные eventId из листа ожидания
     */
    public List<String> getAllEventIds() {
        return waitlistRepository.findAllDistinctEventIds();
    }

    @Transactional
    public RemoveFromWaitlistResult removeFromWaitlist(String eventId, Long userId) {
        Optional<WaitlistEntry> entry = waitlistRepository.findByEventIdAndUserId(eventId, userId);
        if (entry.isEmpty()) {
            return new RemoveFromWaitlistResult(false, "Вы не находитесь в листе ожидания на это событие.");
        }

        int removedPosition = entry.get().getPosition();
        
        // Получаем всех пользователей с их текущими позициями до удаления
        List<WaitlistEntry> allEntriesBefore = waitlistRepository.findByEventIdOrderByPositionAsc(eventId);
        
        // Сохраняем старые позиции для последующих пользователей
        java.util.Map<Long, Integer> oldPositions = new java.util.HashMap<>();
        for (WaitlistEntry e : allEntriesBefore) {
            oldPositions.put(e.getUserId(), e.getPosition());
        }
        
        // Удаляем пользователя
        waitlistRepository.deleteByEventIdAndUserId(eventId, userId);
        
        // Пересчитываем позиции после удаления
        recalculatePositions(eventId);
        
        // Получаем обновленный список и собираем информацию об изменениях
        List<PositionUpdate> positionUpdates = new java.util.ArrayList<>();
        List<WaitlistEntry> remainingEntries = waitlistRepository.findByEventIdOrderByPositionAsc(eventId);
        
        for (WaitlistEntry remainingEntry : remainingEntries) {
            Integer oldPosition = oldPositions.get(remainingEntry.getUserId());
            int newPosition = remainingEntry.getPosition();
            // Уведомляем только тех, кто был после удаленного пользователя
            if (oldPosition != null && oldPosition > removedPosition && oldPosition != newPosition) {
                positionUpdates.add(new PositionUpdate(
                    remainingEntry.getUserId(),
                    remainingEntry.getChatId(),
                    oldPosition,
                    newPosition
                ));
            }
        }
        
        logger.info("User {} removed from waitlist for event {}. {} users will be notified.", 
            userId, eventId, positionUpdates.size());

        return new RemoveFromWaitlistResult(true, "Вы успешно вышли из листа ожидания.", positionUpdates);
    }

    @Transactional
    private void recalculatePositions(String eventId) {
        List<WaitlistEntry> entries = waitlistRepository.findByEventIdOrderByPositionAsc(eventId);
        for (int i = 0; i < entries.size(); i++) {
            WaitlistEntry entry = entries.get(i);
            entry.setPosition(i + 1);
            waitlistRepository.save(entry);
        }
    }

    public static class RemoveFromWaitlistResult {
        private final boolean success;
        private final String message;
        private final List<PositionUpdate> positionUpdates;

        public RemoveFromWaitlistResult(boolean success, String message) {
            this.success = success;
            this.message = message;
            this.positionUpdates = new java.util.ArrayList<>();
        }

        public RemoveFromWaitlistResult(boolean success, String message, List<PositionUpdate> positionUpdates) {
            this.success = success;
            this.message = message;
            this.positionUpdates = positionUpdates != null ? positionUpdates : new java.util.ArrayList<>();
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public List<PositionUpdate> getPositionUpdates() {
            return positionUpdates;
        }
    }

    public static class PositionUpdate {
        private final Long userId;
        private final Long chatId;
        private final int oldPosition;
        private final int newPosition;

        public PositionUpdate(Long userId, Long chatId, int oldPosition, int newPosition) {
            this.userId = userId;
            this.chatId = chatId;
            this.oldPosition = oldPosition;
            this.newPosition = newPosition;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getChatId() {
            return chatId;
        }

        public int getOldPosition() {
            return oldPosition;
        }

        public int getNewPosition() {
            return newPosition;
        }
    }

    public static class AddToWaitlistResult {
        private final boolean success;
        private final String message;
        private final int peopleAhead;

        public AddToWaitlistResult(boolean success, String message, int peopleAhead) {
            this.success = success;
            this.message = message;
            this.peopleAhead = peopleAhead;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getPeopleAhead() {
            return peopleAhead;
        }
    }
}

