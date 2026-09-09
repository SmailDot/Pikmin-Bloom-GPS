# 皮克敏巡花助手 (Pikmin Bloom GPS) — 規格與實作計畫

> 本文件同時是給人看的計畫，也是給實作代理 (implementation agents) 的任務書。API 名稱維持英文。

## 0. 背景與結論

- 原始 repo (lokey0905/POGO_Manager) 只是「下載入口 / 版本檢查」的管理器，本身**沒有** GPS 模擬與步數功能；Pikmin 相關功能全靠外部 App（GPS JoyStick、DeFit）。
- 因此本專案改為：保留 Gradle/AGP 骨架與 Material 3 主題，其餘全部重寫成單一目的 App。
- 目標裝置：POCO X6 Pro (2311DRK48G)，Android 16 (API 36)，HyperOS 3.0，Pikmin Bloom 152.0，Health Connect 為系統模組。

## 1. 使用者需求 → 功能

| # | 需求 | 實作 |
|---|------|------|
| 1 | 自動巡邏、採收巨大花朵 (Big Flowers) | 使用者在地圖上標記大花位置（或輸入座標）。App 以「模擬走路」在各大花之間移動；抵達後在大花 40 m 圈內繞行一段時間（讓遊戲在圈內種花，種滿 300 朵開花；開花後 1 小時內在圈內種花可獲得果實遠征），並以通知+震動提醒使用者「已抵達，可在遊戲內點大花往下滑領花蜜」。遊戲內點擊無法可靠自動化（大花在畫面上的位置不固定），故 v1 不做自動點擊。 |
| 2 | 巡邏時計步（培育花苗） | 依模擬距離換算步數（距離 / 步幅，預設 0.70 m），每 60 秒把 `StepsRecord`（+`DistanceRecord`）寫入 Health Connect。Pikmin Bloom 需在遊戲設定中改用 Health Connect 讀步數，並授權背景讀取。每日花苗步數上限 50,000（可設定）。 |
| 3 | 想結束時「自動回家」 | 開始巡邏前先記錄真實 GPS 位置為 Home。按「回家」後以走路速度沿直線走回 Home（或選擇瞬移），到達後解除模擬定位，遊戲看到的位置與真實位置無縫接軌。 |

### 非目標 (v1)
- 不做遊戲畫面自動點擊 (AccessibilityService)。
- 不需要 root / Shizuku。
- 不做 Google Fit（API 已淘汰）。

## 2. 遊戲機制要點（研究結果，設計依據）

- 大花位於固定 POI（Wayspot）。無公開 API，使用者需自行標記。
- 大花圈半徑 **40 m**：圈內任何玩家種的花都算；累積 300 朵開花，開花維持 23 小時。
- **花蜜**：靠近開花的大花（約 100 m 內）打開資訊頁往下滑，每人每次開花只能領一次。
- **果實**：開花後 1 小時內在 40 m 圈內種花，可獲得果實遠征（需手動派遣）。
- **種花靠 GPS 位移，不靠步數**：地面為 5×5 m 格子，每格 5 分鐘內只能種一次 → 巡邏路線應避免原地小圈打轉，繞行要涵蓋不同格子。
- 種花速度上限約 15–20 km/h；安全模擬速度 4–8 km/h。花瓣按時間消耗（40+ 皮克敏 6 瓣/分）。
- 遊戲對 Pikmin 的定位授權為「一律允許」時，種花可在背景進行。
- 步數來源：Android 14+ 用 Health Connect；Pikmin 需授權 `READ_STEPS` 與「背景存取」。花苗每日步數上限 50,000。
- Niantic 三振政策明文禁止 GPS 造假；社群觀察 Pikmin 執法很少，但風險存在，UI 需顯示免責提示。

## 3. 架構

單一 module `app`，package `app.pikminbloom.gps`。

```
geo/      LatLng, GeoMath                 純 Kotlin，可單元測試
route/    PatrolPlanner                   把 waypoints 轉成路線段（含圈內繞行）
sim/      WalkSimulator                   每 tick 產生一個 Sample（位置/速度/方位/精度）
mock/     MockLocationController          LocationManager test providers + FLP mock mode
steps/    StepInjector                    Health Connect 寫入 / 讀回 / 今日總量
service/  PatrolService, PatrolNotifications   前景服務、1 Hz 迴圈、狀態 StateFlow
data/     Models (已寫好), Prefs, WaypointStore
ui/       MainActivity(地圖+控制), WaypointListActivity/Dialog, SettingsActivity, SetupActivity, HealthRationaleActivity
debug/    (src/debug) DebugCommandReceiver   讓 adb 可以驅動測試
```

