# BeetMeister Code Context

## 1. Android App BLE Scanning/Bonding UI Flow

### Key Files
- **`app/app/src/main/java/de/aarondietz/beetmeister/ui/feature/connection/ConnectionGate.kt`** - Main connection gate composable. Renders the scan/connect entry point when `connectionGateVisible` is true.
- **`app/app/src/main/java/de/aarondietz/beetmeister/ui/feature/connection/ConnectionDeviceCard.kt`** - Single device card composable showing name, address, RSSI, bond state, and a Connect button.
- **`app/app/src/main/java/de/aarondietz/beetmeister/ui/BeetAppUi.kt`** (lines 89-100, 296-307) - Top-level app composable (`BeetMeisterApp`) that toggles `connectionGateVisible` and wires `ConnectionGate` callbacks to `BeetAppViewModel`.
- **`app/app/src/main/java/de/aarondietz/beetmeister/ui/BeetAppViewModel.kt`** - ViewModel bridging UI callbacks (`startScan`, `connect`) to `BeetRepository`.
- **`app/app/src/main/java/de/aarondietz/beetmeister/ui/core/app/AppNavigation.kt`** - Defines `TopLevelScreen` enum (Overview, Calibration, Events, Settings); only shown after connection.

### Composable Screens

#### `ConnectionGate` (ConnectionGate.kt lines 71-171)
Composable function signature:
```kotlin
@Composable
internal fun ConnectionGate(
    state: BeetRepositoryState,
    permissionsPermanentlyDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

#### `DeviceCard` (ConnectionDeviceCard.kt lines 32-62)
Composable function signature:
```kotlin
@Composable
internal fun DeviceCard(device: BeetDiscoveredDevice, selected: Boolean, onConnect: () -> Unit)
```

### Buttons and User-Actionable Controls

| Phase | Button Label (resource key) | Action |
|-------|---------------------------|--------|
| `PermissionsRequired` (permissions permanently denied) | "Open App Settings" (`R.string.connection_open_app_settings`) | `onRequestPermissions` → opens system settings |
| `PermissionsRequired` (can still request) | "Grant Bluetooth Permissions" (`R.string.connection_grant_bluetooth_permissions`) | `onRequestPermissions` → system permission dialog |
| `BluetoothDisabled` | "Turn On Bluetooth" (`R.string.connection_turn_on_bluetooth`) | `onRequestBluetooth` → system BT enable dialog |
| `Idle` / `Disconnected` / `Error` | **"Scan"** (filled tonal, `R.string.connection_scan`) AND **"Retry"** (outlined, `R.string.connection_retry`) | Both call `onScan` |
| Device card (not selected) | **"Connect"** (`R.string.common_connect`) | `onConnect(device.address)` |
| Device card (selected/connecting) | **"Connecting..."** (`R.string.common_connecting`) | `onConnect(device.address)` |

The `DeviceCard` entire card is also clickable (`Modifier.clickable(onClick = onConnect)`), so tapping the card is equivalent to tapping the button.

### Navigation Flow
1. App launches → `connectionGateVisible = true` → `ConnectionGate` renders.
2. User goes through phases: Permissions → Bluetooth → Scanning/Idle.
3. On scan, discovered devices populate as `LazyColumn` of `DeviceCard` items.
4. Tapping a card or its "Connect" button → `viewModel.connect(address)`.
5. After bonding + GATT connection + services discovered + state sync → `connectionGateVisible = false` → main `NavigationSuiteScaffold` with Overview, Calibration, Events, Settings screens.

---

## 2. BeetScanBondCoordinator.kt Orchestration

### File
**`app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetScanBondCoordinator.kt`** (full file, ~350 lines)

### Class
```kotlin
internal class BeetScanBondCoordinator(
    private val host: BeetRepositoryCallbacks,
)
```

### Core Flow: scan → bond → connect

#### `start()`
- Registers `BroadcastReceiver` for `ACTION_STATE_CHANGED` and `ACTION_BOND_STATE_CHANGED`.
- Calls `refreshEnvironment()`.
- Checks `KEY_LAST_ADDRESS` in SharedPreferences:
  - If blank → `startScan(clearResults = true)`.
  - If saved → calls `connect(savedAddress)` directly (auto-reconnect).

#### `refreshEnvironment()`
- Checks permissions via `BeetBluetoothSupport.hasRequiredPermissions()`.
- Checks adapter null/disabled.
- Publishes `BeetConnectionPhase.PermissionsRequired`, `BluetoothDisabled`, `Error`, or `Idle`.

#### `startScan(clearResults, detail)`
- Re-checks permissions and adapter.
- Gets `bluetoothLeScanner`, stops any existing scan if `scanCallback` already active.
- Clears or prunes `discoveredDevices`.
- Creates `ScanCallback` via `beetScanCallback()` (from `BeetBluetoothCallbacks.kt`).
- Calls `scanner.startScan()` with two `ScanFilter` entries for `serviceUuid` and `maintenanceServiceUuid`, using `SCAN_MODE_LOW_LATENCY`.
- Each scan result → `addScanResult()` → `discoveredDevices.upsert()` + `publishDiscoveredDevices()`.

#### `connect(address)` (the core bonding orchestration)
1. Stops scan.
2. `adapter.getRemoteDevice(address)`.
3. Sets `host.currentAddress = address`.
4. Updates state: `selectedAddress = address`.
5. **If bond state != BOND_BONDED**:
   - Sets `pendingBondAddress = address`, `pendingBondGattKickAddress = address`.
   - Publishes `BeetConnectionPhase.Bonding` with detail "Open pairing dialog".
   - Calls `device.createBond()`.
   - Calls `monitorBondState(device)`.
6. **If already bonded**: calls `host.requestOpenGatt(device)` directly (→ `BeetGattSessionCoordinator.openGatt()`).

#### `monitorBondState(device)`
- Polls `device.bondState` up to 40 times (every 500ms, ~20s total).
- On `BOND_BONDING`:
  - First 2 polls: detail "Confirm pairing dialog".
  - After 2 polls: detail "Waiting for pairing".
  - On attempt ≥1, if no GATT active yet, calls `host.requestOpenGatt(device)` to start GATT during bonding (parallel kick).
- On `BOND_BONDED`:
  - Clears `pendingBondAddress`, `pendingBondGattKickAddress`.
  - Calls `host.requestOpenGatt(device)`.
- On `BOND_NONE`: starts a new scan with "Bonding cancelled" detail (bonding was rejected).
- On timeout (40 polls): starts new scan with "Bonding timed out".

#### `recoverFromStaleBond(address, status)`
- Triggered when GATT connection fails with `GATT_ERROR` status (likely stale bond).
- Checks `device.bondState` over 5 polls (250ms each).
- If bond state is `BOND_NONE` (pairing expired): re-creates bond + `monitorBondState`.
- Otherwise: removes last address and starts new scan.

#### BroadcastReceiver for bond state changes
- `onBondStateChanged()` in `systemReceiver`:
  - `BOND_BONDING` → updates phase detail.
  - `BOND_BONDED` → clears pending, calls `requestOpenGatt`.
  - `BOND_NONE` (from `BOND_BONDING`) → starts new scan.

### UI Callbacks Used (via `BeetRepositoryCallbacks`)
| Callback | Purpose |
|----------|---------|
| `updateConnection(phase, detail)` | Sets phase + detail in `BeetConnectionState` |
| `updateState(transform)` | Applies arbitrary state transforms |
| `publishDiscoveredDevices()` | Copies `discoveredDevices.snapshot()` into state |
| `persistLastAddress(address)` | Saves address to SharedPreferences |
| `removeLastAddress()` | Clears saved address |
| `requestOpenGatt(device)` | Delegates to `BeetGattSessionCoordinator.openGatt()` |
| `requestStartScan(detail, clearResults)` | Restarts scan (used in error recovery) |
| `recoverFromStaleBond(address, status)` | Re-enters bond recovery from GATT layer |

### Key Interface: `BeetRepositoryCallbacks`
**File:** `app/app/src/main/java/de/aarondietz/beetmeister/data/repository/BeetRepositoryCallbacks.kt`

```kotlin
internal interface BeetRepositoryCallbacks {
    val appContext: Context
    val bluetoothAdapter: BluetoothAdapter?
    val strings: BeetStringResolver
    val scope: CoroutineScope
    val state: StateFlow<BeetRepositoryState>
    val session: BeetConnectionSession
    val discoveredDevices: BeetDiscoveredDeviceStore
    var currentAddress: String?
    var manualDisconnectRequested: Boolean
    fun updateConnection(phase: BeetConnectionPhase, detail: String?)
    fun updateState(transform: (BeetRepositoryState) -> BeetRepositoryState)
    fun publishDiscoveredDevices()
    fun setCommandMessage(message: String)
    fun clearCommandMessage()
    fun clearSession()
    fun resetSyncState()
    fun persistLastAddress(address: String?)
    fun removeLastAddress()
    fun requestOpenGatt(device: BluetoothDevice)
    fun requestStartScan(detail: String, clearResults: Boolean = false)
    fun recoverFromStaleBond(address: String, status: Int)
}
```

---

## 3. ADB/uiautomator Test Scripts

### Existing ADB/UIAutomator Tests
- **No uiautomator-specific scripts exist** in the repo (zero matches for `uiautomator`, `UiAutomator`, `uiauto`).

### Existing ADB-based Test Infrastructure

#### Primary Script
**`scripts/dev/run-android-real-device-validation.ps1`** (full file, ~280 lines)
- PowerShell script that:
  - Builds app + instrumentation APKs with `gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
  - Installs both APKs via `adb install -r`.
  - Grants BLE permissions via `adb shell pm grant`.
  - Runs Android instrumentation via `adb shell am instrument -w -e class <InstrumentationClass> de.aarondietz.beetmeister.test/androidx.test.runner.AndroidJUnitRunner`.
  - Captures `logcat` to `artifacts/stage8/<timestamp>/android-logcat.txt`.
  - Optionally captures controller serial output via `capture-bench-diagnostics.ps1`.
  - Writes `scenario-notes.md`.
