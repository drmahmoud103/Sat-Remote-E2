Al-Adel Remote - IR remote app (Kotlin, no external libraries)

Build option A (Android Studio): File > Open this folder, accept the Gradle setup, then Build > Build APK(s).
Build option B (online, no install): upload this folder to a new GitHub repo; Actions > Build APK > Run workflow;
download "SatRemote-debug-apk" from the run page.

Use: pick receiver, pick remote layout, point the phone IR blaster at the receiver.
If keys do nothing, try the other layouts, then the three signal options.

Test button: sends Power; tap again to flip the bit order. Use "Flip bit order" only if a remote does nothing.

Version 1.4 (versionCode 5). Targets Android 16 (API 36) as Google Play requires.
Play Store upload: build the signed release bundle (.aab), not the debug APK.
 - Android Studio: Build > Generate Signed App Bundle / APK > Android App Bundle > create a new keystore (keep it safe) > release.
 - GitHub: add secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD; the workflow then also produces a signed .aab.
Before the first upload change applicationId in app/build.gradle.kts to your own unique name (not com.example...).
For each later update raise versionCode by 1 and change versionName.

Remotes: Octagon SF8008 plus 10 remotes built from your extracted user codes and key codes (each one layout).
