# 皮克敏巡花助手（Pikmin Bloom GPS）

專為 **Pikmin Bloom** 設計的單一目的輔助 App（非 root）：

1. **自動巡邏巨大花朵** — 在地圖上標記大花，App 以模擬走路（預設 4.7 km/h）依序巡邏，抵達後在大花 40 m 圈內繞行種花，並以通知提醒你到遊戲內領花蜜。
2. **巡邏時自動計步** — 依模擬距離換算步數，寫入 Health Connect（健康資料同步），讓遊戲培育花苗。
3. **一鍵回家** — 走回你真實的 GPS 位置後自動解除模擬定位。

> 本專案源自 [lokey0905/POGO_Manager](https://github.com/lokey0905/POGO_Manager)，但原專案只是下載入口，
> 不含任何定位或步數功能；這裡保留了建置骨架與主題，其餘完全重寫。設計文件見 [docs/PLAN.md](docs/PLAN.md)。

## 需求
- Android 9 (API 28) 以上；步數功能建議 Android 14 以上（Health Connect 內建）。
- 開發者選項 → **選擇模擬位置應用程式** → 皮克敏巡花助手。
- Pikmin Bloom：設定 → 隱私與步數 → 步數 → **Health Connect**；Health Connect → 應用程式權限 → Pikmin Bloom → 允許讀取步數 + **背景存取**；定位權限「一律允許」。

## 使用流程
1. 開啟 App → 「初始設定」把每一項檢查打勾。
2. 在地圖上**長按**加入大花（可設定繞行半徑與停留秒數），或用「匯入」載入 GPX/JSON。
3. 按「開始巡邏」→ 到遊戲內開啟種花。
4. 抵達提醒出現時，到遊戲內點大花、往下滑領花蜜（每次開花每人一次）。
5. 想結束時按「回家」，等位置走回真實位置後 App 會自動停止模擬。

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

## 免責聲明
本 App 與 Niantic、任天堂無任何關聯。修改定位與步數違反 Pikmin Bloom 使用條款（三振政策），
一切風險由使用者自行承擔。請保持合理速度（4–8 km/h），避免瞬移。
