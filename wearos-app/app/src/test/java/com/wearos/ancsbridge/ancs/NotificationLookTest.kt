package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.model.AncsNotification
import org.junit.Assert.*
import org.junit.Test

class NotificationLookTest {

    private fun notification(
        app: String,
        title: String = "",
        subtitle: String? = null,
        message: String = "",
        category: Int = AncsConstants.CATEGORY_OTHER
    ) = AncsNotification(
        uid = 1,
        appIdentifier = app,
        appDisplayName = null,
        title = title,
        subtitle = subtitle,
        message = message,
        date = null,
        categoryId = category,
        eventFlags = 0,
        positiveActionLabel = null,
        negativeActionLabel = null
    )

    @Test
    fun `chat message comes from the person in the title`() {
        val look = NotificationLook.content(
            notification("net.whatsapp.WhatsApp", title = "Marie", message = "On se voit à 8h ?"),
            appName = "WhatsApp"
        )
        assertEquals("Marie", look.senderName)
        assertFalse(look.senderIsApp)
        assertNull(look.conversationTitle)
        assertNull(look.headline)
        assertEquals("On se voit à 8h ?", look.body)
    }

    @Test
    fun `chat subtitle becomes the group name`() {
        val look = NotificationLook.content(
            notification("com.apple.MobileSMS", title = "Marie", subtitle = "Foot du dimanche", message = "Je ramène le ballon"),
            appName = "Messages"
        )
        assertEquals("Marie", look.senderName)
        assertEquals("Foot du dimanche", look.conversationTitle)
        assertEquals("Je ramène le ballon", look.body)
    }

    @Test
    fun `app alert puts the app first and the title as headline`() {
        val look = NotificationLook.content(
            notification("com.ubercab.UberEats", title = "Commande en route", message = "Arrivée dans 5 min"),
            appName = "Uber Eats"
        )
        assertEquals("Uber Eats", look.senderName)
        assertTrue(look.senderIsApp)
        assertEquals("Commande en route", look.headline)
        assertEquals("Arrivée dans 5 min", look.body)
    }

    @Test
    fun `app alert whose title is the app name has no headline`() {
        val look = NotificationLook.content(
            notification("com.example.weather", title = "Weather", message = "Rain at 3 pm"),
            appName = "Weather"
        )
        assertNull(look.headline)
        assertEquals("Rain at 3 pm", look.body)
    }

    @Test
    fun `email shows the sender and the subject as headline`() {
        val look = NotificationLook.content(
            notification(
                "com.google.Gmail", title = "Isabelle", subtitle = "Planning lundi",
                message = "Bonjour, voici le planning", category = AncsConstants.CATEGORY_EMAIL
            ),
            appName = "Gmail"
        )
        assertEquals("Isabelle", look.senderName)
        assertFalse(look.senderIsApp)
        assertEquals("Planning lundi", look.headline)
        assertEquals("Bonjour, voici le planning", look.body)
    }

    @Test
    fun `missed call comes from the caller`() {
        val look = NotificationLook.content(
            notification("com.apple.mobilephone", title = "Papa", message = "Appel manqué", category = AncsConstants.CATEGORY_MISSED_CALL),
            appName = "Téléphone"
        )
        assertEquals("Papa", look.senderName)
        assertFalse(look.senderIsApp)
    }

    @Test
    fun `chat without a title falls back to the app`() {
        val look = NotificationLook.content(
            notification("net.whatsapp.WhatsApp", message = "Sauvegarde terminée"),
            appName = "WhatsApp"
        )
        assertEquals("WhatsApp", look.senderName)
        assertTrue(look.senderIsApp)
        assertEquals("Sauvegarde terminée", look.body)
    }

    @Test
    fun `initials from names, numbers and emoji`() {
        assertEquals("MD", NotificationLook.initials("Marie Dupont"))
        assertEquals("M", NotificationLook.initials("marie"))
        assertEquals("JM", NotificationLook.initials("Jean-Pierre (Boulot) Martin"))
        assertEquals("", NotificationLook.initials("+32 470 12 34 56"))
        assertEquals("É", NotificationLook.initials("émilie"))
        assertEquals("🎉", NotificationLook.initials("🎉"))
        assertEquals("M", NotificationLook.initials("❤️ Maman"))
    }

    @Test
    fun `bundle IDs read as app names`() {
        assertEquals("Instagram", NotificationLook.fallbackAppName("com.burbn.instagram"))
        assertEquals("Telegram", NotificationLook.fallbackAppName("ph.telegra.Telegraph"))
        assertEquals("Vinted", NotificationLook.prettifyBundleId("com.vinted.app"))
        assertEquals("Foo Bar", NotificationLook.prettifyBundleId("com.acme.FooBar"))
        assertEquals("My Bank", NotificationLook.prettifyBundleId("be.example.my-bank"))
        assertEquals("Itsme", NotificationLook.prettifyBundleId("be.bmid.itsme.ios"))
        assertEquals("", NotificationLook.prettifyBundleId(""))
    }

    @Test
    fun `accent colors are stable and readable`() {
        assertEquals(0xFF25D366.toInt(), NotificationLook.accentColor("net.whatsapp.WhatsApp", AncsConstants.CATEGORY_SOCIAL))
        // Unknown apps get a palette color that doesn't change between notifications.
        val first = NotificationLook.accentColor("com.example.unknown", AncsConstants.CATEGORY_OTHER)
        assertEquals(first, NotificationLook.accentColor("com.example.unknown", AncsConstants.CATEGORY_OTHER))
        assertTrue(first in NotificationLook.palette)
        assertEquals(NotificationLook.avatarColor("Marie"), NotificationLook.avatarColor(" marie "))
        // Dark text on Snapchat yellow, white text on palette colors.
        assertEquals(0xFF1C1C1E.toInt(), NotificationLook.textColorOn(0xFFFFFC00.toInt()))
        NotificationLook.palette.forEach { assertEquals(0xFFFFFFFF.toInt(), NotificationLook.textColorOn(it)) }
    }
}
