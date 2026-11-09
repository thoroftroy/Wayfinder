# Wayfinder for Android

An independent Android project from Wonderdesk by Rednave. This is the existing Android preview, not a full port of the former Linux application.

Browse an Android-selected folder, filter its contents, and sort by name or file size. Folders stay first. File access and supported operations depend on the selected Android document provider.

## Build

Install JDK 17 or 21 and Android SDK 35, then set `ANDROID_HOME` or configure `sdk.dir` in an untracked `local.properties`. Run:

```sh
./gradlew :wayfinder:assembleDebug
```

The debug APK is in `wayfinder/build/outputs/apk/debug/`. It is not a production-signed release. Debug builds use your local Android debug key; they cannot update an existing installation signed with a different key. Back up any important app data before changing signing keys or uninstalling. The application ID is preserved from the Wonderdesk Android monorepo. `versions.json` is a local snapshot; change the relevant app version there when releasing. The catalog and ecosystem version under `shared/src/main/assets/wonderdesk/` are also snapshots, not a live service.

## Appearance

Aqua is the default. Open About to choose Aqua, Bliss, Dark Bliss, or Super and adjust motion independently in this app. Aqua and Super have ambient motion when Full motion and Android animations are enabled; Reduced and Off use still backgrounds. These Compose adaptations are not yet exact ports of BlissPlayer's typography, dialogs, click effects, or every control. Existing feature and platform limitations are described in the app's About screen. Artwork may still be temporary.

The `shared` module is vendored so this repository builds without the monorepo or other Wonderdesk repositories. Changes to shared code must currently be propagated to the other repositories manually. Third-party license notices are in `shared/src/main/assets/licenses/`.

## Publishing privately

In GitHub Desktop choose **File → Add local repository**, select this folder, review the files and create an initial commit. Choose **Publish repository** and ensure **Keep this code private** is checked before publishing. No remote has been configured and nothing is uploaded automatically.
