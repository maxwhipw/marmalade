package app.marmalade.android.ui

import com.journeyapps.barcodescanner.CaptureActivity

// Portrait-locked ZXing capture target for the pairing-QR scan flow —
// launched via ScanContract from ScanPairingQrButton (GatewayStep +
// ConnectionSettingsScreen). Wired 2026-07-03, closing dead-code audit
// item W1.
class PortraitCaptureActivity : CaptureActivity()
