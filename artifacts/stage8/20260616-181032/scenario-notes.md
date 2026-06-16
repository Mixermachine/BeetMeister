# Stage 8 Scenario Notes

- Scenario: justworks-bond-rerun
- Android device serial: RZCY51LB7BD
- Controller port: COM4
- Started at: 2026-06-16 18:10:32

## Evidence

- Android logcat: android-logcat.txt
- Android instrumentation: android-instrumentation.txt
- Controller serial log: controller-serial.txt

## Observations

- Result: Bonding now uses a confirm-pair dialog instead of typed PIN entry, and the app reaches a live connected session with state-stream traffic from the controller.
- Firmware build label: dev
- Controller hardware revision: rev_a
- Notes:
  - Reproduced the previous bond-path regression and verified it is fixed after switching the controller to no-input/no-output bonding.
  - Reproduced the post-bond `GATT service is incomplete` failure and fixed it by ignoring stale GATT callbacks after the bonded session replaces the pre-bond session.
  - Latest evidence is in `android-logcat.txt` around `18:14:54` where `BeetGattSession` receives repeated `state_stream` frames with `syncedPairs=8`.

## Scenario Checklist

- [ ] Bundled firmware success path
- [ ] User abort during upload
- [ ] Android app crash during upload
- [ ] BLE disconnect with successful resume
- [ ] BLE disconnect with session expiry
- [ ] Session invalidation by newer updater
- [ ] Low-battery rejection
- [ ] Busy or watering-active rejection
- [ ] Custom image flow
- [ ] Runtime protocol mismatch warning and override
- [ ] Rollback on failed first boot