### 3.1 已存在的共用型別（`data/Models.kt`, `geo/LatLng.kt`）
`LatLng`, `Waypoint(id,name,lat,lon,radiusM,dwellSec)`, `LoopMode{LOOP,PINGPONG,ONCE}`, `ReturnMode{WALK,TELEPORT}`, `PatrolConfig(...)`, `PatrolPhase{IDLE,STARTING,WALKING,DWELLING,PAUSED,RETURNING_HOME,STOPPING}`, `PatrolState(...)`。**不要改欄位名稱**；需要新增欄位可以加，但要保持既有欄位。

### 3.2 各模組公開 API（契約）

#### geo/GeoMath.kt (object GeoMath)
```kotlin
fun distanceM(a: LatLng, b: LatLng): Double                  // haversine
fun bearingDeg(a: LatLng, b: LatLng): Double                  // [0,360)
fun destination(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng
fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng
fun offsetMeters(p: LatLng, northM: Double, eastM: Double): LatLng
fun normalizeBearing(deg: Double): Double
```

#### route/PatrolPlanner.kt
```kotlin
data class RouteSegment(val from: LatLng, val to: LatLng, val waypointIndex: Int?, val kind: SegmentKind)
enum class SegmentKind { TRAVEL, ORBIT }
data class PatrolPlan(val segments: List<RouteSegment>, val totalLengthM: Double)

object PatrolPlanner {
    /** Build one lap. start = current position. For each waypoint: TRAVEL to the circle edge, then ORBIT
     *  segments inside the circle that cover many distinct 5 m cells for approximately dwellSec at speedMps
     *  (e.g. a rosette / spiral / lawn-mower pattern; NEVER a tight circle smaller than 10 m). */
    fun planLap(start: LatLng, waypoints: List<Waypoint>, config: PatrolConfig, order: List<Int>): PatrolPlan
    fun orderFor(lap: Int, count: Int, mode: LoopMode): List<Int>   // LOOP: 0..n-1 ; PINGPONG: alternate reversed ; ONCE: only lap 0
    fun planReturnHome(from: LatLng, home: LatLng): PatrolPlan       // single TRAVEL segment
}
```

#### sim/WalkSimulator.kt
```kotlin
data class Sample(
    val position: LatLng, val speedMps: Double, val bearingDeg: Double, val accuracyM: Float,
    val altitudeM: Double, val distanceDeltaM: Double, val segmentIndex: Int, val waypointIndex: Int?,
    val kind: SegmentKind, val arrivedAtWaypoint: Int?  /* non-null exactly once when a new waypoint circle is entered */,
    val lapFinished: Boolean,
)
class WalkSimulator(config: PatrolConfig, random: kotlin.random.Random = kotlin.random.Random.Default) {
    fun load(plan: PatrolPlan)                       // resets progress along a new plan
    fun advance(dtSec: Double): Sample               // moves speed*dt (with jitter) along the plan; clamps at end
    fun current(): Sample                            // last sample without moving (used while PAUSED: re-push same fix)
    val finished: Boolean
    fun setSpeed(mps: Double)
}
```
Realism rules: speed jitter ±`speedJitterPct` re-drawn every 3–8 s (not every tick), lateral GPS noise ≤ 1.0 m applied to the *reported* position only (progress along the path is exact), accuracy uniform in [accuracyMinM, accuracyMaxM] changing slowly, bearing = segment bearing, altitude = config.altitudeM ± 0.3 m drift. When speed is 0 (paused), report speed 0 and keep bearing.