- Default instrumentation class: `de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateInstrumentationTest`.

#### Instrumentation Test
**`app/app/src/androidTest/java/de/aarondietz/beetmeister/ui/feature/connection/MaintenanceUpdateInstrumentationTest.kt`** (full file, ~120 lines)
- Two test classes:
  - `MaintenanceUpdateInstrumentationTest` (uses `createComposeRule()`): 4 unit/integration-style Compose UI tests:
    - `maintenanceRequiredWithoutSelectionShowsActionsAndDisabledInstall`
    - `selectedCustomFirmwareShowsSummaryWarningsAndEnabledInstall`
    - `uploadingPhaseShowsProgressAndAbortInsteadOfInstall`
    - `startingPhaseShowsAbortAndDisablesSelection`
  - `MaintenanceUpdateLiveActivityInstrumentationTest` (uses `createAndroidComposeRule<MainActivity>()`): 1 live-activity test:
    - `liveActivityCanOpenBundledFirmwareFlow` - navigates Settings → firmware update → bundled button → install button

#### Test Tags
**`app/app/src/main/java/de/aarondietz/beetmeister/ui/feature/connection/MaintenanceUpdateTestTags.kt`** - Defines test tag constants:
- `Card`, `Title`, `CurrentFirmware`, `Summary`, `Details`, `DowngradeWarning`, `RuntimeWarning`, `StatusDetail`, `ErrorDetail`, `Progress`, `InstallButton`, `AbortButton`, `DisconnectButton`, `BundledButton`, `CustomButton`

