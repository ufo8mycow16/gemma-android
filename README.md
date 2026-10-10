# Gemma for Android

A native client for the Gemma server that runs on Vision. The phone is a thin
remote interface: it shows the conversation, sends what you type, and plays
back what the server produces. Gemma herself (model, memory, research, tools,
voice) stays on Vision. The app talks only to Gemma's application server,
never to Ollama, Chatterbox or the database.

## Layout

- `core/`  Plain Kotlin, no Android. The wire protocol (API v1), networking,
  the conversation state machine, turn recovery, speech ordering.
  Tested on any computer, including against a real Gemma server.
- `app/`   The Android app: Jetpack Compose screens, secure storage of the
  access key, lifecycle. It contains no Gemma logic.

## Build the APK

Needs a JDK (17 or newer) and the Android SDK (Android Studio installs both).

    ./gradlew :app:assembleDebug

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
In Android Studio: open this folder, then Build > Build App Bundle(s) / APK(s) > Build APK(s).

## Tests

    ./gradlew :core:test

To also run the client against a real Gemma server (it sends real test turns
to the primary conversation):

    GEMMA_TEST_URL=http://127.0.0.1:7860 GEMMA_TEST_KEY=<access key> \
    GEMMA_TEST_LONG="Tell me a long story about the Enterprise." ./gradlew :core:test

## On Vision

    GEMMA_HOST=0.0.0.0 python app.py        # let the phone reach it on the home network
    python app.py --add-client phone        # prints an access key for the phone (shown once)
    python app.py --revoke-client phone     # if the phone is lost

In the app: Settings > Gemma server address `http://<Vision's address>:7860`, paste the access key, Test connection, Save.
