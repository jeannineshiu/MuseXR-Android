# MuseXR

An Android app that turns Meta AI glasses into a museum guide: look at a piece of art, tap a button, and hear an AI-generated description spoken back through the glasses.

Built on the [Meta Wearables Device Access Toolkit (DAT)](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit/) for pairing and camera access.

## How it works

1. **Connect** — pairs with Meta AI glasses via the Meta AI app (Meta Wearables DAT SDK).
2. **Capture** — grabs a photo from the glasses' camera.
3. **Ask** — sends the photo (base64-encoded) and a question to a backend AI service over HTTPS.
4. **Speak** — reads the returned answer aloud using Android `TextToSpeech`.

## Project structure

```
app/src/main/java/com/hypdescape/musexr/
├── MainActivity.kt              # UI, permissions, orchestrates capture -> ask -> speak
├── glasses/GlassesManager.kt    # Wraps the DAT SDK: pairing, camera session, photo capture
└── network/LouvreApiClient.kt   # POSTs the photo + question to the backend, parses the answer
```

## Requirements

- Android Studio (Narwhal or newer) with JDK 17+ available (the bundled JBR works)
- A physical Meta AI glasses device (or Developer Mode / Mock Device Kit for testing without hardware)
- A GitHub personal access token with `read:packages` scope — the DAT SDK is distributed via GitHub Packages, not Maven Central

## Setup

1. Clone the repo and open it in Android Studio.
2. Create `local.properties` in the project root (this file is git-ignored) with:

   ```properties
   sdk.dir=/path/to/Android/sdk

   # GitHub PAT with "read:packages" scope, to fetch the Meta Wearables DAT SDK
   github_token=YOUR_GITHUB_TOKEN

   # From https://wearables.developer.meta.com (Wearables Developer Center).
   # Can be left blank while testing with "Developer Mode" enabled in the Meta AI app.
   mwdat_application_id=
   mwdat_client_token=

   # Base URL of your AI backend (exposed to the app as BuildConfig.BACKEND_BASE_URL).
   backend_base_url=https://your-backend-host
   ```

3. Point the app at your own AI backend via `backend_base_url` above. `LouvreApiClient` expects a service exposing:

   ```
   POST /ask
   Content-Type: application/json

   { "question": "Tell me about this sculpture", "image_base64": "<base64 JPEG>" }
   ```

   Response:

   ```json
   { "mode": "FULL_VOICE", "answer": "...", "exhibit": "..." }
   ```

4. Sync Gradle and run on a device with the Meta AI app installed and glasses paired.

## Permissions

The app requests Bluetooth, Bluetooth Connect, and Camera at runtime, plus Internet (normal permission) for the backend call.
