package app.marmalade.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager as AndroidSmsManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import app.marmalade.android.PermissionRequester

/**
 * Sends and reads SMS messages via the Android SMS API and Telephony provider.
 * Requires SEND_SMS and READ_SMS permissions to be granted.
 */
class SmsManager(private val context: Context) {

    private val json = JsonConfig
    @Volatile private var permissionRequester: PermissionRequester? = null

    data class SendResult(
        val ok: Boolean,
        val to: String,
        val message: String?,
        val error: String? = null,
        val payloadJson: String,
    )

    data class ReadResult(
        val ok: Boolean,
        val messages: List<SmsMessageData> = emptyList(),
        val error: String? = null,
        val payloadJson: String
    )

    data class SmsMessageData(
        val address: String,
        val body: String,
        val date: Long,
        val isRead: Boolean
    )

    internal data class ParsedParams(
        val to: String,
        val message: String,
    )

    internal sealed class ParseResult {
        data class Ok(val params: ParsedParams) : ParseResult()
        data class Error(
            val error: String,
            val to: String = "",
            val message: String? = null,
        ) : ParseResult()
    }

    internal data class SendPlan(
        val parts: List<String>,
        val useMultipart: Boolean,
    )

    companion object {
        internal val JsonConfig = Json { ignoreUnknownKeys = true }

        internal fun parseParams(paramsJson: String?, json: Json = JsonConfig): ParseResult {
            val params = paramsJson?.trim().orEmpty()
            if (params.isEmpty()) {
                return ParseResult.Error(error = "INVALID_REQUEST: paramsJSON required")
            }

            val obj = try {
                json.parseToJsonElement(params).jsonObject
            } catch (_: Throwable) {
                null
            }

            if (obj == null) {
                return ParseResult.Error(error = "INVALID_REQUEST: expected JSON object")
            }

            val to = (obj["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val message = (obj["message"] as? JsonPrimitive)?.content.orEmpty()

            if (to.isEmpty()) {
                return ParseResult.Error(
                    error = "INVALID_REQUEST: 'to' phone number required",
                    message = message,
                )
            }

            if (message.isEmpty()) {
                return ParseResult.Error(
                    error = "INVALID_REQUEST: 'message' text required",
                    to = to,
                )
            }

            return ParseResult.Ok(ParsedParams(to = to, message = message))
        }

        internal fun buildSendPlan(
            message: String,
            divider: (String) -> List<String>,
        ): SendPlan {
            val parts = divider(message).ifEmpty { listOf(message) }
            return SendPlan(parts = parts, useMultipart = parts.size > 1)
        }

        internal fun buildPayloadJson(
            json: Json = JsonConfig,
            ok: Boolean,
            to: String,
            error: String?,
        ): String {
            val payload =
                mutableMapOf<String, JsonElement>(
                    "ok" to JsonPrimitive(ok),
                    "to" to JsonPrimitive(to),
                )
            if (!ok) {
                payload["error"] = JsonPrimitive(error ?: "SMS_SEND_FAILED")
            }
            return json.encodeToString(JsonObject.serializer(), JsonObject(payload))
        }
    }

    fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canSendSms(): Boolean {
        return hasSmsPermission() && hasTelephonyFeature()
    }

    fun hasTelephonyFeature(): Boolean {
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) == true
    }

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    /**
     * Send an SMS message.
     *
     * @param paramsJson JSON with "to" (phone number) and "message" (text) fields
     * @return SendResult indicating success or failure
     */
    suspend fun send(paramsJson: String?): SendResult {
        if (!hasTelephonyFeature()) {
            return errorResult(
                error = "SMS_UNAVAILABLE: telephony not available",
            )
        }

        if (!ensureSmsPermission()) {
            return errorResult(
                error = "SMS_PERMISSION_REQUIRED: grant SMS permission",
            )
        }

        val parseResult = parseParams(paramsJson, json)
        if (parseResult is ParseResult.Error) {
            return errorResult(
                error = parseResult.error,
                to = parseResult.to,
                message = parseResult.message,
            )
        }
        val params = (parseResult as ParseResult.Ok).params

        return try {
            val smsManager = context.getSystemService(AndroidSmsManager::class.java)
                ?: throw IllegalStateException("SMS_UNAVAILABLE: SmsManager not available")

            val plan = buildSendPlan(params.message) { smsManager.divideMessage(it) }
            if (plan.useMultipart) {
                smsManager.sendMultipartTextMessage(
                    params.to,     // destination
                    null,          // service center (null = default)
                    ArrayList(plan.parts),    // message parts
                    null,          // sent intents
                    null,          // delivery intents
                )
            } else {
                smsManager.sendTextMessage(
                    params.to,     // destination
                    null,          // service center (null = default)
                    params.message,// message
                    null,          // sent intent
                    null,          // delivery intent
                )
            }

            okResult(to = params.to, message = params.message)
        } catch (e: SecurityException) {
            errorResult(
                error = "SMS_PERMISSION_REQUIRED: ${e.message}",
                to = params.to,
                message = params.message,
            )
        } catch (e: Throwable) {
            errorResult(
                error = "SMS_SEND_FAILED: ${e.message ?: "unknown error"}",
                to = params.to,
                message = params.message,
            )
        }
    }

    private suspend fun ensureSmsPermission(): Boolean {
        if (hasSmsPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.SEND_SMS))
        return results[Manifest.permission.SEND_SMS] == true
    }

