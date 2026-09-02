package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class SourceStatePolicyTest {
    @Test public void firstConnectionResetsProgress() {
        assertEquals(SourceStatePolicy.Decision.RESET,
                SourceStatePolicy.evaluate(1, 1, "instance-a", 1, 10,
                        null, null, 0));
    }

    @Test public void changedInstanceOrGenerationResetsProgress() {
        assertEquals(SourceStatePolicy.Decision.RESET,
                SourceStatePolicy.evaluate(1, 1, "instance-b", 1, 2,
                        "instance-a", 1, 100));
        assertEquals(SourceStatePolicy.Decision.RESET,
                SourceStatePolicy.evaluate(1, 1, "instance-a", 2, 2,
                        "instance-a", 1, 100));
    }

    @Test public void sameSourceContinues() {
        assertEquals(SourceStatePolicy.Decision.CONTINUE,
                SourceStatePolicy.evaluate(1, 1, "instance-a", 1, 101,
                        "instance-a", 1, 100));
    }

    @Test public void incompatibleApiIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                SourceStatePolicy.evaluate(1, 2, "instance-a", 1, 100,
                        "instance-a", 1, 90));
    }

    @Test public void backwardsSequenceInSameNamespaceIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                SourceStatePolicy.evaluate(1, 1, "instance-a", 1, 99,
                        "instance-a", 1, 100));
    }
}
