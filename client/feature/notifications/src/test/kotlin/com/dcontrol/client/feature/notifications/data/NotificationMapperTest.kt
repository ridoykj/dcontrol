package com.dcontrol.client.feature.notifications.data

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationMapperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mapper = NotificationMapper(context)

    @Test
    fun `maps fields, prefers big text and truncates to the protocol limits`() {
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("T".repeat(300))
            .setContentText("short")
            .setSubText("sub")
            .build()
        // Builder caps CharSequences itself; put the long text straight into the extras.
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "B".repeat(2500))
        val sbn = sbn("com.chat", notification)

        val payload = mapper.map(sbn, mapper.app(sbn), iconHash = "a".repeat(64))

        assertEquals(sbn.key, payload.key)
        assertEquals("com.chat", payload.packageName)
        assertEquals(200, payload.title!!.length)
        assertEquals(2000, payload.text!!.length)
        assertTrue(payload.text!!.all { it == 'B' })
        assertEquals("sub", payload.subText)
        assertEquals("1970-01-01T00:00:05Z", payload.postedAt)
        assertEquals("a".repeat(64), payload.iconHash)
        assertFalse(payload.canReply)
        assertTrue(payload.clearable)
    }

    @Test
    fun `app label falls back to the package name without builder app info`() {
        val notification = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        notification.extras.remove(NotificationMapper.EXTRA_BUILDER_APPLICATION_INFO)

        val app = mapper.app(sbn("com.unknown", notification))

        assertEquals("com.unknown", app.label)
        assertNull(app.info)
    }

    @Test
    fun `own notifications and group summaries are not mirrored`() {
        val plain = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        val summary = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup("g")
            .setGroupSummary(true)
            .build()

        assertTrue(mapper.isMirrorable(sbn("com.chat", plain)))
        assertFalse(mapper.isMirrorable(sbn(context.packageName, plain)))
        assertFalse(mapper.isMirrorable(sbn("com.chat", summary)))
    }

    private fun sbn(packageName: String, notification: Notification) =
        StatusBarNotification(packageName, packageName, 7, "tag", 0, 0, 0, notification, Process.myUserHandle(), 5_000)
}
