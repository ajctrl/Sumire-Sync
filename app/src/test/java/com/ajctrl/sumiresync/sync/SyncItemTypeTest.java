package com.ajctrl.sumiresync.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class SyncItemTypeTest {
    @Test public void acceptsContractValues() {
        assertEquals(SyncItemType.TEXT, SyncItemType.fromContractValue("TEXT"));
        assertEquals(SyncItemType.IMAGE, SyncItemType.fromContractValue("IMAGE"));
    }

    @Test public void rejectsUnknownAndNullValues() {
        assertThrows(IllegalStateException.class,
                () -> SyncItemType.fromContractValue("VIDEO"));
        assertThrows(IllegalStateException.class,
                () -> SyncItemType.fromContractValue(null));
    }
}
