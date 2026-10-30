package com.dcontrol.client.core.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var database: DControlDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DControlDatabase::class.java)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `transfers are upserted, listed in creation order and deleted`() = runTest {
        val dao = database.transferDao()
        dao.upsert(transfer("b", createdAt = 2))
        dao.upsert(transfer("a", createdAt = 1))
        dao.upsert(transfer("b", createdAt = 2).copy(fileName = "renamed.bin"))

        assertEquals(listOf("a", "b"), dao.all().map { it.transferId })
        assertEquals("renamed.bin", dao.find("b")?.fileName)

        dao.delete("a")
        assertNull(dao.find("a"))
    }

    @Test
    fun `a seen app starts enabled and keeps its toggle on later sightings`() = runTest {
        val dao = database.mirroredAppDao()
        assertEquals(emptyList<String>(), dao.observeDisabled().first())

        dao.recordSeen("com.chat", "Chat", 1)
        dao.setEnabled("com.chat", false)
        dao.recordSeen("com.chat", "Chat 2", 2)

        assertEquals(listOf("com.chat"), dao.observeDisabled().first())
        val app = dao.observeAll().first().single()
        assertEquals("Chat 2", app.appLabel)
        assertEquals(false, app.enabled)
        assertEquals(2L, app.lastSeenAtMillis)
    }

    private fun transfer(id: String, createdAt: Long) = TransferEntity(
        transferId = id,
        direction = TransferEntity.FROM_DEVICE,
        path = "/storage/emulated/0/$id.bin",
        fileName = "$id.bin",
        size = 10,
        sha256 = null,
        createdAtMillis = createdAt,
    )
}
