package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.model.AncsNotification

/**
 * How a mirrored iPhone notification looks on the watch: the app's name and
 * accent color, who it is from (shown as an avatar), and how its text is laid
 * out. Pure Kotlin, so it is unit tested on the JVM; [NotificationAvatars]
 * draws the avatars.
 */
object NotificationLook {

    /** What a notification shows, decided from its ANCS attributes. */
    data class Content(
        /** Shown next to the avatar: the sender for chats, calls and email, otherwise the app. */
        val senderName: String,
        /** True when [senderName] is the app itself, so the avatar takes the app's color. */
        val senderIsApp: Boolean,
        /** Group chat name, when the iPhone sends one. */
        val conversationTitle: String?,
        /** Emphasized first line: an email subject or an app alert's title. */
        val headline: String?,
        val body: String
    )

    // Apps whose notifications are messages from a person: the title is the sender.
    private val chatApps = setOf(
        "com.apple.MobileSMS",
        "com.apple.mobilephone",
        "com.apple.facetime",
        "net.whatsapp.WhatsApp",
        "net.whatsapp.WhatsAppSMB",
        "ph.telegra.Telegraph",
        "org.telegram.Telegram",
        "com.facebook.Messenger",
        "org.whispersystems.signal",
        "com.tinyspeck.chatlyio",
        "com.slack.Slack",
        "com.hammerandchisel.discord",
        "com.microsoft.skype.teams",
        "com.burbn.instagram",
        "com.toyopagroup.picaboo",
    )

    private val mailApps = setOf(
        "com.apple.mobilemail",
        "com.google.Gmail",
        "com.microsoft.Office.Outlook",
    )

