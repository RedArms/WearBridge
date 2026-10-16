package com.wearos.ancsbridge.alarm

import com.wearos.ancsbridge.ble.AncsConstants

/**
 * An iPhone Clock alarm (or timer) as seen through ANCS, with the actions the
 * iPhone offers on it. Some iPhones don't send their alarms over ANCS at all
 * (reported on iPhone 14 and later), which is why the companion app can also
 * start the watch alarm (see the wake alarm characteristic).
 */
data class ClockAlarm(
    val uid: Long,
    val positiveActionLabel: String?,
    val negativeActionLabel: String?
) {

    companion object {
        const val CLOCK_APP = "com.apple.mobiletimer"

        private val stopWords = listOf("stop", "arrêter", "arreter", "arrêt", "dismiss", "off", "désactiver")
        private val snoozeWords = listOf("snooze", "répéter", "repeter", "rappel", "later", "plus tard", "schlummern")

        /** A Clock notification the iPhone lets us act on: an alarm or timer that is ringing. */
        fun from(
            uid: Long,
            appIdentifier: String,
            positiveActionLabel: String?,
            negativeActionLabel: String?
        ): ClockAlarm? {
            if (appIdentifier != CLOCK_APP) return null
            if (positiveActionLabel.isNullOrBlank() && negativeActionLabel.isNullOrBlank()) return null
            return ClockAlarm(uid, positiveActionLabel, negativeActionLabel)
        }

        private fun matches(label: String?, words: List<String>): Boolean {
            val lower = label?.lowercase() ?: return false
            return words.any { lower.contains(it) }
        }
    }

    /** The ANCS action that stops the alarm on the iPhone, if one is recognizable. */
    val stopActionId: Int?
        get() = when {
            matches(negativeActionLabel, stopWords) -> AncsConstants.ACTION_NEGATIVE
            matches(positiveActionLabel, stopWords) -> AncsConstants.ACTION_POSITIVE
            // A single unrecognized action next to a snooze action is the stop action.
            matches(positiveActionLabel, snoozeWords) && negativeActionLabel != null -> AncsConstants.ACTION_NEGATIVE
            matches(negativeActionLabel, snoozeWords) && positiveActionLabel != null -> AncsConstants.ACTION_POSITIVE
            else -> null
        }

    /** The ANCS action that snoozes the alarm on the iPhone, if there is one. */
    val snoozeActionId: Int?
        get() = when {
            matches(positiveActionLabel, snoozeWords) -> AncsConstants.ACTION_POSITIVE
            matches(negativeActionLabel, snoozeWords) -> AncsConstants.ACTION_NEGATIVE
            else -> null
        }
}
