# 皮克敏巡花助手（Pikmin Bloom GPS）

專為 **Pikmin Bloom** 設計的單一目的輔助 App（非 root）：

1. **自動巡邏** — 在地圖上標記大花，App 以模擬走路依序巡邏，走到你按下「回家」為止。
2. **巡邏時自動計步** — 依模擬距離換算步數寫入 Health Connect，讓遊戲培育花苗。
3. **一鍵回家** — 走回你真實的 GPS 位置後自動解除模擬定位。
4. **浮動控制列** — 懸浮在遊戲畫面上，不用切回本 App 就能暫停、回家、停止。

> 本專案源自 [lokey0905/POGO_Manager](https://github.com/lokey0905/POGO_Manager)，但原專案只是下載入口，
> 不含任何定位或步數功能；這裡保留了建置骨架與主題，其餘完全重寫。設計文件見 [docs/PLAN.md](docs/PLAN.md)。

## 需求
- Android 9 (API 28) 以上；步數功能需 Android 14 以上（Health Connect 內建）。
- 開發者選項 → **選擇模擬位置應用程式** → 皮克敏巡花助手。

## 設定步驟（App 內「初始設定」會逐項檢查）

1. 定位權限、通知權限。
2. 開發者選項 → 選擇模擬位置應用程式 → 本 App。
3. Health Connect 步數與距離讀寫權限。
4. **⚠ Health Connect →「管理資料」→「資料來源與優先順序」→「新增資料來源」→ 選「皮克敏巡花助手」。**
   這一步最容易漏。沒做的話步數寫得進去，但不會計入每日總計，遊戲讀不到。
5. 電池最佳化排除（HyperOS 另需允許自啟動）。
6. Pikmin Bloom 遊戲內：設定 → 隱私權&步數 → 步數 → **Health Connect**，
   並在 Health Connect 中允許 Pikmin Bloom 讀取步數與「在背景存取資料」。
   遊戲的定位權限設為「一律允許」。

> 「使用手機追蹤測量」模式讀的是硬體計步器，任何 App 都無法寫入，必須改用 Health Connect。

## 使用流程
1. 在地圖上**長按**加入大花，或用「搜尋附近候選點」自動帶入 OpenStreetMap 地標。
2. 按「開始巡邏」→ 到遊戲內開啟種花。
3. 抵達提醒出現時，到遊戲內點大花、往下滑領花蜜（每次開花每人一次）。
4. 想結束時按「回家」，等位置走回真實位置後 App 會自動停止模擬。

## 設定重點
| 設定 | 建議 |
|---|---|
| 走路速度 | 4–12 km/h。上限 20，但遊戲約在 15–20 km/h 之間會停止種花。 |
| 抵達後在大花圈內繞行 | 預設關閉。只有想衝某朵大花開花（300 朵）時才打開。 |
| 步數步幅 | 70 公分。距離除以步幅換算步數。 |
| 每日步數上限 | 50000。遊戲的花苗成長上限就是這個數字。 |

## 建置
```bash
./gradlew assembleDebug
```
APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。需要 JDK 17+ 與 Android SDK（platform 37、build-tools 36+）。

以 adb 安裝並設定為模擬定位 App：
```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set app.pikminbloom.gps android:mock_location allow
```

## 不做的事
- **不做遊戲畫面自動點擊**。Pikmin Bloom 是 Unity 畫面，對系統完全不透明（實測五個畫面的無障礙節點掃描結果完全相同，只有一個空白 SurfaceView），既無法定位大花也無法確認點擊成功；地圖鏡頭又可自由縮放旋轉平移且不會自動回正，盲點座標實測兩次全錯。加上自動點擊是可被獨立偵測的違規類別，報酬只有每朵花 1–3 花蜜，不值得。詳見 [docs/PLAN.md](docs/PLAN.md) 附錄 D。
- 不需要 root / Shizuku。
- 不做 Google Fit（API 已淘汰）。

## 大花位置從哪來
Niantic 不公開大花座標，也沒有任何社群資料庫提供。大花長在 Wayspot 上，所以本 App 提供
以 OpenStreetMap Overpass API 查詢附近地標（紀念物、公共藝術、廟宇教堂、遊戲場等）作為**候選點**，
命中率約三到六成，留下真的有大花的即可。OpenStreetMap 資料為 ODbL，僅在再散布時需標註。

## 免責聲明
本 App 與 Niantic、Scopely、任天堂無任何關聯。修改定位與步數違反 Pikmin Bloom 使用條款（三振政策：
警告 7 天 → 停權 30 天 → 永久停權），一切風險由使用者自行承擔。
請保持合理速度，避免瞬移。
