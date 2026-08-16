# Assistant Service

## VoiceInteractionService
- Register as the system assistant via `VoiceInteractionService` + `VoiceInteractionSessionService`
- Manifest declarations for assistant role (see manifest registration example below)
- User can set Marmalade as default assistant in Android settings (Settings → Apps → Default apps → Digital assistant)
- Handles long-press home button, "Hey Marmalade" wake word, and assistant intents from other apps

### Manifest Registration
```xml
<service android:name=".assistant.MarmaladeInteractionService"
         android:permission="android.permission.BIND_VOICE_INTERACTION">
    <meta-data android:name="android.voice_interaction"
               android:resource="@xml/interaction_service" />
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
</service>

<service android:name=".assistant.MarmaladeInteractionSessionService"
         android:permission="android.permission.BIND_VOICE_INTERACTION">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionSessionService" />
    </intent-filter>
</service>
```

## Required Permissions

The following permissions must be declared in `AndroidManifest.xml`:

```xml
<!-- Voice & Audio -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<!-- Node Invoke Handlers -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- System -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```

## Foreground Service
- Persistent notification while wake word detection or conversation mode is active
- Notification channel: "Marmalade Assistant" with low importance (no sound/vibration)
- Notification content: current state ("Listening for wake word", "In conversation", "Connected to N gateways")
- Wake lock: `PARTIAL_WAKE_LOCK` to keep CPU alive for audio processing
- Lifecycle:
  - Start on boot (if user has enabled wake word in settings) via `BootReceiver`
  - Start on user action (opening the app, tapping "start listening")
  - Stop when user explicitly disables wake word or kills the app

## Intent Routing

### Flow
1. User says "Hey Marmalade, open YouTube"
2. Sherpa STT transcribes to text
3. Text sent to gateway LLM via `chat.send` (or a dedicated `assistant.interpret` RPC if available)
4. LLM returns structured action response (JSON)
5. App parses action, checks tier, executes or prompts for confirmation

### Action Response Schema
```json
{
  "action": "app.launch",
  "tier": 2,
  "package": "com.google.android.youtube",
  "params": {},
  "displayText": "Opening YouTube"
}
```

```json
{
  "action": "app.search",
  "tier": 2,
  "package": "com.spotify.music",
  "params": { "query": "cats" },
  "displayText": "Searching for cats on Spotify"
}
```

```json
{
  "action": "device.timer",
  "tier": 2,
  "params": { "duration_seconds": 300 },
  "displayText": "Setting a timer for 5 minutes"
}
```

### Action Dispatch
- `app.launch` → `Intent(Intent.ACTION_MAIN)` with package, or `packageManager.getLaunchIntentForPackage()`
- `app.search` → app-specific deep link or `Intent(Intent.ACTION_SEARCH)` with query extra
- `device.timer` → `Intent(AlarmClock.ACTION_SET_TIMER)` with duration extras
- `device.alarm` → `Intent(AlarmClock.ACTION_SET_ALARM)` with time extras
- `web.search` → `Intent(Intent.ACTION_WEB_SEARCH)` with query
- `text.answer` → speak the response via TTS (no intent needed)

## Tiered Action Execution
- **Tier 1 (safe):** answer questions, read notifications — execute immediately, speak response
- **Tier 2 (app control):** open apps, play music, set timers — execute with brief toast ("Opening YouTube...")
- **Tier 3 (sensitive):** send messages, make calls, share location — show confirmation dialog, wait for user approval
- **Tier 4 (blocked):** uninstall apps, factory reset, financial transactions — always refuse, speak refusal

### Tier Validation
- The LLM assigns a tier in its response
- The app independently validates: if the action matches a Tier 4 pattern, override to Tier 4 regardless of what the LLM said
- Unknown actions default to Tier 3 (require confirmation)
- Tier overrides are defined in a local allowlist/blocklist config (not hardcoded, updatable)
