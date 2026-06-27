# Sapphire

AI-driven smart content aggregator for Android. Local-first, anonymous, LLM-curated feeds.

## Prerequisites (on your machine)

1. **Android Studio** (Koala/Platypus or newer) — bundles a JBR (JDK 21), the Android SDK, emulator.
   The project's Kotlin/Java `jvmTarget` is 21; point `JAVA_HOME` at the Studio JBR
   (`C:\Program Files\Android\Android Studio\jbr`) if building from the command line.
2. Android SDK with `platforms;android-34` and `build-tools;34.0.0` (install via Studio's SDK Manager).
3. An **OpenAI-compatible LLM API key** (OpenAI, OpenRouter, DeepSeek, Zhipu/Moonshot, etc.).

> **Building from China:** `settings.gradle.kts` already routes Maven/Google/plugin repos
> through Aliyun mirrors, and the Gradle wrapper distribution can be pre-seeded from
> `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` into
> `~/.gradle/wrapper/dists/gradle-8.9-bin/<hash>/gradle-8.9-bin.zip` to avoid GFW throttling.

## Setup

1. **Configure the LLM.** At the repo root, create `local.properties` (gitignored):

   ```properties
   # Required
   SAPPHIRE_LLM_API_KEY=sk-...your-key...

   # Optional overrides (defaults shown)
   SAPPHIRE_LLM_BASE_URL=https://api.openai.com/v1/
   SAPPHIRE_LLM_TIER1_MODEL=gpt-4o-mini
   SAPPHIRE_LLM_TIER2_MODEL=gpt-4o
   ```

   For OpenRouter: `SAPPHIRE_LLM_BASE_URL=https://openrouter.ai/api/v1/` and models like
   `openai/gpt-4o-mini`. For DeepSeek: `https://api.deepseek.com/v1/` and `deepseek-chat`.

   `local.properties` is read at build time and injected into `BuildConfig` — the key is
   compiled into the app, so do not distribute debug builds.

2. **Set Android SDK location** (if not using Android Studio which does this automatically).
   In the same `local.properties`:

   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```

## Build & verify

```bash
# 1. Compile + lint
./gradlew assembleDebug
./gradlew lintDebug

# 2. Unit tests (pure domain logic + Room via Robolectric)
./gradlew :core-domain:test
./gradlew :core-data:test
./gradlew test          # all

# 3. Install on emulator/device and run
./gradlew installDebug
# then launch "Sapphire" from the app drawer
```

On Windows use `gradlew.bat` instead of `./gradlew`.
