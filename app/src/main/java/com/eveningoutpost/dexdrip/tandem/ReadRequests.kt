package com.eveningoutpost.dexdrip.tandem

import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.request.currentStatus.*

/**
 * READ-ONLY snapshot requests fired once after connecting (status + settings only;
 * never insulin-delivery commands). All confirmed no-arg in pumpX2 v1.9.0.
 * The bulk time-series comes from the history log; these give the live snapshot.
 */
object ReadRequests {
    fun all(): List<Message> = listOf(
        ApiVersionRequest(),
        PumpVersionRequest(),
        TimeSinceResetRequest(),
        CurrentBatteryV1Request(),
        CurrentBatteryV2Request(),
        InsulinStatusRequest(),
        CurrentBasalStatusRequest(),
        CurrentBolusStatusRequest(),
        LastBolusStatusV2Request(),
        ControlIQIOBRequest(),
        ControlIQInfoV1Request(),
        BasalIQStatusRequest(),
        CurrentEGVGuiDataRequest(),
        CGMStatusRequest(),
        LastBGRequest(),
        AlertStatusRequest(),
        AlarmStatusRequest(),
        MalfunctionStatusRequest(),
        ProfileStatusRequest(),
        CurrentActiveIdpValuesRequest(),
        PumpSettingsRequest(),
        PumpGlobalsRequest(),
        PumpFeaturesV1Request(),
        GlobalMaxBolusSettingsRequest(),
        BasalLimitSettingsRequest(),
        ReminderStatusRequest(),
        HomeScreenMirrorRequest()
    )
}
