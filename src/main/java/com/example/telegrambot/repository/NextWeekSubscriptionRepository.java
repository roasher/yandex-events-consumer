package com.example.telegrambot.repository;

import com.example.telegrambot.entity.NextWeekSubscription;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NextWeekSubscriptionRepository extends JpaRepository<NextWeekSubscription, Long> {

    Optional<NextWeekSubscription> findByOriginalEventIdAndUserId(String originalEventId, Long userId);

    List<NextWeekSubscription> findByUserIdAndIsActiveTrue(Long userId);

    List<NextWeekSubscription> findByIsActiveTrue();

    void deleteByOriginalEventIdAndUserId(String originalEventId, Long userId);
}