#### Documentation
**`docs/testing/ble-maintenance-real-device-validation.md`** - Execution checklist and manual controller scenario descriptions.

---

## 4. beet_maintenance.c and beet_maintenance.h (TLV Parsing)

### Exact File Paths
1. **`firmware/esp-idf/components/beet_firmware/src/beet_maintenance.c`** (382 lines) - TLV metadata parser implementation.
2. **`firmware/esp-idf/components/beet_firmware/include/beet_maintenance.h`** (154 lines) - Public header with types, enums, and function declarations.
3. **`firmware/esp-idf/components/beet_firmware/include/beet_maintenance_tlv.h`** (18 lines) - TLV type definitions (magic number, format version, `beet_maintenance_tlv_type_t` enum).

### TLV Parsing Architecture
- **Header struct** (`beet_maintenance_metadata_header_t`):
  - `uint32_t magic` = `0x544D5442UL` (`BEET_MAINTENANCE_METADATA_MAGIC`)
  - `uint16_t metadata_format_version` = `1U`
  - `uint32_t header_crc32`
- **Entry struct** (`beet_maintenance_tlv_entry_header_t`):
  - `uint16_t type` (TLV type)
  - `uint16_t length` (value length)
- **TLV Types** (from `beet_maintenance_tlv.h`):
  - `PRODUCT_ID = 0x0001`, `HARDWARE_REV = 0x0002`, `FIRMWARE_VERSION = 0x0003`, `BUILD_LABEL = 0x0004`
  - `MAINTENANCE_PROTOCOL_VERSION = 0x0005`, `RUNTIME_PROTOCOL_VERSION = 0x0006`
  - `IMAGE_KIND = 0x0007`, `COMPATIBLE_HARDWARE_REV = 0x0008`
