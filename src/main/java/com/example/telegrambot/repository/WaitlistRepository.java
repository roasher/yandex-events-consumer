package com.example.telegrambot.repository;

import com.example.telegrambot.entity.WaitlistEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    List<WaitlistEntry> findByEventIdOrderByPositionAsc(String eventId);

    Optional<WaitlistEntry> findByEventIdAndUserId(String eventId, Long userId);

    Optional<WaitlistEntry> findByEventIdAndChatId(String eventId, Long chatId);

    @Query("SELECT COUNT(w) FROM WaitlistEntry w WHERE w.eventId = :eventId")
    long countByEventId(String eventId);

    void deleteByEventIdAndUserId(String eventId, Long userId);

    @Query("SELECT DISTINCT w.eventId FROM WaitlistEntry w")
    List<String> findAllDistinctEventIds();
}

