<p align="center">
  <img src="https://raw.githubusercontent.com/abhiramjois/baddhu/main/docs/baddhu_logo.png" width="128" alt="Baddhu">
</p>

<h1 align="center">Baddhu</h1>

<p align="center">A small, branded Android wrapper around Instagram DMs — built for one job: open your messages and watch the reels they contain, without Instagram's own UI in the way.</p>

---

## Why this exists

Instagram's DM web UI is fine for browsing, but it is not pleasant to use full-screen on a phone: reels get cropped, the layout fights the system bars, and everything is Instagram's brand rather than yours.

Baddhu is a single-activity WebView shell that:

- puts your own wordmark and tagline where Instagram's chrome used to be,
- renders shared reels at their **native 9:16 aspect ratio** with no crop and no letterboxing,
- keeps the system navigation bar visible and correctly inset, and
- refuses to navigate anywhere except an allow-list of DM routes.

There is no backend, no account handling and no analytics. It is a WebView and a rule engine.

## Reel sizing

The interesting problem is the reel. Instagram's player is a horizontally-snapping pager that sizes itself to the viewport width, so on a tall phone a 9:16 clip ends up 384dp wide and 634dp tall — and because the player uses `object-fit: cover`, roughly **49px of the video is cropped off the top and bottom**.

The obvious fix is to resize the reel's DOM node from JavaScript. That does remove the crop, and it also breaks the player: Instagram lays the controls, caption and audio pill out in a **parallel subtree positioned against the video's dimensions**. Growing one side of that relationship and not the other makes the controls unreachable.

So Baddhu does not touch the DOM at all. Instead it narrows the WebView to exactly the width a 9:16 video needs at the available height, and lets Instagram lay out its own player natively:

```
reelWidthDp = (viewportHeightDp - COMPOSER_RESERVE_DP) * 9 / 16
```

`COMPOSER_RESERVE_DP` is `69f` — the strip Instagram reserves below the video for the message composer. The width is applied to the `WebView` view itself (not as holder padding, which re-measures a layout pass late and leaves the view stuck at its previous width) and is re-derived on every layout pass, since insets and the tagline are not final on the first one.

The result on a Pixel 7 is a 357×703 viewport with a 358×634 reel box — a 0.5646 ratio against the source's 0.5625, the difference being sub-pixel rounding, with no black bars.

## Architecture

```
MainActivity.kt     window insets, brand header, tagline, WebView sizing
DmWebViewClient.kt  navigation filtering, external-link handoff
DmWebChromeClient.kt  file chooser for attachments
rules/RuleEngine.kt  the injected script: route guard + reel handling
rules/Rules.kt       route allow-list model
rules.json           the allow-list itself (bundled in assets)
```

Navigation is guarded by an allow-list rather than a block-list. `rules.json` permits `/direct` and `/reel/` and rejects `/reels/`; anything else is redirected home. An optional `rulesUrl` Gradle property points at a remotely hosted rules file.

The rule engine also handles the reel pager. Two details worth knowing if you touch it:

- **`findReelPager()` is structural, not stylistic.** It finds the nearest scrollable ancestor that actually contains at least two tall videos. The obvious implementation — "the element whose computed `scroll-snap-type` contains `y`" — is self-defeating, because the first thing the lock does is set `scroll-snap-type: none`. It then matches nothing and silently stops working.
- **Never `preventDefault()` a `touchstart`.** Cancelling touchstart cancels the browser's default touch handling, so Chrome never synthesises the follow-up `click`. This is why the reel's Like, Comment and Mute buttons were dead while the same buttons worked with a mouse: the lock was suppressing click synthesis for the entire reel subtree. The lock now only records a starting position on touchstart, and cancels only an unambiguous vertical drag, so taps stay on the normal path.

## Build

Requires JDK 17 and the Android SDK (compile/target SDK 35, min SDK 26).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew :app:test            # 5 unit tests
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:assembleRelease # release APK (R8 + resource shrinking)
```

## Release signing

Signing is opt-in so that a fresh clone builds without needing anyone else's keys. Without a `keystore.properties`, `assembleRelease` still succeeds — it just emits an unsigned APK, which Android will refuse to install.

To produce an installable release, generate a keystore and add a `keystore.properties` to the repo root (it is git-ignored):

```bash
keytool -genkeypair -v -keystore ~/baddhu-release.jks -storetype JKS \
  -alias baddhu-release -keyalg RSA -keysize 4096 -validity 10000
```

```properties
storeFile=/absolute/path/to/baddhu-release.jks
storePassword=...
keyAlias=baddhu-release
keyPassword=...
```

Keep that keystore and its passwords. Android has no way to ship an update signed by a different key to an existing install, so losing it means a new `applicationId` and every user reinstalling.

## Install

Grab the APK from the [releases page](https://github.com/abhiramjois/baddhu/releases) and install it, allowing installs from your file manager when prompted. Requires Android 8.0 (API 26) or newer.

## Known issues

- Reel control taps were just fixed at the root cause (the `touchstart` `preventDefault` described above). Automated testing with real Android touch injection now shows taps producing `click` events, where previously only `touchstart`/`touchend` fired with no click. Worth confirming on an actual finger.
- Instagram regularly changes its DOM. The rule engine is written to fail quietly rather than break the page, but a redesign may require revisiting the route allow-list or the reel detection.
- There is no offline mode; the app is a live view of instagram.com.
- Coexisting with the official Instagram app is untested and may involve account challenges.

## Legal

This project is not affiliated with, endorsed by, or sponsored by Instagram or Meta. It is an unofficial client, it does not include or redistribute any Instagram code or media, and it requires you to sign in with your own account. You are responsible for complying with Instagram's Terms of Use and applicable law in your jurisdiction.

## License

[MIT](LICENSE) © 2026 abhiramjois

Note that the license covers the Baddhu source code only. It does not grant any rights to
Instagram content, and it does not change Instagram's Terms of Use, which you are still
responsible for following when you use this app.
