import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Human-friendly ABI -> versionCode offset so each split APK gets a unique code.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "universal" to 3)

// ---------------------------------------------------------------------------
// Release signing (see docs/SIGNING.md).
//
// ROOT CAUSE of "App not installed as package conflicts with an existing
// package": the old build silently fell back to the DEBUG keystore whenever no
// KEYSTORE_PATH env var was set. Every machine / clean CI runner has a
// DIFFERENT auto-generated debug key, and Android refuses to install an update
// whose signature differs from the installed APK — so users had to uninstall
// first. The fix: sign every release with ONE stable, private keystore.
//
// Credential sources, in priority order:
//   1. keystore.properties in the repo root (local builds; git-ignored).
//      Create it with:  bash scripts/generate-keystore.sh
//   2. KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//      environment variables (CI secrets).
//   3. The CI keystore persisted in the repo (.github/ci-keystore.jks.b64) —
//      the exact key CI signs with, so local builds match the published
//      signature and updates always install in place.
//
// NOTE: `Properties` / `Base64` are imported at the top of this file. Never
// write `java.util.Properties()` inline here — inside build.gradle.kts the
// `java {}` accessor shadows the `java` package and script compilation fails
// with "Unresolved reference 'util'".
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = signingValue("storeFile", "KEYSTORE_PATH")
val hasReleaseKeystore: Boolean =
    releaseStorePath != null && rootProject.file(releaseStorePath).exists()

// Source 3: the repo-persisted CI keystore. Decode it once at configuration
// time so plain local `gradle assembleRelease` produces the SAME signature as
// the APKs published by GitHub Actions.
// ---------------------------------------------------------------------------
// SECURITY FINDING (audit 1.2.7-r2) — CRITICAL, and deliberately NOT silently
// changed here, because removing this key breaks in-place updates for everyone
// who already installed a release signed with it.
//
// `.github/ci-keystore.jks.b64` is the RELEASE SIGNING KEY, committed to the
// repository, and the password below is in this file in clear text. Anyone who
// can read the repo can therefore produce an APK that Android accepts as an
// in-place UPDATE of this app — same package name, same certificate, no warning,
// full VPN privileges over every byte the victim's device sends. `.gitignore`
// excludes `*.jks`, and the `.b64` suffix is what got this past it.
//
// For a censorship-circumvention VPN this is the highest-impact issue in the
// project. It cannot be fixed by editing a file: it needs a key rotation.
// docs/SECURITY_AUDIT_1.2.7-r2.md §1.1 has the migration plan. Until that is
// done, every release-producing build prints the warning below, so it can never
// happen again without somebody being told.
// ---------------------------------------------------------------------------
val ciKeystoreB64 = rootProject.file(".github/ci-keystore.jks.b64")
val useCiKeystore: Boolean = !hasReleaseKeystore && ciKeystoreB64.exists()
val ciKeystoreFile = rootProject.file("build/ci-release.keystore")
if (useCiKeystore) {
    ciKeystoreFile.parentFile.mkdirs()
    ciKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(ciKeystoreB64.readText().trim()),
    )
    logger.warn(
        "\n" +
            "*******************************************************************\n" +
            "  WARNING: signing with the PUBLIC CI keystore committed to this\n" +
            "  repository (.github/ci-keystore.jks.b64, password in\n" +
            "  app/build.gradle.kts). Anyone can sign an APK that installs OVER\n" +
            "  this app as a legitimate update. Do not use this key for anything\n" +
            "  users install. Provide a private keystore via keystore.properties\n" +
            "  or the KEYSTORE_* environment variables.\n" +
            "  See docs/SECURITY_AUDIT_1.2.7-r2.md section 1.1.\n" +
            "*******************************************************************",
    )
}

