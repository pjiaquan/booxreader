package com.example.booxreader.data.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DecorationHandlerTest {

    @Test
    fun `extractNoteIdFromDecorationId returns correct id for various patterns`() {
        // Case 1: Standard note ID (legacy or direct)
        assertEquals(123L, DecorationHandler.extractNoteIdFromDecorationId("123"))

        // Case 2: Keyword decoration on the same page
        assertEquals(456L, DecorationHandler.extractNoteIdFromDecorationId("note_456_kw_chapter1_100"))

        // Case 3: Keyword decoration on a DIFFERENT page (simulated href)
        // This verifies that regardless of where the keyword is found (href/location), 
        // the click listener will extract the correct Note ID to open the Detail Activity.
        assertEquals(789L, DecorationHandler.extractNoteIdFromDecorationId("note_789_kw_chapter5_segment2_50"))
        
        // Case 4: Invalid ID
        assertEquals(null, DecorationHandler.extractNoteIdFromDecorationId("invalid_id"))
    }
}
