package my.hinoki.booxreader.data.repo

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseReaderSettingsTest {

    private fun gson(): Gson {
        return GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
    }

    @Test
    fun toLocal_parsesMagicTagsArrayPayload() {
        val json = """
            {
              "magic_tags": [
                {"id":"a","label":"Tag A","content":"A","description":"A","role":"system"},
                {"id":"b","label":"Tag B","content":"B","description":"B","role":"user"}
              ]
            }
        """.trimIndent()

        val remote = gson().fromJson(json, SupabaseReaderSettings::class.java)
        val local = ReaderSettings()

        val merged = remote.toLocal(local, localProfileId = -1L)

        assertEquals(2, merged.magicTags.size)
        assertEquals("a", merged.magicTags[0].id)
        assertEquals("b", merged.magicTags[1].id)
    }

    @Test
    fun toLocal_parsesMagicTagsStringPayload() {
        val tags =
            listOf(
                MagicTag(id = "x", label = "Tag X", content = "X", description = "X"),
                MagicTag(id = "y", label = "Tag Y", content = "Y", description = "Y")
            )
        val tagsJson = gson().toJson(tags)
        val payload = JsonObject().apply {
            addProperty("magic_tags", tagsJson)
        }

        val remote = gson().fromJson(payload, SupabaseReaderSettings::class.java)
        val local = ReaderSettings()

        val merged = remote.toLocal(local, localProfileId = -1L)

        assertEquals(2, merged.magicTags.size)
        assertEquals("x", merged.magicTags[0].id)
        assertEquals("y", merged.magicTags[1].id)
    }
}
