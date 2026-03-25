# Boox Reader - Gemini CLI Mandates

This document contains foundational mandates for the Boox Reader project. These instructions take absolute precedence over general workflows and tool defaults.

## 1. Technical Stack & Architecture
- **Language**: Kotlin (JVM Toolchain 21, Source/Target Compatibility 21).
- **UI Framework**: Hybrid approach.
    - Use **Jetpack Compose** for new features and complex UI components.
    - Maintain and respect existing **ViewBinding/XML** layouts for legacy activities/fragments unless a full refactor is requested.
- **Local Database**: **Room** (v2.7.0-rc01+) with KSP. Ensure migrations are handled or schemas are exported if required.
- **Remote Sync**: Supports **PocketBase** and **Supabase**. 
    - Always ensure local-first consistency: write to Room, then sync to remote.
    - Use `updated_at` (BIGINT/milliseconds) for conflict resolution.
- **EPUB Engine**: **Readium Kotlin Toolkit** (v3.1.2).
- **File Storage**: Cloudflare R2 (S3-compatible) or Supabase Storage.

## 2. E-Ink Optimization (High Priority)
Boox Reader is specifically tuned for e-ink devices (like Onyx Boox).
- **Motion**: Disable or minimize animations by default (`page_animation_enabled = false`).
- **Contrast**: Favor high-contrast themes and UI elements (black/white/grayscale).
- **Refresh Control**: Utilize Boox-specific refresh APIs if available or provide manual refresh triggers.
- **Input**: Ensure tap zones are generous and swipe sensitivity is adjustable.

## 3. Engineering Standards
- **Dependency Management**: ALL dependencies must be defined in `gradle/libs.versions.toml`. Do not hardcode versions in `build.gradle.kts`.
- **Surgical Updates**: When modifying existing code, maintain the surrounding style and patterns. Do not perform unrelated refactoring.
- **Null Safety**: Leverage Kotlin's null safety strictly. Avoid `!!` unless absolutely necessary in test code.
- **I18n**: Support `en`, `zh` (Simplified), and `zh-rTW` (Traditional). Ensure all strings are extracted to `res/values/strings.xml` and its variants. Use `opencc4j` for Chinese conversion if necessary.

## 4. Security & Credentials
- **Secrets**: NEVER hardcode API keys or secrets. Use `keystore.properties` (local) or environment variables (`STORE_FILE`, `KEYSTORE_PASSWORD`, etc.).
- **BuildConfig**: Sensitive fields in `BuildConfig` should be populated via Gradle properties or environment variables.

## 5. Tooling & Automation
- **Helper Script**: Use `./run.sh` for common development tasks.
    - `--debug`: Build and install debug APK (default).
    - `--release`: Build, increment version, commit, and install release APK.
    - `--skip-tests`: Skip unit tests during the build process.
    - `--auto-select`: Automatically select the first connected device for installation.
- **CI/CD**: GitHub Actions are used for automated releases (`.github/workflows/android-release.yml`). Manual version bumps are handled by `run.sh` or the `incrementVersionCode` Gradle task.

## 6. Testing & Validation
- **Unit Tests**: Add JUnit tests for business logic, especially for sync and progress tracking. Run them via `./gradlew test` or `./run.sh`.
- **Verification**: Always run `./gradlew :app:assembleDebug` or `./run.sh --debug` to verify build integrity after changes.
- **Linting**: Respect the custom lint rules defined in `app/build.gradle.kts`.

## 7. Git Workflow
- **Commit Messages**: Prefer Conventional Commits. `run.sh` uses AI to generate commit messages if `GROQ_API_KEY` is set.
- **Branching**: Follow the existing repository pattern (usually working on `main` for this scale).
- **Drafting**: Propose clear, concise commit messages. Do not stage or commit changes unless explicitly directed. Review `git diff` before proposing a commit.
