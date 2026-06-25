package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure recycle-bin helpers: timestamped trash names, original-name recovery, expiry. */
class PhoneRouteStoreTrashTest {

    @Test
    fun trashEntryName_prefixesTimestampAndSanitises() {
        assertEquals("1000__riverside_5k.gpx", PhoneRouteStore.trashEntryName("riverside 5k.gpx", 1000L))
        // safeName ensures the .gpx extension + safe chars
        assertEquals("2000__loop.gpx", PhoneRouteStore.trashEntryName("loop", 2000L))
    }

    @Test
    fun originalNameOf_stripsTimestampPrefix() {
        assertEquals("riverside_5k.gpx", PhoneRouteStore.originalNameOf("1000__riverside_5k.gpx"))
    }

    @Test
    fun timestampOf_parsesPrefix() {
        assertEquals(1000L, PhoneRouteStore.timestampOf("1000__x.gpx"))
        assertNull(PhoneRouteStore.timestampOf("notrash.gpx"))
    }

    @Test
    fun isExpired_pastTheMaxAge() {
        val maxAge = 2L * 24 * 60 * 60 * 1000  // 2 days
        val ts = 1_000_000L
        assertFalse(PhoneRouteStore.isExpired("${ts}__x.gpx", ts + maxAge - 1, maxAge))
        assertTrue(PhoneRouteStore.isExpired("${ts}__x.gpx", ts + maxAge + 1, maxAge))
        // unparseable timestamp -> treat as expired (purge it)
        assertTrue(PhoneRouteStore.isExpired("garbage.gpx", ts, maxAge))
    }
}
