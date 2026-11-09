# Diet Tracker

Diet Tracker is an Android calorie tracker. It has no server. AI meal estimates use your own API key.

## Features

- Daily gauge with consumed and remaining kcal, plus fat, protein and carbs.
- AI meal estimates from text and photos. The app works with OpenAI, OpenRouter or any OpenAI-compatible API. You pick the model from the provider's model list.
- Saved meals with search, default weights and a count field.
- History calendar with kcal per day.
- Dropbox sync of saved meals and day logs as YAML files in the app's own Dropbox folder.
- Health Connect integration. Active calories from Health Connect raise the daily limit.

## Privacy

- The app stores all data on the device.
- The app encrypts API keys with the Android Keystore. Keys never go to Dropbox or to Android backup.
- Dropbox sync is optional. The app only accesses its own app folder.
- The app reads active, total and basal calories from Health Connect. Only active calories raise the limit. Total and basal calories appear only in the Settings test breakdown. The app never writes to Health Connect.

## Requirements

- Android 8.0 or later. Activity calories need Android 9 or later with Google Play services.
- To build: JDK 17 or later and the Android SDK with platform 36 and build-tools 35.

## Build

1. Install the Android SDK. With Homebrew on macOS:

   ```bash
   brew install --cask android-commandlinetools
   sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
   ```

2. Create `local.properties` in the project root:

   ```properties
   sdk.dir=/path/to/android/sdk
   dropbox.appKey=<your Dropbox app key>
   ```

   `dropbox.appKey` is optional. You can set the `DROPBOX_APP_KEY` environment variable instead. When the key is absent, the app asks each user for their own Dropbox app key.

3. Build the APK:

   ```bash
   ./gradlew assembleRelease
   ```

   The APK is at `app/build/outputs/apk/release/app-release.apk`. The release build uses the debug signing key, so you can sideload it.

4. Install the APK on a connected device:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

## Dropbox app setup

Create a Dropbox app for your build at https://www.dropbox.com/developers/apps:

1. Choose "Scoped access" and "App folder".
2. On the Permissions tab, enable `files.content.read` and `files.content.write`. Press Submit.
3. On the Settings tab, add the redirect URI `dev.rubybox.diet://dropbox-auth`.
4. On the Settings tab, set "Allow public clients" to on.
5. Copy the App key into `local.properties` as `dropbox.appKey`.

The app uses OAuth with PKCE. PKCE does not need the app secret, so do not put the app secret anywhere in the project.

## Activity calories from Huawei Health

Huawei Health does not write to Health Connect. Use the Health Sync app to copy the data:

1. Install Health Sync from Google Play.
2. In Health Sync, select Huawei Health as the source and Health Connect as the destination. Enable calories.
3. In Diet Tracker, open Settings, turn on "Add activity calories to the daily goal" and allow access.

## Documentation

[SPEC.md](SPEC.md) describes the screens, the data model and the sync rules.

## License

Apache License 2.0. See [LICENSE](LICENSE).