#### mock/MockLocationController.kt
```kotlin
class MockLocationController(context: Context) {
    fun isMockAppSelected(): Boolean               // AppOpsManager.unsafeCheckOpNoThrow(OPSTR_MOCK_LOCATION, uid, pkg) == MODE_ALLOWED
    fun start(config: PatrolConfig)                // addTestProvider gps (+network,+fused on API31+ if enabled), setTestProviderEnabled; FLP setMockMode(true) if enabled. Catch IllegalArgumentException (already exists) and SecurityException (-> throw MockNotAllowedException)
    fun push(sample: Sample)                       // build Location (lat, lon, alt, accuracy, speed, bearing, time=now, elapsedRealtimeNanos=now, verticalAccuracy 1.5f, speedAccuracy 0.3f, bearingAccuracy 5f, extras satellites=9 usedInFix=9); setTestProviderLocation for every provider; FLP setMockLocation
    fun stop()                                     // setTestProviderEnabled(false) + removeTestProvider for each, FLP setMockMode(false); swallow exceptions
    suspend fun currentRealLocation(timeoutMs: Long = 15_000): LatLng?   // ONLY valid when mock is stopped: FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY) with CancellationToken, fallback lastLocation, fallback LocationManager.getLastKnownLocation(GPS/NETWORK)
    fun openMockAppPicker(activity: Activity)      // Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
}
class MockNotAllowedException(msg: String) : Exception(msg)
```
Providers: `LocationManager.GPS_PROVIDER`, `LocationManager.NETWORK_PROVIDER`, and on API ≥ 31 `LocationManager.FUSED_PROVIDER` — each wrapped in its own try/catch so a failure on one does not abort the others. API ≥ 31 uses `ProviderProperties.Builder()` (accuracy FINE, power LOW for network/fused, HIGH for gps, altitude/speed/bearing supported); API < 31 uses the 10-arg overload with `Criteria` constants.

#### steps/StepInjector.kt
```kotlin
class StepInjector(context: Context) {
    val sdkStatus: Int                                     // HealthConnectClient.getSdkStatus
    val isAvailable: Boolean
    val requiredPermissions: Set<String>                   // write+read steps, write+read distance
    suspend fun hasPermissions(): Boolean
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>>   // PermissionController.createRequestPermissionResultContract()
    suspend fun write(start: Instant, end: Instant, steps: Long, distanceM: Double): Boolean
        // StepsRecord + DistanceRecord, zone offsets from system zone, metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)) with clientRecordId = "pbgps-steps-<startEpochMs>"; records must be <= 1 h span, end <= now; returns false (never throws) on failure and logs the reason
    suspend fun stepsToday(): Long                         // aggregate COUNT_TOTAL from local midnight to now (all sources)
    suspend fun stepsWrittenByUsToday(): Long               // readRecords filtered by dataOrigin == our package
    suspend fun deleteOurRecordsToday()                    // deleteRecords by time range for our dataOrigin
    fun openHealthConnectSettings(context: Context)
}
```
Metadata factory (connect-client 1.1.0): `androidx.health.connect.client.records.metadata.Metadata.autoRecorded(device: Device, clientRecordId: String? = null)`, `Device(type = Device.TYPE_PHONE, manufacturer = ..., model = ...)`.

#### service/PatrolService.kt (LifecycleService, foregroundServiceType="location")
Intent actions (constants in companion): `ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_RETURN_HOME`, `ACTION_STOP`, `ACTION_SKIP_WAYPOINT`.
`ACTION_START` extras: none required — the service loads waypoints + config from `WaypointStore`/`Prefs`. Optional extras: `EXTRA_HOME_LAT/EXTRA_HOME_LON` (override captured home), `EXTRA_START_AT_INDEX`.

State: `companion object { val state: StateFlow<PatrolState>; val events: SharedFlow<PatrolEvent> }` (process-local; the UI collects it). `PatrolEvent`: `ArrivedAtWaypoint(index, name)`, `LapFinished(lap)`, `ReturnedHome`, `Error(message)`, `StepsWritten(count, todayTotal)`.

