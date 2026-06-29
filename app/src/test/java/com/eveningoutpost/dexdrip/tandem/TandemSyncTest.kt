package com.eveningoutpost.dexdrip.tandem

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Defaults and round-tripping for [TandemSync] — the per-data-type opt-in shown on the Settings tab.
 * Uses Robolectric because the flags live in xDrip's Pref (SharedPreferences). On a fresh install
 * everything is enabled except glucose, which would clash with an existing CGM session.
 */
class TandemSyncTest : RobolectricTestWithConfig() {

    @Test
    fun defaults_everythingEnabledExceptGlucose() {
        assertThat(TandemSync.isOn(TandemSync.BOLUSES)).isTrue()
        assertThat(TandemSync.isOn(TandemSync.CARBS)).isTrue()
        assertThat(TandemSync.isOn(TandemSync.BASAL)).isTrue()
        assertThat(TandemSync.isOn(TandemSync.PROFILE)).isTrue()
        assertThat(TandemSync.isOn(TandemSync.GLUCOSE)).isFalse()
    }

    @Test
    fun anySelected_trueByDefault() {
        assertThat(TandemSync.anySelected()).isTrue()
    }

    @Test
    fun anySelected_falseWhenEverythingTurnedOff() {
        for (k in listOf(TandemSync.BOLUSES, TandemSync.CARBS, TandemSync.BASAL, TandemSync.PROFILE, TandemSync.GLUCOSE)) {
            TandemSync.set(k, false)
        }
        assertThat(TandemSync.anySelected()).isFalse()
    }

    @Test
    fun set_overridesTheDefault() {
        assertThat(TandemSync.isOn(TandemSync.GLUCOSE)).isFalse()
        TandemSync.set(TandemSync.GLUCOSE, true)
        assertThat(TandemSync.isOn(TandemSync.GLUCOSE)).isTrue()
    }
}