android {
    namespace = "studio.cluvex.aether"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.cluvex.aether"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.8"

        ndk {
            // We ship arm64 (primary) and arm builds.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 1.2.2: the in-app updater (APK download + system installer handoff)
        // was REMOVED. The app no longer downloads executable code at runtime
        // from anywhere. What remains is a read-only pointer to the official,
        // signed GitHub Releases page that the About card can open in the
        // browser -- no network call, no download, no installer intent.
        val githubRepo = System.getenv("GITHUB_REPOSITORY")
            ?: (project.findProperty("githubRepo") as? String ?: "")
        val releasesUrl =
            if (githubRepo.isNotBlank()) "https://github.com/$githubRepo/releases/latest" else ""
        buildConfigField("String", "RELEASES_URL", "\"$releasesUrl\"")

        // Aether engine (core) version compiled into this build. CI keeps this
        // in sync with native/aether/CORE_VERSION via scripts/sync-core.sh.
        val coreVersion = rootProject.file("native/aether/CORE_VERSION")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "unknown" }
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")

        // ------------------------------------------------------------------
        // 1.2.8-r5 BUILD IDENTITY.
        //
        // versionName stays "1.2.8" and versionCode stays 12, as required. That
        // is exactly the problem this field solves rather than papers over: r2,
        // r3 and r4 were all "1.2.8 (12)", the engine banner printed only the
        // upstream core version (1.8.0) which is identical in all of them, and
        // so an APK from two rounds ago is indistinguishable from today's - in
        // the UI, in the log, and on the releases page.
        //
        // The r4 field log is the receipt: five separate strings in it belong to
        // the r3 build, so a whole diagnosis round analysed a binary that
        // predates the fix under test. PATCH_LEVEL is the identity that was
        // missing. It is written into the log on every connect, shown in the
        // About card, cross-checked against the stamp inside libaether.so, and
        // asserted by CI before a release is published.
        val patchLevel = rootProject.file("PATCHLEVEL")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "unstamped" }
        buildConfigField("String", "PATCH_LEVEL", "\"$patchLevel\"")
    }

    // Both native cores (libhev-socks5-tunnel.so + libaether.so) are prebuilt by
    // scripts/build-natives.sh into src/main/jniLibs, so there is NO
    // externalNativeBuild / CMake step in the Gradle build.

    signingConfigs {
        create("release") {
            // PLAY-PROTECT FIX: sign with the FULL modern scheme chain.
            // AGP leaves v3 signing OFF by default; a complete v1+v2+v3
            // signature protects the whole archive from tampering and is
            // what reputable sideloaded apps ship with.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            } else if (useCiKeystore) {
                storeFile = ciKeystoreFile
                storePassword = "aether-ci-keystore"
                keyAlias = "aether-ci"
                keyPassword = "aether-ci-keystore"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // PLAY-PROTECT FIX (root cause of the Google Play Protect
            // "App blocked to protect your device" / "hasn't seen an app
            // from this developer before" install warning): the old build
            // silently fell back to the DEBUG key here. Debug certificates
            // are auto-generated and DIFFERENT on every machine/CI runner,
            // so to Google every release looked like a brand-new unknown
            // developer and installs got flagged. A debug-signed release
            // must never ship again: without a stable keystore the release
            // build now FAILS FAST (guard below) instead of producing a
            // flag-magnet APK. See docs/SIGNING.md.
            signingConfig = if (hasReleaseKeystore || useCiKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    // Produce one APK per ABI + a universal one -> exactly the 3 release files.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // IMPORTANT: extract native libs on install so the bundled `aether` and
        // `hev` executables live on disk in nativeLibraryDir and can be exec()'d.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// PLAY-PROTECT FIX, part 2: hard gate. If neither a private keystore nor the
// persisted CI keystore is available, ANY release-producing task fails with a
// clear message instead of quietly emitting an unsigned/debug-signed APK that
// Google Play Protect then blocks as coming from an "unknown developer".
if (!hasReleaseKeystore && !useCiKeystore) {
    tasks.configureEach {
        if (name.contains("Release") &&
            (name.startsWith("assemble") || name.startsWith("package") || name.startsWith("bundle"))
        ) {
            doFirst {
                throw GradleException(
                    "No stable release keystore configured — refusing to build a " +
                        "debug-signed release (it triggers the Play Protect install " +
                        "warning and breaks in-place updates). Run " +
                        "scripts/generate-keystore.sh, or provide KEYSTORE_* env vars / " +
                        ".github/ci-keystore.jks.b64. See docs/SIGNING.md."
                )
            }
        }
    }
}

// Give every generated split APK a distinct, monotonic versionCode.
// IMPORTANT: derived from defaultConfig.versionCode (versionCode * 1000 + ABI
// offset) so each release's codes are strictly HIGHER than the previous
// release's. Android only allows installing an update when the new
// versionCode is greater — the old fixed base of 1000 froze the codes forever
// and silently broke in-place updates.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { it.filterType == ABI }?.identifier
            val base = (android.defaultConfig.versionCode ?: 1) * 1000
            val offset = abiCodes[abiName ?: "universal"] ?: 0
            output.versionCode.set(base + offset)
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
