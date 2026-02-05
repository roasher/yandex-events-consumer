package com.example.telegrambot.service;

import com.example.telegrambot.entity.NextWeekSubscription;
import com.example.telegrambot.repository.NextWeekSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@org.springframework.context.annotation.Profile("!server-only")
public class NextWeekSubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(NextWeekSubscriptionService.class);
    private final NextWeekSubscriptionRepository repository;

    public NextWeekSubscriptionService(NextWeekSubscriptionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean subscribeToNextWeek(String originalEventId, Long userId, Long chatId, 
                                      String eventTitle, Set<Integer> categoryIds, 
                                      Integer cityId, String originalEventDate) {
        // Check if already subscribed
        Optional<NextWeekSubscription> existing = repository.findByOriginalEventIdAndUserId(originalEventId, userId);
        if (existing.isPresent()) {
            return false; // Already subscribed
        }

        // Create subscription
        String categoryIdsStr = categoryIds != null && !categoryIds.isEmpty() 
            ? categoryIds.stream().map(String::valueOf).collect(Collectors.joining(","))
            : "";
        
        NextWeekSubscription subscription = new NextWeekSubscription(
            originalEventId, userId, chatId, eventTitle, 
            categoryIdsStr, cityId, originalEventDate
        );
        
        repository.save(subscription);
        logger.info("User {} subscribed to next week version of event {}", userId, originalEventId);
        return true;
    }

    @Transactional
    public boolean unsubscribeFromNextWeek(String originalEventId, Long userId) {
        Optional<NextWeekSubscription> subscription = repository.findByOriginalEventIdAndUserId(originalEventId, userId);
        if (subscription.isEmpty()) {
            return false;
        }
        
        repository.deleteByOriginalEventIdAndUserId(originalEventId, userId);
        logger.info("User {} unsubscribed from next week version of event {}", userId, originalEventId);
        return true;
    }

    public boolean isSubscribed(String originalEventId, Long userId) {
        return repository.findByOriginalEventIdAndUserId(originalEventId, userId).isPresent();
    }

    public List<NextWeekSubscription> getAllActiveSubscriptions() {
        return repository.findByIsActiveTrue();
    }

    public List<NextWeekSubscription> getUserSubscriptions(Long userId) {
        return repository.findByUserIdAndIsActiveTrue(userId);
    }

    @Transactional
    public void deactivateSubscription(Long subscriptionId) {
        Optional<NextWeekSubscription> subscription = repository.findById(subscriptionId);
        if (subscription.isPresent()) {
            subscription.get().setIsActive(false);
            repository.save(subscription.get());
        }
    }
}