- **Key function**: `beet_maintenance_metadata_parse()` (lines 76-240 of `beet_maintenance.c`) - validates header magic/format/CRC, then iterates TLV entries in a loop, populating `beet_maintenance_image_metadata_t`.
- **Result type**: `beet_maintenance_image_metadata_t` (in header) with product_id, hardware_rev, firmware_version, build_label, protocol versions, image_kind, and up to 8 compatible hardware revs.

### Key Functions in beet_maintenance.h
```c
const uint8_t *beet_maintenance_metadata_block(size_t *len_out);
esp_err_t beet_maintenance_metadata_parse(const uint8_t *block, size_t block_len, beet_maintenance_image_metadata_t *metadata);
esp_err_t beet_maintenance_get_info(beet_maintenance_info_t *info);
void beet_maintenance_fill_idle_status(beet_maintenance_status_t *status);
const char *beet_maintenance_state_name(beet_maintenance_state_t state);
const char *beet_maintenance_failure_reason_name(beet_maintenance_failure_reason_t reason);
const char *beet_maintenance_image_kind_name(beet_maintenance_image_kind_t kind);
beet_maintenance_image_kind_t beet_maintenance_image_kind_from_name(const char *name);
bool beet_maintenance_is_valid_sha256_hex(const char *value);
```

---

## 5. Bluetooth Permissions (AndroidManifest.xml)

### File
**`app/app/src/main/AndroidManifest.xml`**

### Declared Permissions
```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />

<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### Runtime Permission Check Logic
**File:** `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetBluetoothSupport.kt` (lines 23-34)

```kotlin
fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
```

### BLE Service UUIDs Used for Scanning
**File:** `app/app/src/main/java/de/aarondietz/beetmeister/data/ble/BeetBluetoothSupport.kt` (lines 10-21)

- `serviceUuid`: `8f2a0001-...` (runtime BLE service)
- `maintenanceServiceUuid`: `8f2a0006-...` (maintenance BLE service)

These are used as `ScanFilter` UUIDs in `BeetScanBondCoordinator.startScan()`.

---

## Architecture Summary

```
BeetMeisterApp (BeetAppUi.kt)
  └─> ConnectionGate (ConnectionGate.kt)
        ├─ "Scan" button → viewModel.startScan()
        ├─ "Retry" button → viewModel.startScan()
        ├─ DeviceCard list → onConnect(address) → viewModel.connect(address)
        └─ MaintenanceScreen (if maintenance required)
  └─> BeetAppViewModel (BeetAppViewModel.kt)
        └─> BeetRepository (BeetRepository.kt)
              ├─> BeetScanBondCoordinator (scan, bond)
              │     └─ on bond success → requestOpenGatt()
              └─> BeetGattSessionCoordinator (GATT, sync, commands)
