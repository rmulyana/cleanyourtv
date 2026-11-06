package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** The lock code: arrows map to letters, and the stored hash depends on both salt and sequence. */
public class AppLockTest {
    @Test
    public void hashDependsOnSaltAndSequence() {
        assertEquals(AppLock.hash("s1", "UUDDLR"), AppLock.hash("s1", "UUDDLR"));
        assertNotEquals(AppLock.hash("s1", "UUDDLR"), AppLock.hash("s1", "UUDDRL"));
        assertNotEquals(AppLock.hash("s1", "UUDDLR"), AppLock.hash("s2", "UUDDLR"));
        assertEquals(64, AppLock.hash("s1", "UDLR").length());
    }
}
