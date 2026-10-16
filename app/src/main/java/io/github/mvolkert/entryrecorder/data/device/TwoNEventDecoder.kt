package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.model.Device
import java.util.Locale

/**
 * The decode half of the 2N Logging API transport: turn one `/api/log/pull` body into the existing
 * [IntercomEvent] types.
 *
 * Kept separate from [TwoNIPVersoDevice] — which owns the authenticated subscribe/pull/unsubscribe loop —
 * so the name-and-field mapping is a pure, device-free function a unit test can drive straight from a JSON
 * body. The transport hands it a parsed envelope and a tag and forwards each returned event to its
 * listener; nothing here touches the network or the wall clock.
 *
 * The `event` + `params` names are shared with the (now-removed) SSE decode, which is why a doorbell press,
 * camera motion and noise all map onto the same [IntercomEvent] types regardless of which transport fed
 * them. The exact pull envelope path is still provisional pending the on-device gate (task 3.3), so the
 * diagnostics are logged rather than swallowed: the gate reads the real field names out of logcat.
 */
object TwoNEventDecoder {

    // Wording this firmware family uses for boolean-ish event/status fields; anything else is treated as
    // "unknown" rather than guessed, so a wrong assumption shows up in the log instead of pinning a state.
    private val TRUE_WORDS = setOf("active", "true", "on", "yes", "start", "detected", "moving")
    private val FALSE_WORDS = setOf("inactive", "false", "off", "no", "stop", "idle", "clear", "none")

    // KeyPressed edges that are NOT a doorbell press; the press itself is the default, see decodeEvent.
    private val KEY_RELEASE_ACTIONS = setOf("released", "release", "up", "long", "hold", "held", "double")

    // Where the drained log entries may sit in the pull body; checked in order until one is an array.
    private val EVENT_ARRAY_KEYS = listOf("log", "logs", "entries", "events", "items")

    /** Fan a pull body out to events; an unrecognised envelope yields none and is logged, never dropped silently. */
    fun decodeAll(device: Device, root: JsonObject, tag: String): List<IntercomEvent> {
        val entries = extractEventElements(root)
        if (entries.isEmpty()) {
            Log.d(tag, "No event array in 2N Logging payload: ${root.toString().take(160)}")
            return emptyList()
        }
        return entries.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { decodeEvent(device, it, tag) }
        }
    }

    private fun extractEventElements(root: JsonObject): List<JsonElement> {
        val scopes = listOfNotNull(
            root,
            root.getAsJsonObjectOrNull("result"),
            root.getAsJsonObjectOrNull("data"),
        )
        for (obj in scopes) {
            for (key in EVENT_ARRAY_KEYS) {
                val el = obj.get(key)
                if (el != null && el.isJsonArray) return el.asJsonArray.asList()
            }
        }
        // A single event object (the shape the old SSE delivered) still decodes, so the reused handler works
        // for both transports.
        val single = root.getAsJsonObjectOrNull("event")
            ?: if (root.has("event") || root.has("type")) root else null
        return listOfNotNull(single)
    }

    private fun decodeEvent(device: Device, eventObj: JsonObject, tag: String): IntercomEvent? {
        val eventType = eventObj.get("event")?.takeIf { it.isJsonPrimitive }?.asString
            ?: eventObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val params = eventObj.getAsJsonObjectOrNull("params") ?: JsonObject()

        return when (eventType) {
            "MotionDetected" -> {
                // A missing or unrecognised `state` must not become "no motion": the previous decode
                // (`?.asBoolean ?: (asString == "active")`) fabricated a MotionEnded for every payload
                // it did not understand, so an unexpected field shape killed the trigger silently.
                when (triState(params.get("state")) ?: triState(params.get("active"))) {
                    true -> IntercomEvent.MotionStarted(device)
                    false -> IntercomEvent.MotionEnded(device)
                    null -> {
                        Log.w(tag, "MotionDetected without a usable state field: ${eventObj.toString().take(160)}")
                        null
                    }
                }
            }
            "NoiseDetected" -> {
                // `active` is the field name the status endpoints of this API use, so accept it as an
                // alternative spelling of the same boolean instead of guessing a new one.
                when (triState(params.get("state")) ?: triState(params.get("active"))) {
                    true -> IntercomEvent.NoiseStarted(device)
                    false -> IntercomEvent.NoiseEnded(device)
                    null -> {
                        Log.w(tag, "NoiseDetected without a usable state field: ${eventObj.toString().take(160)}")
                        null
                    }
                }
            }
            "KeyPressed" -> {
                // Reported for every key of the device and for both edges of a press. Only the known
                // release/hold edges are dropped: the press action string is not documented in this
                // repo, so an unknown action still rings and duplicates of one press are collapsed by
                // the monitor service's per-device ring debounce.
                val action = params.get("action")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.lowercase(Locale.US).orEmpty()
                if (action in KEY_RELEASE_ACTIONS) {
                    Log.d(tag, "KeyPressed action=$action ignored (not a press edge)")
                    null
                } else {
                    IntercomEvent.DoorbellRung(device, callerNumber = "Doorbell Button")
                }
            }
            "CallStateChanged" -> {
                val state = params.get("state")?.asString ?: ""
                val direction = params.get("direction")?.asString ?: ""
                Log.d(tag, "2N CallStateChanged: state=$state direction=$direction")
                // "dialing" is kept on purpose: a doorbell press makes the intercom dial this phone,
                // so from the device's point of view the ringing call is *outgoing*. Filtering by
                // direction would therefore discard the primary ring path; the debounce upstream is
                // what makes the overlap with KeyPressed harmless.
                if (state.equals("incoming", ignoreCase = true) || state.equals("ringing", ignoreCase = true) || state.equals("dialing", ignoreCase = true)) {
                    IntercomEvent.DoorbellRung(device, callerNumber = params.get("peer")?.asString)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Converts a 2N boolean-ish field: real booleans, the truthy/falsy words this firmware family uses
     * (`active`/`inactive`, `on`/`off`, `true`/`false`) and numbers. Null means the payload does not say,
     * which callers must handle as "no information" instead of defaulting to a definite state.
     */
    private fun triState(element: JsonElement?): Boolean? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asDouble != 0.0
            else -> when (primitive.asString.lowercase(Locale.US)) {
                in TRUE_WORDS -> true
                in FALSE_WORDS -> false
                else -> null
            }
        }
    }
}

/** Gson-safe `getAsJsonObject` that returns null for a missing member or a member of the wrong kind. */
internal fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
