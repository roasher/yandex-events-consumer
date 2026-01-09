package com.example.telegrambot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "next_week_subscriptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"original_event_id", "user_id"}))
public class NextWeekSubscription {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "original_event_id", nullable = false)
    private String originalEventId;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "chat_id", nullable = false)
    private Long chatId;
    
    @Column(name = "event_title", nullable = false)
    private String eventTitle;
    
    @Column(name = "event_category_ids")
    private String eventCategoryIds; // Comma-separated category IDs
    
    @Column(name = "event_city_id")
    private Integer eventCityId;
    
    @Column(name = "original_event_date")
    private String originalEventDate; // ISO date string for matching time pattern
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    public NextWeekSubscription() {
        this.createdAt = LocalDateTime.now();
        this.isActive = true;
    }

    public NextWeekSubscription(String originalEventId, Long userId, Long chatId, 
                               String eventTitle, String eventCategoryIds, 
                               Integer eventCityId, String originalEventDate) {
        this.originalEventId = originalEventId;
        this.userId = userId;
        this.chatId = chatId;
        this.eventTitle = eventTitle;
        this.eventCategoryIds = eventCategoryIds;
        this.eventCityId = eventCityId;
        this.originalEventDate = originalEventDate;
        this.createdAt = LocalDateTime.now();
        this.isActive = true;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOriginalEventId() {
        return originalEventId;
    }

    public void setOriginalEventId(String originalEventId) {
        this.originalEventId = originalEventId;
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

    public String getEventTitle() {
        return eventTitle;
    }

    public void setEventTitle(String eventTitle) {
        this.eventTitle = eventTitle;
    }

    public String getEventCategoryIds() {
        return eventCategoryIds;
    }

    public void setEventCategoryIds(String eventCategoryIds) {
        this.eventCategoryIds = eventCategoryIds;
    }

    public Integer getEventCityId() {
        return eventCityId;
    }

    public void setEventCityId(Integer eventCityId) {
        this.eventCityId = eventCityId;
    }

    public String getOriginalEventDate() {
        return originalEventDate;
    }

    public void setOriginalEventDate(String originalEventDate) {
        this.originalEventDate = originalEventDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}