    private suspend fun ensureReadSmsPermission(): Boolean {
        if (hasReadSmsPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.READ_SMS))
        return results[Manifest.permission.READ_SMS] == true
    }

    suspend fun readLatest(limit: Int = 3): ReadResult {
        return readMessages(limit = limit, unreadOnly = false)
    }

    suspend fun readUnread(limit: Int = 10): ReadResult {
        return readMessages(limit = limit, unreadOnly = true)
    }

    private suspend fun readMessages(limit: Int, unreadOnly: Boolean): ReadResult {
        if (!hasTelephonyFeature()) {
            return errorReadResult("SMS_UNAVAILABLE: telephony not available")
        }
        if (!ensureReadSmsPermission()) {
            return errorReadResult("SMS_PERMISSION_REQUIRED: grant READ_SMS permission")
        }

        try {
            val messages = mutableListOf<SmsMessageData>()
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )
            val selection = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
            val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

                while (cursor.moveToNext()) {
                    messages.add(
                        SmsMessageData(
                            address = cursor.getString(addressIdx) ?: "",
                            body = cursor.getString(bodyIdx) ?: "",
                            date = cursor.getLong(dateIdx),
                            isRead = cursor.getInt(readIdx) == 1
                        )
                    )
                }
            }

            val messagesJson = messages.joinToString(",") { msg ->
                val escapedBody = msg.body.replace("\"", "\\\"").replace("\n", "\\n")
                val escapedAddress = msg.address.replace("\"", "\\\"").replace("\n", "\\n")
                """{"address":"$escapedAddress","body":"$escapedBody","date":${msg.date},"isRead":${msg.isRead}}"""
            }
            val payload = """{"messages":[$messagesJson]}"""
            return ReadResult(ok = true, messages = messages, payloadJson = payload)
        } catch (e: SecurityException) {
            return errorReadResult("SMS_PERMISSION_REQUIRED: ${e.message}")
        } catch (e: Exception) {
            return errorReadResult("SMS_READ_FAILED: ${e.message ?: "unknown error"}")
        }
    }

    private fun okResult(to: String, message: String): SendResult {
        return SendResult(
            ok = true,
            to = to,
            message = message,
            error = null,
            payloadJson = buildPayloadJson(json = json, ok = true, to = to, error = null),
        )
    }

    private fun errorResult(error: String, to: String = "", message: String? = null): SendResult {
        return SendResult(
            ok = false,
            to = to,
            message = message,
            error = error,
            payloadJson = buildPayloadJson(json = json, ok = false, to = to, error = error),
        )
    }

    private fun errorReadResult(error: String): ReadResult {
        val payload = """{"error":"$error"}"""
        return ReadResult(ok = false, error = error, payloadJson = payload)
    }
}
