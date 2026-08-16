package app.marmalade.android.node

import app.marmalade.android.rpc.InvokeResult

class ScreenHandler(
  private val screenRecorder: ScreenRecordManager,
  private val setScreenRecordActive: (Boolean) -> Unit,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  suspend fun handleScreenRecord(paramsJson: String?): InvokeResult {
    setScreenRecordActive(true)
    try {
      val res =
        try {
          screenRecorder.record(paramsJson)
        } catch (err: Throwable) {
          val (code, message) = invokeErrorFromThrowable(err)
          return InvokeResult.error(code = code, message = message)
        }
      return InvokeResult.ok(res.payloadJson)
    } finally {
      setScreenRecordActive(false)
    }
  }
}
