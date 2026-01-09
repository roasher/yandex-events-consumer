package com.example.telegrambot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "waitlist_entries", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"}))
public class WaitlistEntry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "event_id", nullable = false)
    private String eventId;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "chat_id", nullable = false)
    private Long chatId;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "position", nullable = false)
    private Integer position;

    public WaitlistEntry() {
        this.createdAt = LocalDateTime.now();
    }

    public WaitlistEntry(String eventId, Long userId, Long chatId, Integer position) {
        this.eventId = eventId;
        this.userId = userId;
        this.chatId = chatId;
        this.position = position;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }
}

