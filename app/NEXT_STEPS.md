# Android Next Step

## Next refactor target

The next structural step is to split [BeetRepository.kt](/C:/git/BeetMeister/app/app/src/main/java/de/aarondietz/beetmeister/beet/BeetRepository.kt:1) into two focused collaborators:

1. `BeetScanBondCoordinator`
   Responsible for:
   - Bluetooth permission/environment checks
   - scanning and discovered-device updates
   - bond monitoring and bond-state recovery
   - reconnect entry decisions

2. `BeetGattSessionCoordinator`
   Responsible for:
   - GATT connect/disconnect lifecycle
   - service discovery and descriptor subscription
   - controller-info read/retry handling
   - state-stream / command-result parsing
   - initial sync completion rules

## Why this is the next step

- `BeetRepository` is still the main complexity hotspot in the Android app.
- The UI is now reasonably split by screen, so the biggest remaining structural debt is BLE/session orchestration.
- Separating scan/bond concerns from active GATT session concerns should improve readability, testability, and change safety without changing the UI contract.

## Constraints

- Keep `BeetAppViewModel` and `BeetRepositoryState` stable during the refactor.
- Do not change the BLE protocol or connection UX as part of this step.
- Preserve the current runtime behavior first; cleanups come second.

## Acceptance criteria

- `BeetRepository` becomes a thin coordinator instead of owning all BLE logic directly.
- The scan/bond path can be reasoned about without reading GATT code.
- The GATT session path can be reasoned about without reading scan/bond code.
- `:app:assembleDebug` and `:app:testDebugUnitTest` still pass.
