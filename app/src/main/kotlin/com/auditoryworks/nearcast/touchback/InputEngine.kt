package com.auditoryworks.nearcast.touchback

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NativeCapabilities(val ready: Boolean, val maxContacts: Int, val cleanupBlocked: Boolean = false, val ownerId: String? = null)
data class InputBinding(val sessionId: String, val runtimeInstanceId: String, val target: JSONObject,
    val sourceBindingId: String, val geometry: ScreenGeometry)
data class InputOwner(val id: String, val streamId: Long, val generation: Long, val binding: InputBinding)
interface InputExecutor {
    fun capabilities(): NativeCapabilities
    suspend fun prepare(owner: InputOwner): Boolean
    suspend fun apply(owner: InputOwner, plan: GesturePlan): Boolean
    suspend fun release(owner: InputOwner): Boolean
}
class InputEngine(private val scope: CoroutineScope, private val executor: InputExecutor,
    private val operationTimeoutMs: Long = 3000, private val cleanupTimeoutMs: Long = 3000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val onChanged: () -> Unit = {}) {
    var state = "closed"; private set
    var owner: InputOwner? = null; private set
    var lastError: String? = null; private set
    var submittedSamples = 0L; private set
    var discardedSamples = 0L; private set
    var coalescedWheelSamples = 0L; private set
    private var binding: InputBinding? = null
    private var generation = 0L
    private var bindingToken = 0L
    private var limits: InputLimits? = null
    private var ingress = InputState()
    private var submitted = InputState()
    private var planner = GesturePlanner()
    private val nativeMutex = Mutex()
    private val openMutex = Mutex()
    private var cleanupActual: Deferred<Boolean>? = null
    private val nextIds = mutableMapOf<String, Long>()
    private data class Command(val fingerprint: String, val result: JSONObject)
    private val commands = linkedMapOf<String, Command>()
    private val closed = linkedMapOf<String, InputBinding>()
    private data class Queued(val sample: InputSample, val candidate: InputState, val generation: Long,
        val at: Long, val result: CompletableDeferred<Boolean>)
    private val queue = ArrayDeque<Queued>()
    private var pumping = false
    private fun changed() { runCatching { onChanged() } }

    // All APIs run on the controller dispatcher. Revoke before awaiting any OS work.
    fun revoke() {
        binding = null; bindingToken++; generation++
        queue.forEach { it.result.complete(false) }; queue.clear()
        if (owner != null) state = "closing"
        changed()
    }
    suspend fun bind(binding: InputBinding?) {
        if (this.binding?.sourceBindingId == binding?.sourceBindingId && this.binding != null) return
        revoke()
        val token = bindingToken
        close()
        requireInput(token == bindingToken, "Stale source binding", AxtpContract.Error.CANCELED)
        requireInput(state == "closed", "Cleanup pending", AxtpContract.Error.BUSY)
        this.binding = binding?.copy(target = JSONObject(binding.target.toString()))
        changed()
    }
    private fun identity(params: JSONObject, sid: String, expected: InputBinding) {
        requireInput(isText(sid) && sid == expected.sessionId && params.opt("runtimeInstanceId") == expected.runtimeInstanceId &&
            canonicalJson(params.opt("target")) == canonicalJson(expected.target), "Authenticated source/target mismatch", AxtpContract.Error.PERMISSION_DENIED)
    }
    private fun negotiate(params: JSONObject, caps: NativeCapabilities): Pair<InputLimits, JSONObject> {
        requireInput(params.uint("controlVersion", 1, 1) == 1L && params.opt("streamKind") == "touchback" &&
            params.opt("format") == AxtpContract.FORMAT, "Input format/control version", AxtpContract.Error.NOT_SUPPORTED)
        val requested = params.strings("inputTypes")
        requireInput(requested.isNotEmpty() && requested.toSet().size == requested.size && requested.all { it in setOf("touch", "mouse") }, "Input types")
        requireInput((params.opt("touch") is JSONObject) == ("touch" in requested) && (params.opt("mouse") is JSONObject) == ("mouse" in requested), "Per-type constraints")
        val budget = params.uint("maxDataBytes", 8, 4096).toInt()
        val maxContacts = if ("touch" in requested) minOf(params.getJSONObject("touch").uint("maxContacts", 1, 256).toInt(), caps.maxContacts, (budget - 1) / 7) else 0
        var wheel = false
        if ("mouse" in requested) {
            val m = params.getJSONObject("mouse")
            m.uint("buttonsMask", 0, 31); m.uint("modifierMask", 0, 15)
            requireInput(m.opt("allowChords") is Boolean && m.opt("continuousScroll") is Boolean, "Mouse flags")
            val units = m.strings("wheelUnits")
            requireInput(units.toSet().size == units.size && units.all { it in setOf("detent120", "regionQ16_16") }, "Mouse wheel units")
            wheel = "detent120" in units
            requireInput(budget >= if (wheel) 22 else 12, "Mouse data budget", AxtpContract.Error.NOT_SUPPORTED)
        }
        val result = JSONObject().put("format", AxtpContract.FORMAT).put("accepted", true)
            .put("acceptedInputTypes", JSONArray(requested)).put("maxDataBytes", budget)
        if ("touch" in requested) result.put("touch", JSONObject().put("maxContacts", maxContacts))
        val buttons = if ("mouse" in requested) params.getJSONObject("mouse").getLong("buttonsMask") and AndroidMousePolicy.ACCEPTED_BUTTONS_MASK else 0
        if ("mouse" in requested) result.put("mouse", JSONObject().put("buttonsMask", buttons).put("modifierMask", 0)
            .put("allowChords", false).put("wheelUnits", JSONArray(if (wheel) listOf("detent120") else emptyList<String>())).put("continuousScroll", false))
        return InputLimits(requested.toSet(), maxContacts, budget, buttons, wheelUnits = if (wheel) setOf(0) else emptySet()) to result
    }
    private suspend fun <T> native(block: suspend () -> T): T {
        // A timeout cancels only the await, never the actual in-flight operation.
        val actual = scope.async { nativeMutex.withLock { block() } }
        return withTimeout(operationTimeoutMs) { actual.await() }
    }
    suspend fun open(params: JSONObject, sid: String): JSONObject = openMutex.withLock {
        val source = binding ?: throw ProtocolFailure(AxtpContract.Error.PERMISSION_DENIED, "Source unavailable")
        identity(params, sid, source)
        requireInput(params.opt("sourceBindingId") == source.sourceBindingId && isText(params.opt("commandId")), "Source binding/commandId")
        val key = "$sid:${params.getString("commandId")}"; val fingerprint = canonicalJson(params)
        commands[key]?.let {
            requireInput(it.fingerprint == fingerprint, "Conflicting commandId")
            val caps = executor.capabilities()
            requireInput(state == "active" && owner?.streamId == it.result.getLong("streamId") && caps.ready && !caps.cleanupBlocked,
                "Retired open/readiness lost", AxtpContract.Error.INVALID_STATE)
            return@withLock JSONObject(it.result.toString())
        }
        requireInput(state == "closed" && owner == null, "Native owner/cleanup pending", AxtpContract.Error.BUSY)
        val caps = executor.capabilities()
        requireInput(caps.ownerId == null, "Native input domain busy", AxtpContract.Error.BUSY)
        requireInput(caps.ready && !caps.cleanupBlocked && caps.maxContacts > 0, "Native input not ready", AxtpContract.Error.NOT_SUPPORTED)
        val (accepted, response) = negotiate(params, caps)
        val id = nextIds[sid] ?: 1L
        requireInput(id <= 0xffffffffL, "Stream IDs exhausted", AxtpContract.Error.RESOURCE_EXHAUSTED)
        nextIds[sid] = id + 1; generation++
        val current = InputOwner(UUID.randomUUID().toString(), id, generation, source)
        owner = current; state = "opening"; lastError = null; changed()
        try {
            requireInput(native { executor.prepare(current) }, "Native prepare unconfirmed", AxtpContract.Error.UNAVAILABLE)
            requireInput(owner === current && generation == current.generation && binding === source, "Stale prepare", AxtpContract.Error.CANCELED)
            limits = accepted; planner = GesturePlanner(); ingress = InputState(); submitted = InputState()
            response.put("streamId", id).put("revision", generation.toString())
            state = "active"; commands[key] = Command(fingerprint, JSONObject(response.toString()))
            while (commands.size > 64) commands.remove(commands.keys.first())
            changed(); JSONObject(response.toString())
        } catch (failure: Throwable) {
            lastError = failure.message
            close()
            throw failure
        }
    }
    private fun motion(sample: InputSample) = if (sample.type == "touch") sample.contacts.all { it.phase == 1 } else sample.kind == 0
    private fun supersedes(previous: InputSample, next: InputSample): Boolean = previous.type == next.type && motion(previous) && motion(next) &&
        (if (next.type == "touch") previous.contacts.all { p -> next.contacts.any { it.id == p.id } }
        else previous.buttons == next.buttons && previous.modifiers == next.modifiers)
    private fun combineWheels(previous: InputSample, next: InputSample): InputSample? {
        if (previous.type != "mouse" || next.type != "mouse" || previous.kind != 2 || next.kind != 2 ||
            previous.streamId != next.streamId || previous.buttons != next.buttons || previous.modifiers != next.modifiers ||
            previous.x != next.x || previous.y != next.y || previous.wheelUnit != 0 || next.wheelUnit != 0 ||
            previous.wheelPhase != 0 || next.wheelPhase != 0) return null
        fun sameDirection(a: Int, b: Int) = a == 0 || b == 0 || (a > 0) == (b > 0)
        if (!sameDirection(previous.wheelX, next.wheelX) || !sameDirection(previous.wheelY, next.wheelY)) return null
        val x = previous.wheelX.toLong() + next.wheelX
        val y = previous.wheelY.toLong() + next.wheelY
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return next.copy(wheelX = x.toInt(), wheelY = y.toInt())
    }
    fun accept(sample: InputSample): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        if (state != "active" || owner?.streamId != sample.streamId) { discardedSamples++; result.complete(false); return result }
        try {
            val candidate = ingress.next(sample, limits!!)
            val previous = queue.lastOrNull()
            val combined = previous?.takeIf { it.generation == generation }?.let { combineWheels(it.sample, sample) }
            val pendingSample = combined ?: sample
            val queuedAt = if (combined != null) previous.at else now()
            if (combined != null) {
                // Retire the individual envelope, preserving its delta and oldest
                // deadline in the batch. The latest result tracks native completion.
                queue.removeLast().result.complete(false); coalescedWheelSamples++
            } else if (previous != null && supersedes(previous.sample, sample)) {
                queue.removeLast().result.complete(false); discardedSamples++
            }
            requireInput(queue.size < 64 && queue.sumOf { it.sample.dataBytes } + pendingSample.dataBytes <= 65536 &&
                queue.sumOf { maxOf(it.sample.contacts.size, 1) } + maxOf(pendingSample.contacts.size, 1) <= 2048, "Input queue budget", AxtpContract.Error.RESOURCE_EXHAUSTED)
            ingress = candidate
            queue.addLast(Queued(pendingSample, candidate, generation, queuedAt, result))
            if (!pumping) { pumping = true; scope.launch { pump() } }
        } catch (failure: Throwable) {
            lastError = failure.message; revoke()
            scope.launch { runCatching { close() }; result.complete(false) }
        }
        return result
    }
    private suspend fun pump() {
        try {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst(); val current = owner
                if (current == null || item.generation != generation || state != "active") { item.result.complete(false); continue }
                try {
                    requireInput(now() - item.at <= 250, "Input queue age", AxtpContract.Error.TIMEOUT)
                    val plan = planner.plan(item.sample, current.binding.geometry)
                    requireInput(native {
                        generation == item.generation && owner === current && state == "active" && executor.apply(current, plan)
                    }, "Native submission unconfirmed", AxtpContract.Error.UNAVAILABLE)
                    val active = generation == item.generation && owner === current && state == "active"
                    if (active) { submitted = item.candidate; submittedSamples++; changed() }
                    item.result.complete(active)
                } catch (failure: Throwable) {
                    lastError = failure.message; revoke(); runCatching { close() }; item.result.complete(false)
                }
            }
        } finally { pumping = false }
    }
    suspend fun close(): JSONObject {
        val retiring = owner ?: return JSONObject().put("streamId", 0).put("closed", true).put("alreadyClosed", true).put("revision", generation.toString())
        if (state !in setOf("closing", "cleanup_blocked")) { generation++; state = "closing"; queue.forEach { it.result.complete(false) }; queue.clear(); changed() }
        var actual = cleanupActual
        if (actual == null) {
            state = "closing"
            actual = scope.async(start = CoroutineStart.LAZY) {
                val released = try { nativeMutex.withLock { executor.release(retiring) } } catch (failure: Throwable) { lastError = failure.message; false }
                if (owner === retiring) {
                    if (released) {
                        closed["${retiring.binding.sessionId}:${retiring.streamId}"] = retiring.binding
                        while (closed.size > 4096) closed.remove(closed.keys.first())
                        owner = null; state = "closed"; limits = null; ingress = InputState(); submitted = InputState()
                    } else { state = "cleanup_blocked"; lastError = "Native cleanup unconfirmed" }
                    changed()
                }
                released
            }
            cleanupActual = actual
            val pending = actual
            actual.invokeOnCompletion { if (cleanupActual === pending) cleanupActual = null }
            actual.start()
        }
        val confirmed = try { withTimeout(cleanupTimeoutMs) { actual.await() } } catch (_: TimeoutCancellationException) { false }
        if (!confirmed) {
            if (owner === retiring) state = "cleanup_blocked"
            changed()
            throw ProtocolFailure(AxtpContract.Error.UNAVAILABLE, "cleanupPending", JSONObject().put("cleanupPending", true))
        }
        return JSONObject().put("streamId", retiring.streamId).put("closed", true).put("alreadyClosed", false).put("revision", generation.toString())
    }
    suspend fun closeStream(params: JSONObject, sid: String): JSONObject {
        val id = params.uint("streamId", 1)
        val expected = owner?.takeIf { it.streamId == id && it.binding.sessionId == sid }?.binding ?: closed["$sid:$id"]
            ?: throw ProtocolFailure(AxtpContract.Error.NOT_FOUND, "Unknown input stream")
        identity(params, sid, expected)
        requireInput(params.uint("controlVersion", 1, 1) == 1L && params.opt("streamKind") == "touchback" && isText(params.opt("commandId")), "Close request")
        if (closed.containsKey("$sid:$id")) return JSONObject().put("streamId", id).put("closed", true).put("alreadyClosed", true).put("revision", generation.toString())
        return close()
    }
    fun streamState(id: Long?, sid: String): JSONObject {
        if (id == null) return JSONObject().put("state", "aggregate")
        val current = owner
        if (current?.streamId == id && current.binding.sessionId == sid) {
            val result = JSONObject().put("streamId", id).put("state", if (state == "active") "streaming" else if (state == "opening") "opening" else "closing")
                .put("lastSeqId", submitted.seqId).put("reason", "unknown")
            if (submitted.cursor <= 9007199254740991u) result.put("lastCursor", submitted.cursor.toLong())
            return result
        }
        requireInput(closed.containsKey("$sid:$id"), "Unknown stream", AxtpContract.Error.STREAM_NOT_FOUND)
        return JSONObject().put("streamId", id).put("state", "closed").put("reason", "unknown")
    }
}
