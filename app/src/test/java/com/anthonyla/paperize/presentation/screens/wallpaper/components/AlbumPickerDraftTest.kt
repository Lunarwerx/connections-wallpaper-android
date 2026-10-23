package com.anthonyla.paperize.presentation.screens.wallpaper.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumPickerDraftTest {
    @Test fun tapsApplyNothingUntilTheSheetClosesAndALaterCloseAppliesNothingMore() {
        val applied = mutableListOf<List<String>>()
        val draft = AlbumPickerDraft(listOf("a")) { applied.add(it) }

        draft.toggle("b")
        draft.toggle("a")
        draft.toggle("c")
        assertEquals(listOf("b", "c"), draft.selected)
        assertEquals(emptyList<List<String>>(), applied)

        draft.close()
        assertEquals(listOf(listOf("b", "c")), applied)

        draft.toggle("d")
        draft.close()
        assertEquals(listOf(listOf("b", "c")), applied)
    }
}