Lifecycle:
1. START → phase STARTING: `startForeground` immediately (within 5 s, type location) with the patrol notification; acquire PARTIAL_WAKE_LOCK; check `isMockAppSelected()` else emit Error + stop; capture home = `currentRealLocation()` unless an override / a saved home from Prefs is used (UI decides and passes extras); persist home to Prefs; `mock.start()`; build plan with `PatrolPlanner.planLap(home, waypoints, config, orderFor(0,...))`; `sim.load(plan)`; phase WALKING.
2. Tick loop: `while (isActive) { tick(); delay(config.tickMs) }` on a coroutine (Dispatchers.Default); each tick: if PAUSED → `mock.push(sim.current().copy(speed 0))` (Android 12+ needs ≥1 Hz re-push or the fix decays); else `sample = sim.advance(dt)`, `mock.push(sample)`, accumulate distance, steps = floor(distance / strideM) accrued; on `arrivedAtWaypoint` → phase DWELLING, event + high-priority notification (vibrate) if `notifyOnArrival`; when ORBIT segments end → WALKING; on `lapFinished` → next lap per LoopMode (ONCE → auto RETURN_HOME).
3. Step flush: every `stepFlushIntervalSec` (and on pause/stop/return-home completion) write accrued steps since last flush with `StepInjector.write(lastFlush, now, steps, distance)` if `injectSteps` and `stepsWrittenToday + steps <= dailyStepCap` (clamp; do not write over the cap). Update `stepsWrittenToday` in state (read once at start via `stepsWrittenByUsToday()`, then accumulate).
4. RETURN_HOME → phase RETURNING_HOME: `sim.load(planReturnHome(current, home))`; WALK mode walks at config speed; TELEPORT pushes home directly; when `sim.finished` → push home fix 3 more ticks, `mock.stop()`, flush steps, event ReturnedHome, stopSelf.
5. STOP → phase STOPPING: flush, `mock.stop()` (position stays wherever it was — warn in UI that this may look like a teleport), release wakelock, stopForeground(REMOVE), stopSelf.
6. Notification (CHANNEL_PATROL, low importance, ongoing): title = phase text, text = "第 N 個大花 · 已走 1.2 km · 今日步數 3,450"; actions: 暫停/繼續, 回家, 停止 (PendingIntents to the service). Update at most once per 5 s.
7. Robustness: `onTaskRemoved` keeps running; `START_STICKY` is NOT used (do not auto-restart a spoof after process death; instead on restart, if mock providers linger, `mock.stop()` in `onCreate` of the Application? No — leave to service). Catch every exception in tick, publish `lastError`, and keep going except for MockNotAllowedException which stops the service.

#### data/Prefs.kt & data/WaypointStore.kt
- `Prefs(context)`: reads/writes `PatrolConfig` from `PreferenceManager.getDefaultSharedPreferences` using string keys that match `res/xml/preferences.xml` (keys: `speed_kmh` (String, default "4.7"), `speed_jitter_pct`, `stride_cm` (default "70"), `loop_mode`, `inject_steps` (Boolean), `step_flush_sec`, `daily_step_cap`, `accuracy_min_m`, `accuracy_max_m`, `altitude_m`, `notify_on_arrival`, `return_mode`, `mock_network`, `mock_fused`, `flp_mock_mode`, `default_radius_m`, `default_dwell_sec`). Also `home: LatLng?` (`home_lat`/`home_lon` as Long bits), `lastPosition`.
- `WaypointStore(context)`: JSON file `waypoints.json` in `filesDir`; `load(): List<Waypoint>`, `save(list)`, `add/update/remove/move(from,to)`, `exportJson(): String`, `importJson(text)`, `importGpx(text)` (wpt elements), `exportGpx(): String`. Expose `StateFlow<List<Waypoint>>`.

