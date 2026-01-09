package com.example.telegrambot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Event {
    private String id;
    private String title;
    private String shortDescription;
    private String cover;
    private String coverPreview;
    private String coverWebp;
    private List<Category> category;
    private Integer group;
    private City city;
    private boolean haveFreeSeats;
    private List<String> datesOf;
    private boolean booked;
    private int freeSeats;
    private int bookedCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getShortDescription() {
        return shortDescription;
    }

    public void setShortDescription(String shortDescription) {
        this.shortDescription = shortDescription;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public String getCoverPreview() {
        return coverPreview;
    }

    public void setCoverPreview(String coverPreview) {
        this.coverPreview = coverPreview;
    }

    public String getCoverWebp() {
        return coverWebp;
    }

    public void setCoverWebp(String coverWebp) {
        this.coverWebp = coverWebp;
    }

    public List<Category> getCategory() {
        return category;
    }

    public void setCategory(List<Category> category) {
        this.category = category;
    }

    public Integer getGroup() {
        return group;
    }

    public void setGroup(Integer group) {
        this.group = group;
    }

    public City getCity() {
        return city;
    }

    public void setCity(City city) {
        this.city = city;
    }

    public boolean isHaveFreeSeats() {
        return haveFreeSeats;
    }

    public void setHaveFreeSeats(boolean haveFreeSeats) {
        this.haveFreeSeats = haveFreeSeats;
    }

    public List<String> getDatesOf() {
        return datesOf;
    }

    public void setDatesOf(List<String> datesOf) {
        this.datesOf = datesOf;
    }

    public boolean isBooked() {
        return booked;
    }

    public void setBooked(boolean booked) {
        this.booked = booked;
    }

    public int getFreeSeats() {
        return freeSeats;
    }

    public void setFreeSeats(int freeSeats) {
        this.freeSeats = freeSeats;
    }

    public int getBookedCount() {
        return bookedCount;
    }

    public void setBookedCount(int bookedCount) {
        this.bookedCount = bookedCount;
    }
}