    /** Names for apps whose bundle ID doesn't read well. The iPhone's own name wins once known. */
    private val knownAppNames = mapOf(
        // Apple
        "com.apple.MobileSMS" to "Messages",
        "com.apple.mobilephone" to "Phone",
        "com.apple.mobilemail" to "Mail",
        "com.apple.mobilecal" to "Calendar",
        "com.apple.facetime" to "FaceTime",
        "com.apple.reminders" to "Reminders",
        "com.apple.news" to "News",
        "com.apple.mobilenotes" to "Notes",
        "com.apple.Health" to "Health",
        "com.apple.Fitness" to "Fitness",
        "com.apple.weather" to "Weather",
        "com.apple.Maps" to "Maps",
        "com.apple.Music" to "Music",
        "com.apple.stocks" to "Stocks",
        "com.apple.camera" to "Camera",
        "com.apple.Passbook" to "Wallet",
        "com.apple.Preferences" to "Settings",
        "com.apple.mobiletimer" to "Clock",
        "com.apple.podcasts" to "Podcasts",
        "com.apple.findmy" to "Find My",
        "com.apple.Photos" to "Photos",
        "com.apple.mobileslideshow" to "Photos",
        "com.apple.TestFlight" to "TestFlight",
        "com.apple.AppStore" to "App Store",
        "com.apple.Passwords" to "Passwords",
        "com.apple.tips" to "Tips",
        "com.apple.shortcuts" to "Shortcuts",
        "com.apple.Home" to "Home",
        "com.apple.iBooks" to "Books",
        "com.apple.tv" to "TV",
        "com.apple.ScreenTimeNotifications" to "Screen Time",

        // Messaging
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
        "ph.telegra.Telegraph" to "Telegram",
        "org.telegram.Telegram" to "Telegram",
        "com.facebook.Messenger" to "Messenger",
        "org.whispersystems.signal" to "Signal",
        "com.tinyspeck.chatlyio" to "Slack",
        "com.slack.Slack" to "Slack",
        "com.hammerandchisel.discord" to "Discord",
        "com.microsoft.skype.teams" to "Teams",

        // Social
        "com.atebits.Tweetie2" to "X",
        "com.burbn.instagram" to "Instagram",
        "com.facebook.Facebook" to "Facebook",
        "com.linkedin.LinkedIn" to "LinkedIn",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.toyopagroup.picaboo" to "Snapchat",
        "com.burbn.barcelona" to "Threads",
        "com.reddit.Reddit" to "Reddit",

        // Email & productivity
        "com.google.Gmail" to "Gmail",
        "com.microsoft.Office.Outlook" to "Outlook",
        "com.google.calendar" to "Google Calendar",
        "notion.id" to "Notion",
        "com.grammarly.keyboard" to "Grammarly",

        // Google
        "com.google.GoogleMobile" to "Google",
        "com.google.Maps" to "Google Maps",
        "com.google.Drive" to "Google Drive",
        "com.google.Photos" to "Google Photos",
        "com.google.photos" to "Google Photos",
        "com.google.Home" to "Google Home",
        "com.google.chrome.ios" to "Chrome",
        "com.google.ios.youtube" to "YouTube",

        // Media, travel, delivery
        "com.spotify.client" to "Spotify",
        "com.shazam.Shazam" to "Shazam",
        "com.netflix.Netflix" to "Netflix",
        "com.waze.iphone" to "Waze",
        "com.ubercab.UberClient" to "Uber",
        "com.ubercab.UberEats" to "Uber Eats",
        "com.deliveroo.orderapp" to "Deliveroo",

        // Finance
        "com.revolut.revolut" to "Revolut",
        "au.com.westpac.ConsultWPC" to "Westpac",
        "au.com.westpac.banking" to "Westpac",
        "com.stake.stake" to "Stake",
        "au.com.hellostake" to "Stake",
        "com.afterpay.afterpay-consumer" to "Afterpay",

        // Other
        "com.anytimefitness.club" to "Anytime Fitness",
        "com.anytimefitness.atfmobile" to "Anytime Fitness",
        "au.com.hotdoc.app" to "HotDoc",
        "com.hotdoc.patient" to "HotDoc",
        "com.jio.myjio" to "Jio",
        "com.ril.ajio" to "Jio",
        "au.com.auspost" to "AusPost",
        "com.auspost.MyPost" to "AusPost",
        "com.philips.hue.gen4" to "Hue",
        "com.signify.hue.blue" to "Hue",
        "com.microsoft.smartglass" to "Xbox",
        "com.microsoft.xboxapp" to "Xbox",
        "com.surfshark.vpnclient" to "Surfshark",
        "com.producthunt.ProductHuntApp" to "Product Hunt",
        "com.github.stormcrow" to "GitHub",
    )

