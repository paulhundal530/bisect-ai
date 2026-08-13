package com.bisectai.evaluation

/** A human's verdict on a candidate commit in manual classification mode. */
enum class ManualVerdict {
    /** The regression is absent -> GOOD. */
    GOOD,

    /** The regression is present -> BAD. */
    BAD,

    /** Can't tell -> UNKNOWN (git bisect skip). */
    SKIP,

    /** The tester wants to stop the investigation. */
    ABORT,
}

/** A verdict plus an optional free-text observation that grounds the later AI analysis. */
data class VerdictResult(val verdict: ManualVerdict, val note: String?)

/**
 * Source of human verdicts for manual classification (§13 manual mode). Abstracted behind an
 * interface so the interactive terminal implementation can be swapped for a scripted one in
 * tests — mirroring how [ProcessRunner] and the analysis provider are injected.
 */
interface VerdictReader {
    /** True if a verdict can actually be collected (e.g. an interactive terminal is present). */
    fun isAvailable(): Boolean = true

    /** Prompt for and return a verdict on [commit]; [instructions] describe the journey to test. */
    fun read(commit: String, instructions: String?): VerdictResult
}
