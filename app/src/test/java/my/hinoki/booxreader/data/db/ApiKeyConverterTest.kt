package my.hinoki.booxreader.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.security.SecretCipher
import my.hinoki.booxreader.data.security.Secrets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 驗證 [ApiKeyConverter]：API key 在資料庫裡是密文，透過 DAO 讀出時自動還原。
 *
 * 注入可逆假 cipher（Robolectric 沒有 AndroidKeyStore），因此可以直接斷言落地內容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ApiKeyConverterTest {

    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(stored: String): String = stored.reversed()
    }

    @Before
    fun setUp() {
        initBooxReaderDatabase(ApplicationProvider.getApplicationContext<Context>())
        Secrets.setCipherForTesting(ReversingCipher())
        runBlocking { AppDatabase.get().aiProfileDao().deleteAll() }
    }

    @After
    fun tearDown() {
        runBlocking { AppDatabase.get().aiProfileDao().deleteAll() }
        Secrets.setCipherForTesting(null)
        AppDatabase.resetInstanceForTesting()
    }

    @Test
    fun apiKeyIsEncryptedInDatabaseAndDecryptedOnRead() = runBlocking {
        val db = AppDatabase.get()
        val dao = db.aiProfileDao()

        val id =
                dao.insert(
                        AiProfileEntity(
                                name = "Encrypted",
                                modelName = "model",
                                apiKey = ApiKey("sk-db-secret"),
                                serverBaseUrl = "https://example.com",
                                systemPrompt = "sys",
                                userPromptTemplate = "%s",
                                useStreaming = false
                        )
                )

        // 直接看底層資料表：落地值必須是密文
        db.openHelper.readableDatabase
                .query("SELECT apiKey FROM ai_profiles WHERE id = ?", arrayOf(id))
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val stored = cursor.getString(0).orEmpty()
                    assertNotEquals("sk-db-secret", stored)
                    assertTrue(stored.startsWith(Secrets.ENCRYPTED_PREFIX))
                }

        // 透過 DAO 讀回：必須自動解密
        assertEquals("sk-db-secret", dao.getById(id)?.apiKey?.value)
    }

    @Test
    fun legacyPlaintextRowsRemainReadable() = runBlocking {
        val db = AppDatabase.get()
        val dao = db.aiProfileDao()

        // 模擬舊版本寫入的明文資料
        db.openHelper.writableDatabase.execSQL(
                "INSERT INTO ai_profiles " +
                        "(name, modelName, apiKey, serverBaseUrl, systemPrompt, userPromptTemplate, " +
                        "useStreaming, temperature, maxTokens, topP, frequencyPenalty, " +
                        "presencePenalty, assistantRole, enableGoogleSearch, createdAt, updatedAt, " +
                        "isSynced) " +
                        "VALUES ('Legacy', 'm', 'sk-legacy-plain', 'u', 's', '%s', 0, 0.7, 4096, " +
                        "1.0, 0.0, 0.0, 'assistant', 1, 1, 1, 0)"
        )

        val legacy = dao.getAllList().first { it.name == "Legacy" }
        assertEquals("sk-legacy-plain", legacy.apiKey.value)
    }
}