    /**
     * Brand accent colors (ARGB), used to tint the notification icon and the
     * app's avatar. Picked to stay readable on the watch's black background, so
     * black brands get white.
     */
    private val appColors = mapOf(
        "com.apple.MobileSMS" to 0xFF34C759.toInt(),
        "com.apple.mobilephone" to 0xFF34C759.toInt(),
        "com.apple.facetime" to 0xFF34C759.toInt(),
        "com.apple.mobilemail" to 0xFF0A84FF.toInt(),
        "com.apple.mobilecal" to 0xFFFF453A.toInt(),
        "com.apple.reminders" to 0xFFFF9F0A.toInt(),
        "com.apple.mobiletimer" to 0xFFFF9F0A.toInt(),
        "com.apple.news" to 0xFFFF375F.toInt(),
        "com.apple.Health" to 0xFFFF375F.toInt(),
        "com.apple.Music" to 0xFFFF375F.toInt(),
        "com.apple.podcasts" to 0xFFBF5AF2.toInt(),
        "com.apple.findmy" to 0xFF34C759.toInt(),
        "com.apple.weather" to 0xFF64D2FF.toInt(),
        "net.whatsapp.WhatsApp" to 0xFF25D366.toInt(),
        "net.whatsapp.WhatsAppSMB" to 0xFF25D366.toInt(),
        "ph.telegra.Telegraph" to 0xFF2AABEE.toInt(),
        "org.telegram.Telegram" to 0xFF2AABEE.toInt(),
        "com.facebook.Messenger" to 0xFF0A7CFF.toInt(),
        "org.whispersystems.signal" to 0xFF3A76F0.toInt(),
        "com.tinyspeck.chatlyio" to 0xFFE01E5A.toInt(),
        "com.slack.Slack" to 0xFFE01E5A.toInt(),
        "com.hammerandchisel.discord" to 0xFF5865F2.toInt(),
        "com.microsoft.skype.teams" to 0xFF7B83EB.toInt(),
        "com.burbn.instagram" to 0xFFE1306C.toInt(),
        "com.facebook.Facebook" to 0xFF1877F2.toInt(),
        "com.linkedin.LinkedIn" to 0xFF0A66C2.toInt(),
        "com.reddit.Reddit" to 0xFFFF4500.toInt(),
        "com.toyopagroup.picaboo" to 0xFFFFFC00.toInt(),
        "com.zhiliaoapp.musically" to 0xFFFE2C55.toInt(),
        "com.atebits.Tweetie2" to 0xFFFFFFFF.toInt(),
        "com.burbn.barcelona" to 0xFFFFFFFF.toInt(),
        "com.google.Gmail" to 0xFFEA4335.toInt(),
        "com.microsoft.Office.Outlook" to 0xFF0078D4.toInt(),
        "com.google.calendar" to 0xFF4285F4.toInt(),
        "com.google.Maps" to 0xFF34A853.toInt(),
        "com.google.ios.youtube" to 0xFFFF0000.toInt(),
        "com.spotify.client" to 0xFF1DB954.toInt(),
        "com.netflix.Netflix" to 0xFFE50914.toInt(),
        "com.waze.iphone" to 0xFF33CCFF.toInt(),
        "com.ubercab.UberClient" to 0xFFFFFFFF.toInt(),
        "com.ubercab.UberEats" to 0xFF06C167.toInt(),
        "com.deliveroo.orderapp" to 0xFF00CCBC.toInt(),
    )

    /** Avatar colors for people and unknown apps; all take white text. */
    val palette = intArrayOf(
        0xFFE53935.toInt(), // red
        0xFFD81B60.toInt(), // pink
        0xFF8E24AA.toInt(), // purple
        0xFF5E35B1.toInt(), // deep purple
        0xFF3949AB.toInt(), // indigo
        0xFF1E88E5.toInt(), // blue
        0xFF0288D1.toInt(), // light blue
        0xFF00897B.toInt(), // teal
        0xFF43A047.toInt(), // green
        0xFFF4511E.toInt(), // deep orange
        0xFF6D4C41.toInt(), // brown
        0xFF546E7A.toInt(), // blue grey
    )

    private const val CALL_GREEN = 0xFF34C759.toInt()
    private const val MAIL_BLUE = 0xFF0A84FF.toInt()
    private const val SCHEDULE_ORANGE = 0xFFFF9F0A.toInt()
    private const val DARK_TEXT = 0xFF1C1C1E.toInt()
    private const val LIGHT_TEXT = 0xFFFFFFFF.toInt()

    /** Trailing bundle ID components that say nothing about the app. */
    private val genericComponents = setOf(
        "app", "apps", "ios", "iphone", "ipad", "mobile", "client", "prod", "production",
        "release", "main", "consumer", "application"
    )

    fun isChatApp(appIdentifier: String): Boolean = appIdentifier in chatApps

    fun isMailApp(appIdentifier: String, categoryId: Int): Boolean =
        appIdentifier in mailApps || categoryId == AncsConstants.CATEGORY_EMAIL

    /** A readable app name when the iPhone hasn't told us its real one yet. */
    fun fallbackAppName(appIdentifier: String): String =
        knownAppNames[appIdentifier] ?: prettifyBundleId(appIdentifier)

