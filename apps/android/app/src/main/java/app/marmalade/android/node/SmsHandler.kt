package app.marmalade.android.node

import app.marmalade.android.rpc.InvokeResult

class SmsHandler(
  private val sms: SmsManager,
) {
  suspend fun handleSmsSend(paramsJson: String?): InvokeResult {
    val res = sms.send(paramsJson)
    if (res.ok) {
      return InvokeResult.ok(res.payloadJson)
    } else {
      val error = res.error ?: "SMS_SEND_FAILED"
      val idx = error.indexOf(':')
      val code = if (idx > 0) error.substring(0, idx).trim() else "SMS_SEND_FAILED"
      return InvokeResult.error(code = code, message = error)
    }
  }

  suspend fun handleSmsReadLatest(): InvokeResult {
    val res = sms.readLatest()
    if (res.ok) {
      return InvokeResult.ok(res.payloadJson)
    } else {
      val error = res.error ?: "SMS_READ_FAILED"
      val idx = error.indexOf(':')
      val code = if (idx > 0) error.substring(0, idx).trim() else "SMS_READ_FAILED"
      return InvokeResult.error(code = code, message = error)
    }
  }

  suspend fun handleSmsReadUnread(): InvokeResult {
    val res = sms.readUnread()
    if (res.ok) {
      return InvokeResult.ok(res.payloadJson)
    } else {
      val error = res.error ?: "SMS_READ_FAILED"
      val idx = error.indexOf(':')
      val code = if (idx > 0) error.substring(0, idx).trim() else "SMS_READ_FAILED"
      return InvokeResult.error(code = code, message = error)
    }
  }
}
