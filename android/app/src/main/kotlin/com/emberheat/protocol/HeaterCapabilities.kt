package com.emberheat.protocol

// Per-driver capability declaration. These describe what the heater
// itself can do natively — NOT what the app can do on top.
//
// Distinction matters: Schedule, Groups, AutoStartStop are app-driven
// features that only require start()/stop() to work. They are gated
// on `hasStart && hasStop`, not on a dedicated flag here.
//
// The UI consumes this to gate buttons, hide whole pages, show "—"
// for unsupported telemetry fields, etc.
data class HeaterCapabilities(
    // --- Core control surface --------------------------------------
    val hasStart:      Boolean = false,
    val hasStop:       Boolean = false,
    val hasVent:       Boolean = false,
    val hasTargetTemp: Boolean = false,
    val hasGear:       Boolean = false,
    val hasRunModes:   Boolean = false,

    // --- Diagnostic / advanced surface -----------------------------
    val hasAltitude:        Boolean = false,
    val hasFaultBits:       Boolean = false,
    val hasManualPump:      Boolean = false,
    val hasNativeSchedule:  Boolean = false,  // heater stores its own table — read-only mirror
    val hasNativeSwitches:  Boolean = false,  // auto-restart / child-lock / fault-lockout flags
    val hasRawFrameStream:  Boolean = false,  // debug box wants this

    // --- Telemetry richness ----------------------------------------
    // Each flag = "this heater publishes this number". UI shows the
    // tile only when true; falls back to "—" otherwise.
    val telemAmbient:  Boolean = false,
    val telemHousing:  Boolean = false,
    val telemIntake:   Boolean = false,
    val telemOutlet:   Boolean = false,
    val telemBattery:  Boolean = false,
    val telemFanRpm:   Boolean = false,
    val telemPumpHz:   Boolean = false,
    val telemIgnition: Boolean = false,
) {
    companion object {
        /** HeatGenie reports everything — it's the reference for "fully featured". */
        val HEATGENIE = HeaterCapabilities(
            hasStart           = true,
            hasStop            = true,
            hasVent            = true,
            hasTargetTemp      = true,
            hasGear            = true,
            hasRunModes        = true,
            hasAltitude        = true,
            hasFaultBits       = true,
            hasManualPump      = true,
            hasNativeSchedule  = true,
            hasNativeSwitches  = true,
            hasRawFrameStream  = true,
            telemAmbient       = true,
            telemHousing       = true,
            telemIntake        = true,
            telemOutlet        = true,
            telemBattery       = true,
            telemFanRpm        = true,
            telemPumpHz        = true,
            telemIgnition      = true,
        )

        /**
         * HCalory baseline. Conservative — assumes start/stop + target temp +
         * a few common telemetry DPs. Confirm with DP catalog work next session;
         * flags here are intentionally cautious so UI hides things we're unsure
         * about rather than showing them broken.
         */
        val HCALORY = HeaterCapabilities(
            hasStart           = true,
            hasStop            = true,
            hasTargetTemp      = true,
            hasGear            = true,    // most HCalory units expose gear/level DP
            hasRunModes        = true,
            telemAmbient       = true,
            telemBattery       = true,
            // Vent, altitude, pump, fault bits, native schedule/switches,
            // intake/housing/outlet temps — left false until confirmed.
        )
    }
}
