# Diet — calorie tracker (Android)

Personal calorie tracker. No server; AI via the user's own OpenAI key (BYOK); optional Dropbox sync.

## Platform
- Android 8+ (minSdk 26; activity calories need Health Connect: Android 9+ with Google Play), compileSdk 36 / targetSdk 35, Kotlin + Jetpack Compose (Material 3), Room, DataStore, WorkManager, OkHttp.
- Works offline; only AI chat and Dropbox sync need network.

## Screens

### Main (today)
- Title: current date (re-evaluated on resume, so it rolls over at midnight).
- Gauge: consumed / limit kcal, "N left" or "N over". Arc turns error-colored when over the limit.
  Limit = goal + today's active kcal (when activity calories are on), shown as "2000 + 350 activity".
- Buttons: **Add new** (AI chat) / **Add existing** (saved meals).
- Macros: fat / protein / carbs in grams for the day.
- List of today's entries (title, grams, kcal). Delete with confirm dialog, no undo. Gauge and macros update.
- Hamburger menu: History, Goals, My meals, Settings.
- No editing of past days (v1).

### Add existing
- Search input with live filter over saved meals (required: a meal must be selected).
- Below the input: hot list (most used, then most recently used) when the query is empty, matches otherwise. Same ordering for matches.
- Weight (default = meal's default weight) and **count** (default 1). Logged weight = weight × count; title gets `×N` when count ≠ 1.
- Live total kcal. **Add** logs the entry and returns to main.

### Add new (AI chat)
- Chat with OpenAI: text and photos (camera or gallery). Photos are downscaled to ≤1024 px JPEG before sending.
- System prompt + structured output (JSON schema) on every turn; the whole conversation is resent each turn.
- Top pane (after the first answer): title, weight, kcal/100 g (editable); total kcal (derived = weight × kcal/100 g / 100);
  F/P/C per 100 g (read-only AI guesses).
- Editing kcal/100 g keeps weight and recalculates total kcal. F/P/C are never rescaled.
- Manual edits are passed back to the model on the next turn so it keeps them.
- **Add**: one-time log entry. **Add & save**: log entry + new saved meal (default weight = current weight).

### History
- Scrollable month calendar (newest month first, back to the first logged month); each day shows total kcal,
  colored under/over the current goal plus that day's stored active kcal. Days are not openable (v1).

### Goals
- Daily kcal target (default 2000).

### My meals
- List of saved meals; edit title / default weight / kcal per 100 g; delete.

### Settings
- Activity calories: switch "Add activity calories to the daily goal"; Health Connect status (unavailable / install /
  Allow access / granted), Test (today's active kcal), Open Health Connect. Reads `ActiveCaloriesBurnedRecord`
  (active only, not BMR) via aggregate, for today and the 2 previous days, each time the main screen resumes
  (foreground only). Huawei Health → Health Sync app → Health Connect. `PrivacyActivity` is the required rationale screen.
- AI provider picker: OpenAI (`https://api.openai.com/v1`) / OpenRouter (`https://openrouter.ai/api/v1`) / Custom (base URL field).
  One API key per provider (Keystore-encrypted, local only, never synced, excluded from backup) and one selected model per provider.
- Model picker: editable dropdown filled from the provider's `GET /models`, newest first, filtered to models usable for meals
  (OpenRouter: image input + structured outputs; all: no embedding/audio/image-gen/search/`:batch`). Any id can still be typed.
  Default model: `gpt-6-luna` / `openai/gpt-6-luna`.
- **Test AI** runs a real estimate ("1 medium apple") with the current settings, checking key, URL, model and JSON-schema support.
- Dropbox: built-in app key (`dropbox.appKey` in `local.properties` or `DROPBOX_APP_KEY` env at build time; public, PKCE needs
  no secret), so users just press Connect: PKCE OAuth with redirect `dev.rubybox.diet://dropbox-auth` (+ `state` check) brings
  the browser back to the app. If the build has no key, the user enters their own app key and uses "paste the code" OAuth (no redirect/server needed), Sync now, last sync status, disconnect.
  **Test connection** refreshes the token, reads both sync files, writes and deletes a probe file.

## Data
- `SavedMeal`: UUID id, title, default weight, kcal/fat/carbs/protein per 100 g, `updatedAt`, `deleted` (tombstone),
  local-only usage stats (count, last used) for ordering.
- `DayActivity`: date, active kcal (rounded), updatedAt. Stored per day so past limits stay correct.
- `LogEntry`: self-contained snapshot — date, title, weight, kcal/fat/carbs/protein totals, createdAt. No reference to saved meals.

## Dropbox sync (app folder)
- `meals.yaml` — saved meals incl. tombstones. Merge by id, newer `updated_at` wins.
- `days.yaml` — log entries grouped by date. Merge: any day with local entries overwrites the remote day;
  remote days absent locally are imported. `activity_kcal` (date → kcal) in the same file, same rule.
- Runs on app start, ~15 s after any change (WorkManager, network required), and on "Sync now".
- The OpenAI key is never written to Dropbox.

## Build
```
# local.properties: dropbox.appKey=<key from dropbox.com/developers/apps> (optional)
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (debug-signed, for sideloading)
```
