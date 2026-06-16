# Stage 8 Scenario Notes

- Scenario: android-connected-smoke
- Android device serial: RZCY51LB7BD
- Controller port: COM4
- Started at: 2026-06-16 09:17:35

## Evidence

- Android logcat: android-logcat.txt
- Android instrumentation: android-instrumentation.txt
- Controller serial log: controller-serial.txt

## Observations

- Result:
- Firmware build label:
- Controller hardware revision:
- Notes:

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