    /** "com.burbn.instagram" -> "Instagram", "com.acme.FooBar" -> "Foo Bar", "com.vinted.app" -> "Vinted". */
    fun prettifyBundleId(appIdentifier: String): String {
        val parts = appIdentifier.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return appIdentifier
        var index = parts.lastIndex
        while (index > 0 && parts[index].lowercase() in genericComponents) index--
        val words = parts[index]
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return appIdentifier
        return words.joinToString(" ") { word ->
            if (word.all { it.isLowerCase() }) word.replaceFirstChar { it.titlecase() } else word
        }
    }

    /** The app's accent color: its brand color, else one by category, else a stable palette color. */
    fun accentColor(appIdentifier: String, categoryId: Int): Int =
        appColors[appIdentifier] ?: when (categoryId) {
            AncsConstants.CATEGORY_INCOMING_CALL,
            AncsConstants.CATEGORY_MISSED_CALL,
            AncsConstants.CATEGORY_VOICEMAIL -> CALL_GREEN
            AncsConstants.CATEGORY_EMAIL -> MAIL_BLUE
            AncsConstants.CATEGORY_SCHEDULE -> SCHEDULE_ORANGE
            else -> paletteColor(appIdentifier)
        }

    /** A stable color per person, so the same contact always looks the same. */
    fun avatarColor(name: String): Int = paletteColor(name.trim().lowercase())

    private fun paletteColor(key: String): Int = palette[(key.hashCode() and 0x7FFFFFFF) % palette.size]

    /** White or dark text, whichever reads better on [background]. */
    fun textColorOn(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        return if (0.299 * r + 0.587 * g + 0.114 * b > 186) DARK_TEXT else LIGHT_TEXT
    }

    /**
     * Up to two initials for an avatar ("Marie Dupont" -> "MD"). Phone numbers
     * give "" (the avatar shows a person outline instead), names without
     * letters give their first symbol (often an emoji).
     */
    fun initials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || isPhoneNumber(trimmed)) return ""
        val letters = trimmed.split(Regex("\\s+")).mapNotNull(::firstLetter)
        return when {
            letters.isEmpty() -> String(Character.toChars(trimmed.codePointAt(0)))
            letters.size == 1 -> letters[0]
            else -> letters.first() + letters.last()
        }
    }

    private fun firstLetter(word: String): String? {
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.isLetter(cp)) return String(Character.toChars(cp)).uppercase()
            i += Character.charCount(cp)
        }
        return null
    }

    private fun isPhoneNumber(text: String): Boolean =
        text.any { it.isDigit() } && text.all { it.isDigit() || it in "+-()./  " }

    /** Lays out a notification: who it is from, and its text. */
    fun content(notification: AncsNotification, appName: String): Content {
        val title = notification.title.trim()
        val subtitle = notification.subtitle?.trim().orEmpty()
        val message = notification.message.trim()
        val appId = notification.appIdentifier

        if (isMailApp(appId, notification.categoryId)) {
            return Content(
                senderName = title.ifEmpty { appName },
                senderIsApp = title.isEmpty(),
                conversationTitle = null,
                headline = subtitle.ifEmpty { null },
                body = message
            )
        }

        val fromPerson = isChatApp(appId) ||
            notification.categoryId == AncsConstants.CATEGORY_MISSED_CALL ||
            notification.categoryId == AncsConstants.CATEGORY_VOICEMAIL
        if (fromPerson && title.isNotEmpty()) {
            // A subtitle on a chat is the group or channel, when the app sends one.
            val group = subtitle.takeIf { it.isNotEmpty() && message.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            return Content(
                senderName = title,
                senderIsApp = false,
                conversationTitle = group,
                headline = null,
                body = message.ifEmpty { subtitle }
            )
        }

        // App alerts: the app is the sender and the title becomes the first line.
        val headline = title.takeIf { it.isNotEmpty() && !it.equals(appName, ignoreCase = true) }
        val body = listOf(subtitle, message)
            .filter { it.isNotEmpty() && !it.equals(headline, ignoreCase = true) }
            .joinToString("\n")
        return Content(
            senderName = appName,
            senderIsApp = true,
            conversationTitle = null,
            headline = headline,
            body = body
        )
    }
}
