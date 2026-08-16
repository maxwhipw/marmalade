package app.marmalade.android.ui.settings

import app.marmalade.android.rpc.JsonRpcClient
import app.marmalade.android.rpc.WebSocketFactory
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Shared scaffolding for the `:shared` settings-ViewModel tests.
 *
 * These ViewModels moved out of `:app` onto the multiplatform lifecycle
 * artifacts (desktop-client plan Phase 1), which is what makes them directly
 * constructible with a fake RPC — no Robolectric, no `Application`. Each test
 * subclasses [app.marmalade.android.rpc.MarmaladeRpc] and overrides only the
 * one or two methods its ViewModel calls, so they all need the same throwaway
 * client for the super constructor.
 */

/** Satisfies the `MarmaladeRpc(client)` super call; never actually used —
 *  every fake overrides the methods under test, so nothing reaches the wire. */
internal val StubJsonRpcClient: JsonRpcClient by lazy {
    JsonRpcClient(
        webSocketFactory = object : WebSocketFactory {
            override fun create(request: Request, listener: WebSocketListener): WebSocket =
                throw UnsupportedOperationException("test stub")
        },
    )
}
