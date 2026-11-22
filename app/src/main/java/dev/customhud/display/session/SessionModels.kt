package dev.customhud.display.session

enum class SessionState {
    STOPPED, PREPARING, WAITING_DISPLAY, ATTACHING, ATTACHED,
    SUSPENDED, RECOVERING, STOPPING, FAILED,
}

enum class UserObservation { UNKNOWN, VISIBLE_CONFIRMED, NOT_VISIBLE_REPORTED }
enum class ScreenOffPolicy { SUSPEND_AND_REMOVE }

data class DisplayTarget(val displayId: Int, val name: String) {
    val targetKey: String get() = "id:$displayId|name:$name"
}

data class DisplayDescriptor(
    val id: Int,
    val name: String,
    val flags: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val valid: Boolean,
    val state: Int,
    val lastSeenMonotonic: Long,
    val sessionGeneration: Long,
)

data class WindowSpec(
    val type: Int = 2038,
    val flags: Int = 664,
    val format: Int = -3,
    val width: Int = -1,
    val height: Int = -1,
    val attachTimeoutMs: Long = 2000,
)

data class RecoveryPolicy(
    val retryDelaysMs: List<Long> = listOf(250, 1000, 3000),
    val displayChangeDebounceMs: Long = 100,
    val stableAttachWindowMs: Long = 30_000,
)

data class SessionConfig(
    val target: DisplayTarget,
    val windowSpec: WindowSpec = WindowSpec(),
    val configRevision: Long = 1,
    val layoutRevision: Long,
    val backgroundAllowed: Boolean = true,
    val screenOffPolicy: ScreenOffPolicy = ScreenOffPolicy.SUSPEND_AND_REMOVE,
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy(),
) {
    init {
        require(configRevision > 0 && layoutRevision > 0)
        require(windowSpec.attachTimeoutMs > 0)
        require(recoveryPolicy.retryDelaysMs.all { it >= 0 })
    }
}

data class SessionError(
    val code: String,
    val stage: SessionState,
    val message: String,
    val recoverable: Boolean,
    val operationId: String?,
    val generation: Long,
    val target: DisplayTarget?,
    val causeType: String? = null,
)

data class SessionSnapshot(
    val state: SessionState = SessionState.STOPPED,
    val desiredRunning: Boolean = false,
    val generation: Long = 0,
    val operationId: String? = null,
    val configRevision: Long = 0,
    val layoutRevision: Long = 0,
    val target: DisplayTarget? = null,
    val display: DisplayDescriptor? = null,
    val addViewReturned: Boolean = false,
    val viewAttached: Boolean = false,
    val firstDrawAtMonotonic: Long? = null,
    val lastError: SessionError? = null,
    val resourceCount: Int = 0,
    val pendingCleanupCount: Int = 0,
    val retryCount: Int = 0,
    val nextRetryAtMonotonic: Long? = null,
    val userObservation: UserObservation = UserObservation.UNKNOWN,
    val staleCallbackCount: Long = 0,
)

data class OperationResult(
    val accepted: Boolean,
    val operationId: String,
    val generation: Long,
    val state: SessionState,
    val error: SessionError? = null,
)

data class StartRequest(val config: SessionConfig, val operationId: String)
data class StopRequest(val reason: String, val operationId: String)
