package com.anthonyla.paperize.connections

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConnectionsPicturesTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = mockk<Context> { every { filesDir } answers { folder.root } }
    private val api = mockk<ConnectionsApi>()
    private val pictures = ConnectionsPictures(context, api)
    private val picture = Picture(id = "00000000-0000-0000-0000-00000000000a", slug = "slug-a", previewUrl = null)

    @Test fun `a picture is fetched once as its screen copy, then opens from the phone with no second fetch`() = runTest {
        coEvery { api.screen(picture.id) } returns ConnectionsDownload("https://example.invalid/a.webp", "slug-a.webp", "image/webp")
        coEvery { api.readInto("https://example.invalid/a.webp", any()) } answers { secondArg<File>().apply { parentFile!!.mkdirs(); writeText("webp") } }

        val first = pictures.file(picture)
        val second = pictures.file(picture)

        assertEquals(first, second)
        assertEquals("webp", second.readText())
        coVerify(exactly = 1) { api.screen(picture.id) }
    }

    @Test fun `a downloaded original opens with no fetch`() = runTest {
        val original = File(folder.root, "connections/slug-a.jpg").apply { parentFile!!.mkdirs(); writeText("original") }

        assertEquals(original, pictures.file(picture))
        coVerify(exactly = 0) { api.screen(any()) }
    }
}