```

```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "All 5 requested items fully reported with exact file paths, line ranges, class names, composable function names, button labels, and orchestration flow details."
    }
  ],
  "changedFiles": [
    "C:\\git\\BeetMeister\\context.md"
  ],
  "testsAddedOrUpdated": [],
  "commandsRun": [
    {
      "command": "find tool across app/ui and app/data/ble for Kotlin files",
      "result": "passed",
      "summary": "Located all UI composables (ConnectionGate, DeviceCard, BeetAppUi) and coordinator (BeetScanBondCoordinator)"
    },
    {
      "command": "find AndroidManifest.xml and beet_maintenance.*",
      "result": "passed",
      "summary": "Found AndroidManifest at app/app/src/main/AndroidManifest.xml; beet_maintenance.c/.h at firmware/esp-idf/components/beet_firmware/"
    },
    {
      "command": "grep for uiautomator/adb test scripts",
      "result": "passed",
      "summary": "No uiautomator scripts found; one ADB-based PS1 script exists at scripts/dev/run-android-real-device-validation.ps1; one instrumentation test at androidTest"
    }
  ],
  "validationOutput": [
    "ConnectionGate.kt: composable with 5 phase-dependent button paths (Permissions, Bluetooth, Scan, Retry, Connect/Connecting)",
    "DeviceCard.kt: clickable card + Button with 'Connect'/'Connecting...' labels",
    "BeetScanBondCoordinator.kt: full scan→bond→connect orchestration with 20s bond monitor polling",
    "BeetMaintenanceForegroundService declared in AndroidManifest with foregroundServiceType='dataSync'",
    "beet_maintenance.c TLV parser validates magic=0x544D5442, format version=1, CRC32 header checksum"
  ],
  "residualRisks": [
    "No uiautomator scripts exist - any UI automation would need to be built from scratch",
    "Only one instrumentation test class exists (MaintenanceUpdateInstrumentationTest) - no scan/bond/connect UI tests",
    "Button labels are string resources - exact English labels may vary by locale; resource keys (e.g., R.string.connection_scan) are the stable identifiers"
  ],
  "noStagedFiles": false,
  "diffSummary": "Created context.md with comprehensive findings on BLE UI flow, scan/bond coordinator, test infrastructure, maintenance TLV parser paths, and Bluetooth permissions",
  "reviewFindings": [
    "info: ConnectionGate.kt:71 - isBusy computation includes Scanning, Bonding, Connecting, DiscoveringServices, Syncing phases but not MaintenanceRequired",
    "info: BeetScanBondCoordinator.kt:153-175 - connect() launches a coroutine in host.scope; bond monitor polls up to 40×500ms = 20s before timeout",
    "info: AndroidManifest.xml has BLUETOOTH_SCAN with neverForLocation flag but BLUETOOTH_CONNECT without it (standard pattern for API 31+)"
  ],
  "manualNotes": "The ConnectionGate and DeviceCard composables are 'internal' visibility, used only within BeetMeisterApp. The scan filters use both serviceUuid (8f2a0001) and maintenanceServiceUuid (8f2a0006). The ADB validation script at scripts/dev/run-android-real-device-validation.ps1 is the primary entry point for real-device testing."
}
```<｜end▁of▁thinking｜>The findings have been written to `C:\git\BeetMeister\context.md`. Here's a summary of what was found:

## Summary

### 1. BLE Scanning/Bonding UI Flow
- **`ConnectionGate`** composable in `ConnectionGate.kt` renders phase-dependent UI with buttons: "Grant Bluetooth Permissions"/"Open App Settings", "Turn On Bluetooth", "Scan"/"Retry"
- **`DeviceCard`** composable in `ConnectionDeviceCard.kt` shows each discovered device with a "Connect"/"Connecting..." button (entire card is also clickable)
- Flow: Permissions → Bluetooth enable → Scan → Device list → Connect → Bond → GATT → Connected

### 2. BeetScanBondCoordinator Orchestration
- `start()` → check saved address, scan or auto-connect
- `connect()` → if not bonded: `createBond()` + `monitorBondState()` (polls 40×500ms)
- On bond success: `requestOpenGatt()` → `BeetGattSessionCoordinator`
- Callbacks via `BeetRepositoryCallbacks` interface (10 callback methods)

### 3. ADB/UIAutomator Tests
- **No uiautomator scripts exist**
- One ADB validation script: `scripts/dev/run-android-real-device-validation.ps1`
- One instrumentation test: `MaintenanceUpdateInstrumentationTest.kt` (5 tests)

### 4. beet_maintenance Files
- `firmware/esp-idf/components/beet_firmware/src/beet_maintenance.c` (TLV parser)
- `firmware/esp-idf/components/beet_firmware/include/beet_maintenance.h` (types/functions)
- `firmware/esp-idf/components/beet_firmware/include/beet_maintenance_tlv.h` (TLV type enum)

### 5. Bluetooth Permissions
- `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT` (API 31+)
- `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (API ≤30)
- Plus: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`