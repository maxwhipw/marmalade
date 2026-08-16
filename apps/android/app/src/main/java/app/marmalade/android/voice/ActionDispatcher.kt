/**
 * Data Flow: ActionDispatcher
 *
 * Assistant message text arrives in ChatViewModel.afterResponseReceived() or
 * MarmaladeVoiceSession.handleResponseReceived().
 *
 *   responseText
 *       |
 *   parseMarmaladeAction(responseText)
 *       | (MarmaladeAction? or null)
 *   dispatchAction(context, action)
 *       |
 *   Android Intent (startActivity) or no-op for text.answer
 *       |
 *   DispatchResult (Success or Error) — returned to caller
 *
 * The assistant embeds a JSON envelope in its response text like:
 *   { "marmalade_action": { "action": "app.launch", "package": "...", "displayText": "..." } }
 *
 * [MarmaladeAction] and `parseMarmaladeAction()` — the pure half — live in
 * :shared/commonMain (voice/MarmaladeAction.kt), same package. This file keeps
 * the Android-Intent dispatch half.
 */
package app.marmalade.android.voice

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore

/**
 * Result of dispatching an action. Callers can report this back to the gateway
 * so the agent knows whether the action succeeded.
 */
sealed class DispatchResult {
    data class Success(val displayText: String) : DispatchResult()
    data class Error(val message: String) : DispatchResult()
}

/**
 * Dispatch a MarmaladeAction as an Android Intent.
 * Returns a [DispatchResult] indicating success or failure. The caller is
 * responsible for surfacing the result to the user (chat bubble, banner,
 * gateway log, etc.).
 */
fun dispatchAction(context: Context, action: MarmaladeAction): DispatchResult {
    return try {
        when (action.action) {
            "app.launch" -> dispatchAppLaunch(context, action)
            "app.search" -> dispatchAppSearch(context, action)
            "web.search" -> dispatchWebSearch(context, action)
            "device.timer" -> dispatchTimer(context, action)
            "device.alarm" -> dispatchAlarm(context, action)
            "device.call" -> dispatchCall(context, action)
            "device.sms" -> dispatchSms(context, action)
            "media.play" -> dispatchMediaPlay(context, action)
            "intent.generic" -> dispatchGenericIntent(context, action)
            "text.answer" -> {
                // No intent needed -- response is already rendered in chat and spoken via TTS
                DispatchResult.Success(action.displayText)
            }
            else -> DispatchResult.Error("Unknown action type: ${action.action}")
        }
    } catch (e: Exception) {
        DispatchResult.Error(e.message ?: "Unknown error")
    }
}

// -- Action dispatchers --

private fun dispatchAppLaunch(context: Context, action: MarmaladeAction): DispatchResult {
    val pkg = action.packageName
        ?: return DispatchResult.Error("No package specified")
    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        ?: return DispatchResult.Error("$pkg is not installed")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return DispatchResult.Success(action.displayText)
}

private fun dispatchAppSearch(context: Context, action: MarmaladeAction): DispatchResult {
    val query = action.params["query"] ?: ""
    val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
        `package` = action.packageName
        putExtra(SearchManager.QUERY, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(searchIntent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        // Fall back to web search
        val webIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
        DispatchResult.Success(action.displayText)
    }
}

private fun dispatchWebSearch(context: Context, action: MarmaladeAction): DispatchResult {
    val query = action.params["query"] ?: ""
    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
        putExtra(SearchManager.QUERY, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        DispatchResult.Error("No web browser available")
    }
}

private fun dispatchTimer(context: Context, action: MarmaladeAction): DispatchResult {
    val seconds = action.params["duration_seconds"]?.toIntOrNull()
        ?: return DispatchResult.Error("Invalid or missing duration_seconds")
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        // Phase 9 MCP-04: surface params["message"] as the Clock app's timer label.
        action.params["message"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return DispatchResult.Success(action.displayText)
}

private fun dispatchAlarm(context: Context, action: MarmaladeAction): DispatchResult {
    val hour = action.params["hour"]?.toIntOrNull()
        ?: return DispatchResult.Error("Invalid or missing hour")
    val minute = action.params["minute"]?.toIntOrNull() ?: 0
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        // Phase 9 MCP-04: surface params["message"] as the Clock app's alarm label.
        action.params["message"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return DispatchResult.Success(action.displayText)
}

/**
 * Open the dialer with number pre-filled. Uses ACTION_DIAL (not ACTION_CALL)
 * so the user presses the Call button themselves, per CONTEXT.md decision.
 */
private fun dispatchCall(context: Context, action: MarmaladeAction): DispatchResult {
    val number = action.params["number"]
        ?: return DispatchResult.Error("No phone number specified")
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:${Uri.encode(number)}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        DispatchResult.Error("No dialer app available")
    }
}

/**
 * Open the messaging app with recipient and body pre-filled.
 * Uses ACTION_SENDTO with smsto: URI -- the user sends the message manually.
 */
private fun dispatchSms(context: Context, action: MarmaladeAction): DispatchResult {
    val number = action.params["number"]
        ?: return DispatchResult.Error("No phone number specified")
    val body = action.params["body"] ?: ""
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("smsto:${Uri.encode(number)}")
        putExtra("sms_body", body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        DispatchResult.Error("No messaging app available")
    }
}

/**
 * Play media via INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH. Falls back to
 * launching the target app if the search intent is not handled.
 */
private fun dispatchMediaPlay(context: Context, action: MarmaladeAction): DispatchResult {
    val query = action.params["query"] ?: ""
    val mediaType = action.params["media_type"] ?: MediaStore.Audio.Media.ENTRY_CONTENT_TYPE

    val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaType)
        putExtra(SearchManager.QUERY, query)
        if (action.packageName != null) {
            `package` = action.packageName
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        context.startActivity(searchIntent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        // Fall back to launching the app directly if search intent not supported
        if (action.packageName != null) {
            val fallback = context.packageManager.getLaunchIntentForPackage(action.packageName!!)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                return DispatchResult.Success(action.displayText)
            }
        }
        DispatchResult.Error("No media player available for this request")
    }
}

/**
 * Construct and dispatch an arbitrary Android Intent from the generic intent
 * fields on MarmaladeAction. Enables the gateway to dispatch any intent that
 * installed apps can handle without hardcoding every action type.
 */
private fun dispatchGenericIntent(context: Context, action: MarmaladeAction): DispatchResult {
    val intentActionStr = action.intentAction
        ?: return DispatchResult.Error("intent.generic requires intentAction field")

    val intent = Intent(intentActionStr).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Set data URI if provided
        if (action.intentData != null) {
            data = Uri.parse(action.intentData)
        }

        // Set package if provided
        if (action.packageName != null) {
            `package` = action.packageName
        }

        // Add category if provided
        if (action.intentCategory != null) {
            addCategory(action.intentCategory)
        }

        // Add extras if provided
        action.intentExtras?.forEach { (key, value) ->
            putExtra(key, value)
        }
    }

    return try {
        context.startActivity(intent)
        DispatchResult.Success(action.displayText)
    } catch (e: ActivityNotFoundException) {
        DispatchResult.Error("No app can handle intent: $intentActionStr")
    }
}

