package app.marmalade.android.rpc

/**
 * Result of dispatching one node-side tool/action invocation.
 *
 * The 18 Android `*Handler` files (Camera / Location / Contacts / Sms /
 * Wifi / etc.) build one of these for every action they perform;
 * `MarmaladeInvokeDispatcher` consumes them and maps them onto the
 * gateway's tool-call protocol.
 *
 * - [ok] - factory for a successful invocation with a JSON-string payload.
 *   Most handlers build a small JSON string inline (`{"success":true}`,
 *   `{"apps":[...]}`).
 * - [error] - factory for a failure with a short symbolic [code]
 *   (`INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED`, …) plus a
 *   human-readable [message] that surfaces to the assistant.
 */
data class InvokeResult(
    val ok: Boolean,
    val payloadJson: String?,
    val error: InvokeError?,
) {
    companion object {
        fun ok(payloadJson: String): InvokeResult =
            InvokeResult(ok = true, payloadJson = payloadJson, error = null)

        fun error(code: String, message: String): InvokeResult =
            InvokeResult(ok = false, payloadJson = null, error = InvokeError(code, message))
    }
}

/** Symbolic error code + human-readable message paired on a failed [InvokeResult]. */
data class InvokeError(val code: String, val message: String)
