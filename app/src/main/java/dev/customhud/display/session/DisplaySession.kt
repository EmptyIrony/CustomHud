package dev.customhud.display.session

import java.util.concurrent.CopyOnWriteArrayList

class DisplaySession(
    private val platform: SessionPlatform,
    private val scheduler: SessionScheduler,
    private val mainThread: () -> Boolean = { true },
    private val event: (category: String, message: String, errorCode: String?, generation: Long) -> Unit = { _, _, _, _ -> },
) {
    private val observers = CopyOnWriteArrayList<(SessionSnapshot) -> Unit>()
    private val startOperations = mutableMapOf<String, StartRequest>()
    private val stopOperations = mutableMapOf<String, OperationResult>()
    private var config: SessionConfig? = null
    private var listenerHandle: DisplayListenerHandle? = null
    private var window: SessionWindow? = null
    private var dataHandle: DataHandle? = null
    private var attachTimeout: Cancellable? = null
    private var retryTask: Cancellable? = null
    private var stableTask: Cancellable? = null
    private var displayDebounce: Cancellable? = null
    private var cleanupTimeout: Cancellable? = null
    private var pendingStart: StartRequest? = null
    private var attachReturned = false
    private var attachCallback = false
    /** remove() returned while the owned view was still attached; its detach callback finishes the cleanup. */
    private var awaitingDetach = false
    /** A rebuild was requested while the previous window was still detaching. */
    private var prepareAfterDetach = false
    private var stopReason = "user"
    private var recoveryTargetKey: String? = null
    private var recoveryEpisode = 0L

    var snapshot = SessionSnapshot()
        private set

    fun observe(observer: (SessionSnapshot) -> Unit): () -> Unit {
        observers.add(observer)
        observer(snapshot)
        return { observers.remove(observer) }
    }

    fun start(request: StartRequest): OperationResult {
        checkMain()
        startOperations[request.operationId]?.let { previous ->
            return if (previous == request) {
                result(true, request.operationId)
            } else {
                reject("CONFIG_CONFLICT", request.operationId, "operationId reused with different request", false)
            }
        }
        startOperations[request.operationId] = request
        val state = snapshot.state
        if (state == SessionState.STOPPING) {
            pendingStart = request
            return result(true, request.operationId)
        }
        if (state == SessionState.FAILED && (window != null || dataHandle != null)) {
            // lifecycle §5.3: finish the old cleanup before preparing; never create a second window.
            stopReason = "start-after-failed-cleanup"
            beginStop(request.operationId)
            when (snapshot.state) {
                SessionState.STOPPING -> {
                    pendingStart = request
                    return result(true, request.operationId)
                }
                SessionState.STOPPED -> Unit // clean now; continue with a fresh start below
                else -> return reject("CLEANUP_FAILED", request.operationId, "Previous display resources could not be released", true)
            }
        } else if (state != SessionState.STOPPED && state != SessionState.FAILED) {
            if (config != request.config || !snapshot.desiredRunning) {
                return reject("CONFIG_CONFLICT", request.operationId, "Stop or reconfigure the active session", false)
            }
            // Same config: reuse the session, but an explicit start wakes a suspended or waiting one.
            when (state) {
                SessionState.SUSPENDED -> resume("start-request")
                SessionState.WAITING_DISPLAY, SessionState.RECOVERING -> onDisplayEvent()
                else -> Unit
            }
            return result(true, request.operationId)
        }
        recoveryEpisode += 1
        recoveryTargetKey = request.config.target.targetKey
        prepareNewGeneration(request.config, request.operationId, resetRetries = true)
        return result(true, request.operationId)
    }

    fun stop(request: StopRequest): OperationResult {
        checkMain()
        stopOperations[request.operationId]?.let { return it }
        if (snapshot.state == SessionState.STOPPED || snapshot.state == SessionState.STOPPING) {
            return result(true, request.operationId).also { stopOperations[request.operationId] = it }
        }
        stopReason = request.reason
        beginStop(request.operationId)
        return result(true, request.operationId).also { stopOperations[request.operationId] = it }
    }

    fun reconfigure(updated: SessionConfig, operationId: String): OperationResult {
        checkMain()
        val old = config ?: return reject("CONFIG_INVALID", operationId, "No active config", false)
        if (updated.configRevision <= old.configRevision) {
            return reject("STALE_REVISION", operationId, "Revision must strictly increase", false)
        }
        if (!snapshot.desiredRunning) return reject("CONFIG_CONFLICT", operationId, "Session is not desired running", false)
        val requiresRebuild = updated.target != old.target || updated.windowSpec != old.windowSpec
        config = updated
        publish(snapshot.copy(
            operationId = operationId,
            configRevision = updated.configRevision,
            layoutRevision = updated.layoutRevision,
            target = updated.target,
        ))
        if (requiresRebuild) {
            invalidateGeneration()
            cleanupDisplayResources()
            prepare(updated, operationId)
        } else {
            window?.refresh()
        }
        return result(true, operationId)
    }

    fun refresh(layoutRevision: Long, reason: String): OperationResult {
        checkMain()
        if (!snapshot.desiredRunning || snapshot.state in setOf(SessionState.STOPPING, SessionState.STOPPED, SessionState.SUSPENDED)) {
            return reject("CANCELLED", "refresh-$layoutRevision", "Refresh rejected: $reason", true)
        }
        publish(snapshot.copy(layoutRevision = layoutRevision))
        window?.refresh()
        return result(true, "refresh-$layoutRevision")
    }

    fun suspend(reason: String) {
        checkMain()
        if (!snapshot.desiredRunning || snapshot.state == SessionState.SUSPENDED) return
        event("SESSION", "suspend reason=$reason", null, snapshot.generation)
        prepareAfterDetach = false
        invalidateGeneration()
        cleanupDisplayResources()
        transition(SessionState.SUSPENDED)
    }

    fun resume(reason: String) {
        checkMain()
        val current = config ?: return
        if (!snapshot.desiredRunning || snapshot.state != SessionState.SUSPENDED) return
        event("SESSION", "resume reason=$reason", null, snapshot.generation)
        invalidateGeneration()
        prepare(current, snapshot.operationId)
    }

    fun reportObservation(observation: UserObservation, generation: Long) {
        checkMain()
        if (generation != snapshot.generation) {
            publish(snapshot.copy(staleCallbackCount = snapshot.staleCallbackCount + 1))
            return
        }
        publish(snapshot.copy(userObservation = observation))
        event("OBSERVATION", observation.name, null, generation)
    }

    fun onDisplayEvent() {
        checkMain()
        displayDebounce?.cancel()
        val generation = snapshot.generation
        displayDebounce = scheduler.postDelayed(config?.recoveryPolicy?.displayChangeDebounceMs ?: 100) {
            if (generation != snapshot.generation) {
                staleCallback()
            } else {
                reconcileDisplay()
            }
        }
    }

    private fun prepareNewGeneration(config: SessionConfig, operationId: String?, resetRetries: Boolean) {
        this.config = config
        invalidateGeneration()
        if (resetRetries) {
            publish(snapshot.copy(retryCount = 0, nextRetryAtMonotonic = null))
        }
        prepare(config, operationId)
    }

    private fun prepare(config: SessionConfig, operationId: String?) {
        if (window != null && awaitingDetach && dataHandle == null) {
            // The previous view is still detaching (removeView is asynchronous). Rebuild once it is gone.
            prepareAfterDetach = true
            publish(snapshot.copy(desiredRunning = true, operationId = operationId))
            transition(SessionState.RECOVERING)
            event("SESSION", "rebuild deferred until previous window detaches", null, snapshot.generation)
            return
        }
        if (window != null || dataHandle != null) {
            val error = SessionError("CLEANUP_FAILED", snapshot.state, "Previous display resources are still owned", true, operationId, snapshot.generation, config.target)
            publish(snapshot.copy(desiredRunning = true, operationId = operationId, lastError = error, state = SessionState.FAILED))
            updateResourceCounts()
            event("SESSION", "rebuild blocked by pending cleanup", "CLEANUP_FAILED", snapshot.generation)
            return
        }
        publish(snapshot.copy(
            desiredRunning = true,
            operationId = operationId,
            configRevision = config.configRevision,
            layoutRevision = config.layoutRevision,
            target = config.target,
            lastError = null,
            addViewReturned = false,
            viewAttached = false,
            firstDrawAtMonotonic = null,
            userObservation = UserObservation.UNKNOWN,
            pendingCleanupCount = 0,
        ))
        transition(SessionState.PREPARING)
        ensureDisplayListener()
        val display = resolveTarget() ?: run {
            transition(SessionState.WAITING_DISPLAY)
            event("DISPLAY", "target waiting ${config.target.targetKey}", "TARGET_NOT_FOUND", snapshot.generation)
            return
        }
        publish(snapshot.copy(display = display))
        if (!platform.overlayGranted()) {
            unrecoverableFailure("PERMISSION_DENIED", "Overlay permission or policy denied", null)
            return
        }
        attach(display)
    }

    private fun attach(display: DisplayDescriptor) {
        val currentConfig = config ?: return
        transition(if (snapshot.retryCount == 0) SessionState.ATTACHING else SessionState.RECOVERING)
        val generation = snapshot.generation
        attachReturned = false
        attachCallback = false
        var owned: SessionWindow? = null
        val callbacks = object : WindowCallbacks {
            override fun onAttach() {
                checkMain()
                if (generation != snapshot.generation) return staleCallback()
                attachCallback = true
                event("WINDOW", "viewAttached", null, generation)
                maybeAttached()
            }

            override fun onDetach() {
                checkMain()
                // Cleanup of an owned window must finish even after its generation became stale
                // (lifecycle §6: generation only masks business callbacks, never resource cleanup).
                if (awaitingDetach && owned != null && window === owned) {
                    event("WINDOW", "viewDetached after remove", null, generation)
                    clearDetachedWindow()
                    return
                }
                if (generation != snapshot.generation) return staleCallback()
                event("WINDOW", "viewDetached", null, generation)
                if (snapshot.desiredRunning && snapshot.state in setOf(SessionState.ATTACHED, SessionState.ATTACHING, SessionState.RECOVERING)) {
                    handleAttachFailure("ATTACH_FAILED", "View detached unexpectedly", null, true)
                }
            }

            override fun onDraw(layoutRevision: Long, frameGeneration: Long) {
                checkMain()
                if (generation != snapshot.generation || layoutRevision != snapshot.layoutRevision) {
                    // Live layout editing makes superseded draws routine; count them without logging.
                    staleCallback(log = false)
                    return
                }
                val first = snapshot.firstDrawAtMonotonic == null
                publish(snapshot.copy(firstDrawAtMonotonic = snapshot.firstDrawAtMonotonic ?: scheduler.now()))
                if (first) event("RENDER", "firstOnDrawCompleted layoutRevision=$layoutRevision frame=$frameGeneration", null, generation)
            }

            override fun onError(code: String, message: String, causeType: String?) {
                checkMain()
                if (generation != snapshot.generation) return staleCallback()
                handleAttachFailure(code, message, causeType, true)
            }
        }
        try {
            window = platform.createWindow(currentConfig.target, currentConfig.windowSpec, callbacks)
            owned = window
            updateResourceCounts()
            window?.add()
            attachReturned = true
            publish(snapshot.copy(addViewReturned = true))
            event("WINDOW", "addViewReturned", null, generation)
            attachTimeout = scheduler.postDelayed(currentConfig.windowSpec.attachTimeoutMs) {
                if (generation != snapshot.generation) return@postDelayed staleCallback()
                if (!attachCallback) handleAttachFailure("ATTACH_TIMEOUT", "View attach callback timed out", null, true)
            }
            maybeAttached()
        } catch (error: Exception) {
            handleAttachFailure("ATTACH_FAILED", error.message ?: "addView failed", error::class.java.name, true)
        }
    }

    private fun maybeAttached() {
        if (!attachReturned || !attachCallback || snapshot.state == SessionState.ATTACHED) return
        attachTimeout?.cancel()
        transition(SessionState.ATTACHED)
        try {
            dataHandle = platform.startData(snapshot.generation)
            updateResourceCounts()
            event("DATA", "subscriptionStarted", null, snapshot.generation)
        } catch (error: Exception) {
            event("DATA", "subscriptionFailed type=${error::class.java.name}", "DATA_TASK_FAILED", snapshot.generation)
        }
        val generation = snapshot.generation
        stableTask?.cancel()
        stableTask = scheduler.postDelayed(config?.recoveryPolicy?.stableAttachWindowMs ?: 30_000) {
            if (generation == snapshot.generation && snapshot.state == SessionState.ATTACHED) {
                recoveryEpisode += 1
                publish(snapshot.copy(retryCount = 0, nextRetryAtMonotonic = null))
                event("RECOVERY", "stable attach; recovery budget reset", null, generation)
            }
        }
    }

    private fun handleAttachFailure(code: String, message: String, causeType: String?, recoverable: Boolean) {
        val currentConfig = config ?: return
        val generation = snapshot.generation
        val error = SessionError(code, snapshot.state, message, recoverable, snapshot.operationId, generation, currentConfig.target, causeType)
        event("WINDOW", message, code, generation)
        invalidateGeneration()
        cleanupDisplayResources()
        publish(snapshot.copy(lastError = error))
        val retries = currentConfig.recoveryPolicy.retryDelaysMs
        if (recoverable && snapshot.desiredRunning && snapshot.retryCount < retries.size && (window == null || awaitingDetach)) {
            transition(SessionState.RECOVERING)
            val delay = retries[snapshot.retryCount]
            val nextCount = snapshot.retryCount + 1
            val nextAt = scheduler.now() + delay
            publish(snapshot.copy(retryCount = nextCount, nextRetryAtMonotonic = nextAt))
            val retryGeneration = snapshot.generation
            retryTask = scheduler.postDelayed(delay) {
                if (retryGeneration != snapshot.generation || !snapshot.desiredRunning) return@postDelayed staleCallback()
                publish(snapshot.copy(nextRetryAtMonotonic = null))
                invalidateGeneration()
                prepare(currentConfig, snapshot.operationId)
            }
        } else if (recoverable && snapshot.desiredRunning) {
            transition(SessionState.FAILED)
            event("RECOVERY", "retry budget exhausted target=${currentConfig.target.targetKey}", code, snapshot.generation)
        } else {
            transition(SessionState.FAILED)
        }
    }

    private fun unrecoverableFailure(code: String, message: String, causeType: String?) {
        val error = SessionError(code, snapshot.state, message, false, snapshot.operationId, snapshot.generation, config?.target, causeType)
        publish(snapshot.copy(desiredRunning = false, lastError = error))
        event("SESSION", message, code, snapshot.generation)
        invalidateGeneration()
        cleanupDisplayResources()
        closeDisplayListener()
        transition(SessionState.FAILED)
    }

    private fun reconcileDisplay() {
        if (!snapshot.desiredRunning) return
        if (!platform.overlayGranted()) {
            unrecoverableFailure("PERMISSION_DENIED", "Overlay permission was revoked", null)
            return
        }
        val current = resolveTarget()
        when (snapshot.state) {
            SessionState.WAITING_DISPLAY, SessionState.RECOVERING -> if (current != null && (window == null || awaitingDetach) && !prepareAfterDetach) {
                invalidateGeneration()
                prepare(config ?: return, snapshot.operationId)
            }
            SessionState.ATTACHED, SessionState.ATTACHING -> {
                val old = snapshot.display
                if (current == null) {
                    event("DISPLAY", "target removed or identity changed", "TARGET_CHANGED", snapshot.generation)
                    invalidateGeneration()
                    cleanupDisplayResources()
                    publish(snapshot.copy(display = null))
                    transition(SessionState.WAITING_DISPLAY)
                } else if (old != null && geometryChanged(old, current)) {
                    event("DISPLAY", "target geometry changed; rebuilding", null, snapshot.generation)
                    invalidateGeneration()
                    cleanupDisplayResources()
                    publish(snapshot.copy(display = current))
                    prepare(config ?: return, snapshot.operationId)
                } else {
                    publish(snapshot.copy(display = current))
                }
            }
            else -> Unit
        }
    }

    private fun beginStop(operationId: String) {
        pendingStart = null
        prepareAfterDetach = false
        publish(snapshot.copy(desiredRunning = false, operationId = operationId))
        invalidateGeneration()
        transition(SessionState.STOPPING)
        event("SESSION", "stopping reason=$stopReason", null, snapshot.generation)
        cleanupDisplayResources()
        closeDisplayListener()
        finishStopIfClean()
    }

    private fun cleanupDisplayResources() {
        attachTimeout?.cancel()
        retryTask?.cancel()
        stableTask?.cancel()
        cleanupTimeout?.cancel()
        dataHandle?.let { handle ->
            try {
                handle.close()
                dataHandle = null
                event("DATA", "subscriptionClosed", null, snapshot.generation)
            } catch (error: Exception) {
                val structured = SessionError("CLEANUP_FAILED", snapshot.state, "Data subscription cleanup failed", true, snapshot.operationId, snapshot.generation, config?.target, error::class.java.name)
                publish(snapshot.copy(lastError = structured, state = SessionState.FAILED))
                event("DATA", "data cleanup failed type=${error::class.java.name}", "CLEANUP_FAILED", snapshot.generation)
            }
        }
        val oldWindow = window ?: run {
            updateResourceCounts()
            return
        }
        try {
            oldWindow.remove()
            event("WINDOW", "removeViewReturned attached=${oldWindow.isAttached}", null, snapshot.generation)
            if (window !== oldWindow) {
                // The detach callback already arrived inside remove() and cleared the owner.
            } else if (!oldWindow.isAttached) {
                clearDetachedWindow()
            } else {
                awaitingDetach = true
                publish(snapshot.copy(pendingCleanupCount = 1))
                cleanupTimeout = scheduler.postDelayed(config?.windowSpec?.attachTimeoutMs ?: 2000) {
                    // Only judge the window this timeout was armed for, never a newer one.
                    if (window !== oldWindow) return@postDelayed
                    if (oldWindow.isAttached) {
                        try {
                            oldWindow.remove() // second request escalates to an immediate removal
                        } catch (_: Exception) {
                        }
                    }
                    if (window !== oldWindow) return@postDelayed
                    if (!oldWindow.isAttached) {
                        event("WINDOW", "detach callback missing; view no longer attached", null, snapshot.generation)
                        clearDetachedWindow()
                    } else {
                        val error = SessionError("CLEANUP_TIMEOUT", snapshot.state, "View remained attached", true, snapshot.operationId, snapshot.generation, config?.target)
                        publish(snapshot.copy(lastError = error, pendingCleanupCount = 1, state = SessionState.FAILED))
                    }
                }
            }
        } catch (error: Exception) {
            val structured = SessionError("CLEANUP_FAILED", snapshot.state, error.message ?: "removeView failed", true, snapshot.operationId, snapshot.generation, config?.target, error::class.java.name)
            publish(snapshot.copy(lastError = structured, pendingCleanupCount = 1, state = SessionState.FAILED))
            event("WINDOW", "removeView failed type=${error::class.java.name}", "CLEANUP_FAILED", snapshot.generation)
        }
        updateResourceCounts()
    }

    private fun clearDetachedWindow() {
        if (window == null) return
        window = null
        awaitingDetach = false
        cleanupTimeout?.cancel()
        publish(snapshot.copy(addViewReturned = false, viewAttached = false, pendingCleanupCount = 0))
        updateResourceCounts()
        when {
            snapshot.state == SessionState.STOPPING -> finishStopIfClean()
            prepareAfterDetach && snapshot.desiredRunning && snapshot.state != SessionState.SUSPENDED -> {
                prepareAfterDetach = false
                val current = config ?: return
                invalidateGeneration()
                prepare(current, snapshot.operationId)
            }
        }
    }

    private fun closeDisplayListener() {
        val handle = listenerHandle ?: return
        try {
            handle.close()
            listenerHandle = null
        } catch (error: Exception) {
            val structured = SessionError("CLEANUP_FAILED", snapshot.state, "Display listener cleanup failed", true, snapshot.operationId, snapshot.generation, config?.target, error::class.java.name)
            publish(snapshot.copy(lastError = structured, state = SessionState.FAILED))
            event("DISPLAY", "listener cleanup failed type=${error::class.java.name}", "CLEANUP_FAILED", snapshot.generation)
        }
        updateResourceCounts()
    }

    private fun finishStopIfClean() {
        if (window != null) return
        closeDisplayListener()
        if (listenerHandle != null || dataHandle != null) return
        config = null
        publish(snapshot.copy(
            state = SessionState.STOPPED,
            desiredRunning = false,
            display = null,
            addViewReturned = false,
            viewAttached = false,
            firstDrawAtMonotonic = null,
            resourceCount = 0,
            pendingCleanupCount = 0,
            nextRetryAtMonotonic = null,
            userObservation = UserObservation.UNKNOWN,
        ))
        event("SESSION", "stopped resources=0", null, snapshot.generation)
        pendingStart?.let { next ->
            pendingStart = null
            start(next)
        }
    }

    private fun ensureDisplayListener() {
        if (listenerHandle != null) return
        listenerHandle = platform.registerDisplayListener { onDisplayEvent() }
        updateResourceCounts()
        event("DISPLAY", "listenerRegistered", null, snapshot.generation)
    }

    private fun resolveTarget(): DisplayDescriptor? {
        val target = config?.target ?: return null
        return platform.displays(snapshot.generation).firstOrNull {
            it.id == target.displayId && it.name == target.name && it.valid
        }
    }

    private fun geometryChanged(old: DisplayDescriptor, new: DisplayDescriptor): Boolean =
        old.width != new.width || old.height != new.height || old.densityDpi != new.densityDpi || old.rotation != new.rotation

    private fun invalidateGeneration() {
        publish(snapshot.copy(generation = snapshot.generation + 1))
    }

    private fun transition(state: SessionState) {
        if (snapshot.state == state) return
        val previous = snapshot.state
        publish(snapshot.copy(state = state))
        event("STATE", "$previous -> $state resources=${snapshot.resourceCount}", null, snapshot.generation)
    }

    private fun updateResourceCounts() {
        val cleaning = snapshot.state in setOf(SessionState.STOPPING, SessionState.FAILED)
        val resources = (if (listenerHandle != null) 1 else 0) + (if (window != null) 1 else 0) + (if (dataHandle != null) 1 else 0)
        val pendingWindow = if (window?.isAttached == true && cleaning) 1 else 0
        val pendingData = if (dataHandle != null && cleaning) 1 else 0
        val pendingListener = if (listenerHandle != null && cleaning && !snapshot.desiredRunning && snapshot.lastError?.code == "CLEANUP_FAILED") 1 else 0
        publish(snapshot.copy(resourceCount = resources, pendingCleanupCount = pendingWindow + pendingData + pendingListener))
    }

    private fun staleCallback(log: Boolean = true) {
        publish(snapshot.copy(staleCallbackCount = snapshot.staleCallbackCount + 1))
        if (log) event("SESSION", "stale callback discarded", null, snapshot.generation)
    }

    private fun reject(code: String, operationId: String, message: String, recoverable: Boolean): OperationResult {
        val error = SessionError(code, snapshot.state, message, recoverable, operationId, snapshot.generation, config?.target)
        publish(snapshot.copy(lastError = error))
        event("SESSION", message, code, snapshot.generation)
        return OperationResult(false, operationId, snapshot.generation, snapshot.state, error)
    }

    private fun result(accepted: Boolean, operationId: String): OperationResult =
        OperationResult(accepted, operationId, snapshot.generation, snapshot.state, snapshot.lastError)

    private fun publish(updated: SessionSnapshot) {
        snapshot = updated.copy(
            viewAttached = attachCallback && attachReturned && window?.isAttached == true && updated.state == SessionState.ATTACHED,
        )
        observers.forEach { it(snapshot) }
    }

    private fun checkMain() = check(mainThread()) { "DisplaySession must run on the main thread" }
}
