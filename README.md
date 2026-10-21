# AirTouch

AirTouch recognizes hand gestures using the front camera and MediaPipe on-device. Thumbs up plays/resumes media, a closed fist pauses media, a horizontal open-hand swipe changes media items/slides, and a two-finger swipe up/down scrolls the current screen in the same direction. Palm-to-lock requests a screen lock after a steady 0.8-second hold.

Camera recognition starts only after the user starts a session while the app is visible and grants camera access. A camera foreground service keeps that user-started session active while the screen is off or another app is in front. The ongoing Android notification includes a Stop camera action. Force-stopping AirTouch or stopping the service ends camera access.

To enable scrolling, open **Settings > Gesture scrolling > Enable gesture scrolling**, then switch on **AirTouch gesture scrolling** in Android Accessibility settings. Android requires this explicit special access before an app can send scroll actions in other apps. AirTouch uses it to locate scrollable controls in the active window and does not collect or store screen text. Scrolling remains unavailable until this service is enabled.

## Build in Android Studio

Open this folder in Android Studio and allow Gradle sync to finish. The project uses JDK 17, Android Gradle Plugin 9.2.1, Gradle 9.4.1, and Android SDK 36.

Run the gesture-engine unit tests with `gradlew testDebugUnitTest`. Build a debug APK with `gradlew assembleDebug`. The current debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Google Play release

The Play release target is API 36. Build the app bundle with `gradlew bundleRelease`; it is written to `app/build/outputs/bundle/release/app-release.aab`.

The bundle is unsigned unless you provide your own upload key. To sign from Gradle, set these environment variables before building:

- `AIRTOUCH_UPLOAD_KEYSTORE`: path to your upload `.jks` or `.keystore` file
- `AIRTOUCH_UPLOAD_STORE_PASSWORD`: keystore password
- `AIRTOUCH_UPLOAD_KEY_ALIAS`: upload key alias
- `AIRTOUCH_UPLOAD_KEY_PASSWORD`: upload key password

Keep the upload key and passwords out of source control. You can also use Android Studio's **Build > Generate Signed Bundle / APK** wizard. Before publishing, upload a public privacy-policy URL, finish the Play Console data-safety form and store listing, and test on the supported device range. The app needs a unique application ID for your Play Console listing; change `applicationId` before the first upload if `com.airtouch.app` is unavailable or not your chosen ID.

## Camera behavior

Android requires the camera foreground service to be started while AirTouch is visible and camera permission is granted. It cannot automatically restart after a force-stop. Camera frames are analyzed on-device and are not saved or uploaded by this app.
