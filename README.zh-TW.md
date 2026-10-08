# 皮克敏巡花助手（Pikmin Bloom GPS）

[English](README.md) ｜ **繁體中文** ｜ [日本語](README.ja.md)

專為 **Pikmin Bloom** 設計的巡花輔助 App，不需要 root。介面有英文、繁體中文、日文，會跟著手機語言自動切換（Android 13 以上也可以在「設定 → 應用程式 → 語言」單獨指定）。

## 功能
- **自動巡邏**：在地圖上標記大花，App 用模擬走路依序巡邏，直到你按「回家」。
- **巡邏時計步**：模擬距離換算成步數寫入 Health Connect，遊戲會拿去培育花苗。
- **回家**：可以走回或瞬移回真實位置，也可以去存好的家（家 1、家 2…）停著。
- **立刻前往／前往後停在這裡**：點大花直接去。「前往後停在這裡」到了就暫停，適合晚上掛著、早上再領獎勵；路程超過設定的公里數（預設 15 km）會先問你要走路（寫步數）還是搭交通工具（不寫步數）。
- **交通工具**：其他、汽車、飛機，速度可以自己設定，不寫步數；到下一朵大花時自動切回走路。
- **浮動控制列＋搖桿**：浮在遊戲畫面上，可以暫停、回家、切換交通工具；搖桿可以拖到任何位置，有三種大小。
- **真實位置切換**：暫時把定位還給手機（例如要開 Google 地圖），回來時再切回虛擬位置。
- **搜尋附近候選大花**：從 OpenStreetMap 帶入附近的地標當作候選點。
- **中斷點續走**：App 被系統關掉後，可以從原本的位置和狀態接著走。

## 需求
- Android 9（API 28）以上；步數功能需要 Android 14 以上（內建 Health Connect）。
- 開發者選項 → **選擇模擬位置應用程式** → 皮克敏巡花助手。

## 設定步驟（App 內「初始設定」會逐項檢查）
1. 定位權限、通知權限、顯示在其他應用程式上層（浮動控制列）。
2. 開發者選項 → 選擇模擬位置應用程式 → 本 App。
3. Health Connect 步數與距離的讀寫權限。
4. **⚠ Health Connect →「管理資料」→「資料來源與優先順序」→ 新增「皮克敏巡花助手」。**
   這一步最容易漏，沒做的話步數寫得進去，但不會算進每日總計，遊戲讀不到。
5. 把本 App 排除在電池最佳化之外（HyperOS 另外要允許自啟動）。
6. Pikmin Bloom 遊戲內：設定 → 隱私權&步數 → 步數 → **Health Connect**，
   並在 Health Connect 允許 Pikmin Bloom 讀取步數與「在背景存取資料」。遊戲的定位權限設為「一律允許」。
7. 建議關閉「Google 定位準確度」（設定 → 位置 → 定位服務），減少定位跳回真實位置的機會。

> 遊戲的「使用手機追蹤測量」模式讀的是硬體計步器，任何 App 都寫不進去，一定要改用 Health Connect。

## 使用流程
1. 在地圖上**長按**加入大花，或用「搜尋附近候選大花」帶入候選點。
2. 按「開始巡邏」，再到遊戲內開啟種花。
3. 跳出抵達提醒時，到遊戲內點大花、往下滑領花蜜。
4. 要結束時按「回家」，走回真實位置後 App 會自動停止模擬。

## 設定重點
| 設定 | 建議 |
|---|---|
| 走路速度 | 預設 18 km/h，實測最穩。上限 20；開到 19–20 時，手機過熱卡頓可能一次衝一大段，遊戲可能當成你在坐車。 |
| 前往後停在這裡：超過幾公里先問怎麼去 | 預設 15。填 0 就不會問。 |
| 抵達後在大花圈內繞行 | 預設關閉，只有想衝某朵大花開花（300 朵）時才打開。 |
| 步幅 | 70 公分，用距離除以步幅換算步數。 |
| 每日步數上限 | 留空就是不限制。想照遊戲的花苗成長上限就填 50000。 |

## 建置
需要 JDK 21 與 Android SDK（platform 37）。
```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```
Release 簽章讀的是 repo **旁邊**的 `../keystore/keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`），
不要放進 repo。沒有這個檔案時，release APK 不會簽章，裝不上手機。

用 adb 安裝並設定成模擬位置 App：
```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set app.pikminbloom.gps android:mock_location allow
```

## 大花位置從哪來
Niantic 不公開大花座標。大花長在 Wayspot 上，所以 App 用 OpenStreetMap Overpass API 查附近的地標
（紀念物、公共藝術、廟宇教堂、遊戲場等）當作**候選點**，大概三到六成真的有大花，留下有的就好。
OpenStreetMap 資料採 ODbL 授權。

## 免責聲明
本 App 與 Niantic、Scopely、任天堂沒有任何關係。修改定位與步數違反 Pikmin Bloom 使用條款（三振政策：
警告 7 天 → 停權 30 天 → 永久停權），風險由使用者自行承擔。請保持合理速度，避免瞬移。
