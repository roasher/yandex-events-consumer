package com.example.telegrambot.service;

import com.example.telegrambot.dto.Event;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PollTargetTest {

    @Test
    void parseList_preservesOrderAndDaySuffix() {
        List<PollTarget> targets = PollTarget.parseList(
            "Плавание:3,Плавание:1,теннис,Boxing (Красная Роза):2"
        );

        assertEquals(4, targets.size());
        assertEquals("плавание", targets.get(0).getNamePattern());
        assertEquals(3, targets.get(0).getDayOfWeek());
        assertEquals("плавание", targets.get(1).getNamePattern());
        assertEquals(1, targets.get(1).getDayOfWeek());
        assertEquals("теннис", targets.get(2).getNamePattern());
        assertNull(targets.get(2).getDayOfWeek());
        assertEquals("boxing (красная роза)", targets.get(3).getNamePattern());
        assertEquals(2, targets.get(3).getDayOfWeek());
    }

    @Test
    void parseEntry_nameWithColonInParentheses_notTreatedAsDay() {
        PollTarget target = PollTarget.parseEntry("Some Event:99");
        assertEquals("some event:99", target.getNamePattern());
        assertNull(target.getDayOfWeek());
    }

    @Test
    void matchesDayOfWeek_usesDatesOf() {
        Event event = new Event();
        event.setTitle("Плавание");
        event.setDatesOf(List.of("2026-03-18T10:00:00Z")); // Wednesday

        assertTrue(PollTarget.matchesDayOfWeek(event, 3));
        assertFalse(PollTarget.matchesDayOfWeek(event, 1));
    }

    @Test
    void matchesEvent_titleAndDay() {
        PollTarget target = new PollTarget("плавание", 3);
        Event event = new Event();
        event.setTitle("Плавание в бассейне");
        event.setDatesOf(List.of("2026-03-18T10:00:00Z"));

        assertTrue(target.matchesEvent(event));
    }
}
