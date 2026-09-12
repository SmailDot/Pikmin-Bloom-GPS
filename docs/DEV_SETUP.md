# 在另一台電腦接手開發（DEV_SETUP）

這份文件讓「另一台電腦 + 一個全新的 Claude Code 對話」可以只靠 git 把專案接起來。
規格與決策全部在 `docs/PLAN.md`（附錄 A–G 是實機測試結果與每次需求變更的原因），先讀它。

## 1. 需要帶過去的東西

| 東西 | 在 git 裡？ | 怎麼帶 |
|------|------------|--------|
| 原始碼、規格、測試 | 是 | `git clone https://github.com/SmailDot/Pikmin-Bloom-GPS.git` |
| release 簽章 keystore（`keystore/` 資料夾，含 `keystore.properties`） | **否（故意 gitignore）** | 用 USB / 加密壓縮檔自己搬。放在 **repo 旁邊**：`<任意路徑>/keystore/`，`app/build.gradle` 讀的是 `../keystore/keystore.properties`。沒有它仍能 build，只是 release 會改用 debug key，**女友手機上的 1.1.0 就無法就地更新**（簽章不同要先解除安裝） |
| 實機截圖測試素材（`app/src/test/resources/birdseye/live_user5.png`、`docs/test/*.png`） | 否（含真實位置） | 可選。沒有的話 `LiveSceneProbeTest` 會自動跳過 |
| Claude 的專案記憶（`%USERPROFILE%\.claude\projects\<專案路徑編碼>\memory\`） | 否 | 可選。內容只有環境路徑與這份文件已寫的東西 |

## 2. 工具

- JDK 21（任何發行版；這台用的是 `C:\Users\user\AppData\Local\Java\jdk-21.0.12.1+1`）
- Android SDK：platform 36/37、build-tools、platform-tools（adb）。`local.properties` 不在 git 裡，clone 後建立：
  ```
  sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
  ```
- Git Bash（PowerShell 在這台會因 Gradle 記憶體出問題，`gradle.properties` 已把 heap 降到 4G）

## 3. 建置與測試

```bash
cd "<repo>"
export JAVA_HOME="<JDK 21 路徑>"
./gradlew.bat assembleDebug :app:testDebugUnitTest --console=plain
```

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（作者自己手機用這個；簽章是 debug key）
- Release APK：`./gradlew.bat assembleRelease`（`app/build/outputs/apk/release/app-release.apk`，給其他人；需要 keystore）
- 版本號在 `app/build.gradle` 的 `versionCode` / `versionName`，每次給別人新版都要 +1

## 4. 裝到手機

```bash
ADB="<SDK>/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
# HyperOS 每次安裝後會把這兩個權限重設，一定要再跑：
"$ADB" shell appops set app.pikminbloom.gps android:mock_location allow
"$ADB" shell appops set app.pikminbloom.gps SYSTEM_ALERT_WINDOW allow
```

Git Bash 對 `/sdcard` 這類路徑會亂轉換，adb pull/push 前加 `MSYS_NO_PATHCONV=1`。

## 5. 用 adb 驅動測試（debug build 才有）

```bash
"$ADB" logcat -c
"$ADB" shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd status
"$ADB" logcat -d -s PikminGPS
```

指令清單在 `app/src/debug/java/app/pikminbloom/gps/debug/DebugCommandReceiver.kt` 的註解與 `when` 分支。
`start` 必須在主畫面開著時發（Android 14+ 不准背景啟動定位型前景服務）。

## 6. 手機端一次性設定（新手機）

`docs/PLAN.md` §4 與附錄 B。最容易漏掉的是 **Health Connect → 管理資料 → 資料來源與優先順序 → 新增本 App**，
沒加的話步數寫進去了但總數不會動。