#### ui/
- `MainActivity`: full-screen osmdroid `MapView` (Mapnik tiles, multi-touch, zoom 17 default centered on home or last position or Taiwan 25.03,121.56). Overlays: Waypoint markers (numbered, tap → edit/delete/reorder bottom sheet), 40 m circle (`Polygon.pointsAsCircle`) + orbit radius circle per waypoint, Home marker, current simulated position marker (with bearing), planned route `Polyline`, walked trail. Long-press on map → "新增大花" dialog (name, radius, dwell). FAB menu: 我的位置 (real or simulated), 大花清單, 設定, 初始設定檢查. Bottom card: status line (phase, 目前目標, 距離, 已走距離, 本次步數, 今日步數, 速度) + buttons: **開始巡邏 / 暫停 / 繼續**, **回家**, **停止**. On 開始巡邏: run pre-flight checks (location permission, notification permission (API 33+), mock app selected, ≥1 waypoint, Health Connect permissions if injectSteps — offer to continue without steps), then `startForegroundService(ACTION_START)`. If a saved home exists and the mock is currently inactive, ask "使用上次的家 / 重新定位". Collect `PatrolService.state` with `repeatOnLifecycle(STARTED)`.
- `SetupActivity`: checklist with status icons and one-tap fix buttons: (1) 定位權限, (2) 通知權限, (3) 開發者選項 → 選擇模擬位置應用程式 (button opens developer settings; text explains: 若「開發者選項」未開啟，到「關於手機」連點 MIUI/OS 版本 7 次), (4) Health Connect 可用 + 權限 (button launches permission contract), (5) 電池最佳化排除 (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), (6) Pikmin Bloom 設定說明 (text: 遊戲內 設定 → 隱私與步數 → 步數 → 使用 Health Connect；並在 Health Connect → 應用程式權限 → Pikmin Bloom 開啟「背景存取」；定位權限設為「一律允許」), (7) 開啟 Pikmin Bloom 按鈕. Re-check on `onResume`.
- `SettingsActivity` + `SettingsFragment` (PreferenceFragmentCompat, `res/xml/preferences.xml`) with the keys above (EditTextPreference numeric inputType, ListPreference for loop/return mode, SwitchPreferenceCompat). Include a "重設今日寫入的步數 (刪除本 App 今日寫入的 Health Connect 紀錄)" preference and an "關於 / 免責聲明" preference.
- `HealthRationaleActivity`: shows `@string/health_rationale_body`.
- All UI strings in `res/values/strings.xml` in Traditional Chinese (zh-TW). Service-only strings live in `res/values/strings_service.xml`.

#### src/debug/AndroidManifest.xml + debug/DebugCommandReceiver.kt (debug build only)
Exported `BroadcastReceiver` with action `app.pikminbloom.gps.DEBUG_CMD`, extras: `cmd` in {`start`,`pause`,`resume`,`return_home`,`stop`,`set_waypoints`,`set_home`,`write_steps`}, `waypoints` (String "lat,lon,name;lat,lon,name"), `home` ("lat,lon"), `steps` (Int), `minutes` (Int). It writes to WaypointStore/Prefs and forwards to PatrolService. Purpose: `adb shell am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd start ...` for automated device tests. Must not exist in release.

## 4. 手機端設定步驟（使用者）
1. 開發者選項 → USB 偵錯（已完成）→ **選擇模擬位置應用程式 = 皮克敏巡花助手**。
2. App 初始設定頁完成所有檢查。
3. Pikmin Bloom：設定 → 隱私與步數 → 步數 → **Health Connect**；Health Connect → 應用程式權限 → Pikmin Bloom → 允許讀取步數 + **背景存取**。Pikmin 定位權限「一律允許」。
4. 在地圖長按加入大花 → 開始巡邏 → 遊戲內開啟種花 → 抵達提醒時到遊戲點大花往下滑領花蜜 → 想結束按「回家」。

## 5. 測試計畫
- 單元測試 (JUnit, pure Kotlin)：GeoMath（已知距離/方位/目的地反算）、PatrolPlanner（每個 waypoint 至少一個 TRAVEL + ORBIT，ORBIT 全在 radius 內，總長度合理，LOOP/PINGPONG/ONCE 順序）、WalkSimulator（速度 × 時間 ≈ 走過距離、jitter 在範圍內、arrivedAtWaypoint 只觸發一次、finished 行為、暫停不前進）。
- 裝置測試 (adb, debug build)：`adb shell appops set app.pikminbloom.gps android:mock_location allow`；`am broadcast ... set_waypoints/start`；`adb shell dumpsys location | grep -A3 "last location"` 觀察 gps/fused 位置每秒更新且座標沿路線移動；`adb shell cmd location get-last-location`（若可用）；開啟 Pikmin Bloom 截圖確認人物移動；Health Connect：用 `write_steps` 寫入後以 App 內「今日步數」讀回；回家：位置回到 home 且 test providers 移除（`dumpsys location` 不再顯示 mock）。

## 6. 風險
- Pikmin 若之後開始拒絕 `isMock` 定位，非 root 方案無解（目前社群普遍可用）。
- Health Connect 步數是否被 Pikmin 採計取決於遊戲端讀取方式（recordingMethod 過濾未知）；先以 `autoRecorded` + `TYPE_PHONE` 測試，必要時改 `activelyRecorded`。
- HyperOS 省電策略會殺前景服務：需電池最佳化排除 + 自啟動允許。
