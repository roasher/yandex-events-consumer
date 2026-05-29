package com.example.telegrambot.service;

import com.example.telegrambot.dto.Event;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A single poll entry: event name substring match (case-insensitive) and optional day of week.
 * Day numbers follow ISO-8601: 1 = Monday, 7 = Sunday (e.g. 3 = Wednesday).
 */
public final class PollTarget {

    private final String namePattern;
    private final Integer dayOfWeek;

    public PollTarget(String namePattern, Integer dayOfWeek) {
        this.namePattern = Objects.requireNonNull(namePattern, "namePattern");
        this.dayOfWeek = dayOfWeek;
    }

    public String getNamePattern() {
        return namePattern;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public boolean matchesTitle(String eventTitle) {
        if (eventTitle == null) {
            return false;
        }
        return eventTitle.toLowerCase(Locale.ROOT).contains(namePattern);
    }

    public boolean matchesEvent(Event event) {
        if (!matchesTitle(event.getTitle())) {
            return false;
        }
        if (dayOfWeek == null) {
            return true;
        }
        return matchesDayOfWeek(event, dayOfWeek);
    }

    /**
     * Parses comma-separated poll config, preserving order.
     * Format: {@code Name} or {@code Name:day} where day is 1–7 (Monday–Sunday).
     * Example: {@code Плавание:3,Плавание:1,теннис,Boxing (Красная Роза):2}
     */
    public static List<PollTarget> parseList(String commaSeparated) {
        List<PollTarget> targets = new ArrayList<>();
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
            return targets;
        }
        for (String part : commaSeparated.split(",")) {
            PollTarget target = parseEntry(part);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    static PollTarget parseEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int colonIdx = trimmed.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < trimmed.length() - 1) {
            String suffix = trimmed.substring(colonIdx + 1).trim();
            if (suffix.length() == 1 && suffix.charAt(0) >= '1' && suffix.charAt(0) <= '7') {
                String name = trimmed.substring(0, colonIdx).trim();
                if (!name.isEmpty()) {
                    return new PollTarget(
                        name.toLowerCase(Locale.ROOT),
                        suffix.charAt(0) - '0'
                    );
                }
            }
        }

        return new PollTarget(trimmed.toLowerCase(Locale.ROOT), null);
    }

    static boolean matchesDayOfWeek(Event event, int dayOfWeek) {
        List<String> dates = event.getDatesOf();
        if (dates == null || dates.isEmpty()) {
            return false;
        }
        for (String dateStr : dates) {
            ZonedDateTime zdt = parseDate(dateStr);
            if (zdt != null && zdt.getDayOfWeek().getValue() == dayOfWeek) {
                return true;
            }
        }
        return false;
    }

    private static ZonedDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            if (dateStr.endsWith("Z")) {
                return ZonedDateTime.parse(dateStr);
            }
            LocalDateTime localDateTime = LocalDateTime.parse(dateStr.replace("Z", ""));
            return localDateTime.atZone(ZoneId.of("Europe/Moscow"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String dayName(int dayOfWeek) {
        return DayOfWeek.of(dayOfWeek).name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        if (dayOfWeek == null) {
            return namePattern;
        }
        return namePattern + ":" + dayOfWeek + " (" + dayName(dayOfWeek) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PollTarget that)) {
            return false;
        }
        return namePattern.equals(that.namePattern) && Objects.equals(dayOfWeek, that.dayOfWeek);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namePattern, dayOfWeek);
    }
}
