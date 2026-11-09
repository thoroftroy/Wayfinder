package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileSortingTest {
    @Test fun foldersStayFirstAndOrdersAreStable() {
        val input = listOf(FileSortKey("small", false, 3), FileSortKey("z folder", true, 0), FileSortKey("large", false, 50), FileSortKey("a folder", true, 0))
        fun names(order: FileOrder) = input.sortedWith { a, b -> compareFileKeys(a, b, order) }.map { it.name }
        assertEquals(listOf("a folder", "z folder", "large", "small"), names(FileOrder.NAME_ASC))
        assertEquals(listOf("z folder", "a folder", "small", "large"), names(FileOrder.NAME_DESC))
        assertEquals(listOf("a folder", "z folder", "large", "small"), names(FileOrder.SIZE_DESC))
    }
}
