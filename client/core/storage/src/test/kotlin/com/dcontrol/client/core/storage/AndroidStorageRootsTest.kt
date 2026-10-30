package com.dcontrol.client.core.storage

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.StorageVolumeBuilder

@RunWith(AndroidJUnit4::class)
class AndroidStorageRootsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var internal: File
    private lateinit var sdCard: File

    @Before
    fun setUp() {
        internal = temp.newFolder("emulated0")
        sdCard = temp.newFolder("sdcard")
        val manager = shadowOf(context.getSystemService(StorageManager::class.java))
        manager.resetStorageVolumeList()
        manager.addStorageVolume(
            StorageVolumeBuilder("primary", internal, "Internal shared storage", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(true)
                .build(),
        )
        manager.addStorageVolume(
            StorageVolumeBuilder("public:8,1", sdCard, "SD card", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setIsPrimary(false)
                .setIsRemovable(true)
                .setFsUuid("1A2B-3C4D")
                .build(),
        )
        manager.addStorageVolume(
            StorageVolumeBuilder("public:8,2", temp.newFolder("usb"), "USB", Process.myUserHandle(), Environment.MEDIA_UNMOUNTED)
                .setIsPrimary(false)
                .setIsRemovable(true)
                .build(),
        )
    }

    @Test
    fun `mounted volumes become roots with ids, labels and flags`() {
        val volumes = AndroidStorageRoots(context).volumes()

        assertEquals(listOf("primary", "1A2B-3C4D"), volumes.map { it.id })
        assertEquals(listOf("Internal shared storage", "SD card"), volumes.map { it.label })
        assertEquals(listOf(internal.absolutePath, sdCard.absolutePath), volumes.map { it.path })
        assertEquals(listOf(true, false), volumes.map { it.primary })
        assertEquals(listOf(false, true), volumes.map { it.removable })
    }

    @Test
    fun `app-private Android folders are blocked on API 30+`() {
        val blocked = AndroidStorageRoots(context).blockedPaths()

        assertEquals(
            listOf(File(internal, "Android/data"), File(internal, "Android/obb"), File(sdCard, "Android/data"), File(sdCard, "Android/obb")),
            blocked,
        )
    }
}
