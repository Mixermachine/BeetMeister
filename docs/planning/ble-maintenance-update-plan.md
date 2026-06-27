# BeetMeister BLE Maintenance Update Plan

## Summary

BeetMeister needs a user-facing firmware update path that works without garden Wi-Fi, without manual file handling, and without asking the user to understand networking. The primary update path shall therefore be Bluetooth-based and app-driven.

The Android app shall bundle the production firmware image and transfer it to the controller over BLE through a dedicated maintenance update channel. The controller shall apply the image using the existing ESP-IDF OTA slot layout and rollback support.

This maintenance update channel is a separate compatibility contract from the main BLE runtime control protocol. The latest app must be able to identify a production BeetMeister controller, pair with it, determine whether a forced update is required, and update it successfully using only Bluetooth. This compatibility promise starts with the first production firmware that ships the maintenance channel.

## Product Intent

### End-user expectations

- A normal user shall update the controller from the Android app only.
- The user shall not need a local Wi-Fi network, hotspot, IP address, local server, or manual firmware download.
- The app shall guide the user through pairing and update progress with a single guided flow.
- If a controller firmware is too old for the current runtime BLE app protocol, the app shall force the maintenance update flow instead of failing with a generic connection error.

### Compatibility promise

- The maintenance update channel shall be versioned independently from the runtime BLE protocol.
- The runtime BLE protocol may evolve separately.
- The maintenance update channel shall remain backward-compatible across app releases.
- If later maintenance protocol revisions add fields or commands, they shall do so compatibly through optional fields or capability advertisement instead of breaking earlier maintenance protocol versions.
- The latest app shall be able to update any production controller that shipped with the maintenance update channel, even if the controller's runtime BLE protocol is no longer current.

### Maintenance protocol evolution rules

The maintenance protocol is intended to avoid version changes wherever possible.

Rules:

- additive non-breaking changes are the default evolution path
- unknown optional fields from a newer peer shall be ignored
- unknown capability flags shall be ignored unless a command explicitly requires them
- any behavior change in an existing maintenance command, status, or required field contract shall require a new `maintenance_protocol_version`
- existing required fields shall not change meaning within the same `maintenance_protocol_version`
- existing command names, status states, and failure reason identifiers shall remain stable

`maintenance_protocol_version` shall change only when a genuinely breaking protocol change is introduced, such as:

- removing a previously required field
- changing the meaning of an existing required field
- changing the behavior of an existing command or status contract incompatibly
- changing the required behavior of an existing command incompatibly
- changing a binary transfer frame in a non-backward-compatible way

If a breaking maintenance protocol version is ever introduced:

- the Android app shall carry explicit handlers for all production maintenance protocol versions that have shipped
- the app shall route by `maintenance_protocol_version` before issuing maintenance commands
- the app shall not assume that only the latest maintenance protocol version exists in the field

This means the compatibility strategy is:

- prefer additive change
- avoid new maintenance protocol versions whenever possible
- treat maintenance protocol version bumps as rare events, preferably never
- if a new maintenance protocol version becomes unavoidable, keep app support for every shipped production maintenance protocol version

### Scope boundary

- This plan applies to future production firmware only.
- Pre-release firmware that ships before the maintenance channel exists is out of scope for the long-term compatibility promise.
- Wi-Fi OTA shall be removed from active product scope for now rather than retained as an untested secondary path.

## Architecture

### High-level model

- Add a dedicated maintenance GATT service, separate from the existing runtime BLE service.
- Keep this maintenance service inside the normal firmware image.
- Use a split protocol:
  - JSON control/status for small stable maintenance operations
  - binary chunk streaming for firmware transfer
- Use the existing OTA-first partition layout and rollback support already present in firmware.

### Why a separate maintenance service

The current runtime BLE protocol is designed for controller state and commands. It is version-locked in the app today and is expected to evolve with normal product development. That makes it the wrong place for the one compatibility promise that must survive long gaps between controller production and app updates.

The maintenance service isolates that promise:

- stable discovery and identification
- stable update eligibility and status
- stable upload control semantics
- stable error handling for forced updates

This avoids coupling "can I update this controller?" to "can I fully speak its runtime application protocol?"

### Firmware placement

The maintenance service shall live in the normal firmware image, not in:

- a dedicated recovery partition
- a custom bootloader protocol
- a special manual update mode

This keeps the implementation and field recovery model simpler for the first production release while still benefiting from ESP-IDF rollback and dual-slot OTA behavior.

## BLE Maintenance Service

### Exposure model

- The maintenance service shall always be advertised during normal controller advertising.
- A single read-only maintenance information characteristic shall be readable before bonding.
- All update-control and firmware-transfer operations shall require normal OS-managed BLE pairing and bonding.

This gives the app deterministic device identification and routing before the runtime protocol is involved, while still keeping all mutating operations protected behind bonding.

### Security posture

The pre-bond surface shall intentionally reveal very little. It exists only so the app can recognize a BeetMeister controller, determine the maintenance protocol revision, and route into the correct connection flow.

The pre-bond surface shall not expose:

- writable actions
- update triggers
- bond-management controls
- secrets
- internal diagnostics
- information that materially improves attack capability beyond basic product/version identification

### Characteristics

The maintenance service shall contain:

- `maintenance_info`
- `maintenance_control`
- `maintenance_status`
- `maintenance_data`

#### `maintenance_info`

- readable without bond
- minimal device identity and compatibility metadata only

Required fields:

- `product_id`
- `hardware_rev`
- `firmware_version`
- `build_label`
- `maintenance_protocol_version`
- `runtime_protocol_version`
- `update_capable`
- `image_kind`

Purpose:

- detect that the device is a BeetMeister controller
- identify the supported maintenance protocol revision
- identify hardware compatibility for bundled firmware selection
- decide whether the app must force maintenance update mode

Response shape:

```json
{
  "type": "maintenance_info",
  "data": {
    "product_id": "beetmeister",
    "hardware_rev": "rev_a",
    "firmware_version": "0.1.0",
    "build_label": "v0.1.0",
    "maintenance_protocol_version": 1,
    "runtime_protocol_version": 9,
    "update_capable": true,
    "image_kind": "bundled"
  }
}
```

Identity format rules:

- `product_id` shall use a stable ASCII slug and shall be `beetmeister` for this product family
- `hardware_rev` shall use stable ASCII revision tokens such as `rev_a`, `rev_b`, and `rev_c`
- a new `hardware_rev` shall be introduced only for firmware-relevant hardware compatibility changes
- tiny manufacturing changes that do not affect firmware compatibility shall not create a new `hardware_rev`
- `product_id` and `hardware_rev` are machine-facing compatibility identifiers, not marketing labels

`build_label` rules:

- `build_label` is human-facing display and debugging metadata only
- `build_label` shall not be used for update acceptance or compatibility decisions
- bundled release examples: `v0.2.0`, `v0.2.0-beta1`
- custom build examples: `custom-abc1234`, `v0.2.0+abc1234`

#### `maintenance_control`

- bonded-only
- write-with-response
- JSON messages
- requests shall use a top-level `cmd` field

Initial fixed command set:

- `query_status`
- `begin_update`
- `abort_update`
- `finish_update`

`begin_update` shall include:

- target firmware version
- target build label
- total image size
- full-image SHA-256
- target `product_id`
- declared compatible hardware revisions
- runtime protocol version carried by the image, when available
- app asset identifier
- `image_kind`

The metadata sent in `begin_update` shall be derived from metadata embedded inside the firmware image itself.

Rules:

- unknown required fields shall be rejected
- future additions shall prefer optional fields or capability flags
- command names and terminal failure reason strings shall be stable machine-readable identifiers

Request shape:

```json
{
  "cmd": "query_status"
}
```

`begin_update` request shape:

```json
{
  "cmd": "begin_update",
  "data": {
    "firmware_version": "0.2.0",
    "build_label": "v0.2.0",
    "image_size": 812344,
    "image_sha256": "5f8c...64hexchars...",
    "product_id": "beetmeister",
    "hardware_revs": ["rev_a", "rev_b"],
    "runtime_protocol_version": 10,
    "asset_id": "bundled-prod-rev_a-0.2.0",
    "image_kind": "bundled"
  }
}
```

`abort_update` request shape:

```json
{
  "cmd": "abort_update"
}
```

`finish_update` request shape:

```json
{
  "cmd": "finish_update"
}
```

#### `maintenance_status`

- bonded-only
- indicate characteristic
- used for asynchronous progress and terminal results
- responses shall use a top-level `type` field

Stable status states:

- `idle`
- `awaiting_data`
- `transferring`
- `verifying`
- `rebooting`
- `completed`
- `failed`

Required status payload fields:

- `session_id`
- `state`
- `next_offset`
- `bytes_received`
- `total_bytes`
- `failure_reason` when failed

`query_status` scope:

- `query_status` is a synchronous snapshot of the maintenance update session only
- it shall not represent general runtime controller state beyond what is necessary to describe the current update session
- it shall use the same maintenance status states and failure reasons as `maintenance_status`

Purpose:

- let the app resume after link loss
- give the app progress reporting without depending on runtime BLE state
- provide stable terminal error semantics

Response shape:

```json
{
  "type": "maintenance_status",
  "data": {
    "session_id": 42,
    "state": "transferring",
    "next_offset": 65536,
    "bytes_received": 65536,
    "total_bytes": 812344
  }
}
```

Failed response example:

```json
{
  "type": "maintenance_status",
  "data": {
    "session_id": 42,
    "state": "failed",
    "failure_reason": "update_session_expired"
  }
}
```

Initial stable `failure_reason` enum:

- `update_session_expired`
- `update_session_invalidated`
- `update_session_not_found`
- `update_session_mismatch`
- `update_already_active`
- `update_low_battery`
- `update_watering_active`
- `update_runtime_busy`
- `update_invalid_command`
- `update_invalid_metadata`
- `update_invalid_offset`
- `update_invalid_chunk`
- `update_unknown_protocol_version`
- `image_product_mismatch`
- `image_hardware_revision_incompatible`
- `image_sha256_mismatch`
- `image_upload_incomplete`
- `image_slot_too_large`
- `image_metadata_missing`
- `image_metadata_malformed`
- `update_internal_error`

Enum guidance:

- these names are the stable wire-format identifiers
- multiple internal failure points may map to the same stable user-visible reason when they represent the same failure category
- `update_internal_error` shall be used only for genuinely unexpected failures that do not fit one of the stable named categories

#### `maintenance_data`

- bonded-only
- binary chunk stream
- not JSON-framed
- v1 shall use GATT write-with-response

Each chunk shall contain:

- `session_id:u32`
- `offset:u32`
- raw chunk payload bytes

Rules:

- chunks shall be accepted only for the active session
- chunks shall be accepted only at the expected current offset
- offset mismatch shall not corrupt staged OTA data
- the controller shall report the expected `next_offset` through `maintenance_status` or `query_status`
- chunk payload size shall be chosen dynamically based on the negotiated MTU, up to a defined controller maximum
- the protocol-level chunk format shall not assume one fixed BLE packet size
- larger negotiated MTU values should be used to improve v1 transfer speed without changing the reliability model

Transfer complexity guidance:

- v1 prioritizes predictable and reliable transfer behavior over maximum BLE throughput
- negotiated MTU sizing is the preferred first optimization lever
- future transfer-speed improvements may be added later, but they are a medium-to-low product priority compared with getting the reliable v1 updater working well

## Firmware Behavior

### OTA implementation model

Firmware shall implement the maintenance update path using ESP-IDF OTA APIs and the existing OTA partition layout:

- write only to the inactive OTA slot
- never invalidate the currently bootable slot before the replacement image is fully written and validated
- preserve rollback support
- confirm the new image only after normal startup checks succeed

### Session lifecycle

1. App discovers controller and reads `maintenance_info`.
2. App completes OS-level BLE pairing and bonding.
3. App sends `begin_update`.
4. Firmware validates update eligibility and opens a new OTA session.
5. Firmware reports `awaiting_data` with a fresh `session_id` and `next_offset = 0`.
6. App streams binary chunks to `maintenance_data`.
7. Firmware updates progress and expected offset.
8. App sends `finish_update`.
9. Firmware verifies image completeness and SHA-256.
10. Firmware finalizes OTA, marks the candidate image for next boot, reports `rebooting`, and reboots.
11. New image performs standard startup checks and confirms itself only after successful initialization.
12. If the new image fails before confirmation, ESP-IDF rollback returns to the previous confirmed slot.

### Resume rules

The first implementation shall support:

- resume after BLE link drop during the same staged OTA session

The first implementation shall not support:

- durable resume after Android app restart
- durable resume after controller reboot

Practical behavior:

- after BLE link loss, the app reconnects, re-bonds if required, calls `query_status`, and resumes from the reported `next_offset`
- after app restart or controller reboot, the app restarts the upload from zero

### Session expiry behavior

- an interrupted update session shall remain resumable for 15 minutes after BLE disconnect
- during that resumable window, the controller shall keep the staged OTA session open and wait for resume
- while the resumable session remains open, the controller shall allow only read-only status access
- while the resumable session remains open, normal mutating runtime actions shall remain blocked
- these temporary runtime restrictions shall clear immediately when the update session ends or expires

Session invalidation rules:

- if the user disconnects intentionally from the update flow, the controller shall discard staged OTA data immediately
- if a new client begins a new OTA update, the controller shall invalidate any previous staged OTA session immediately
- if an older client reconnects after its staged OTA data was invalidated by a newer update attempt, that client shall restart upload from zero

Session expiry rules:

- when the 15-minute resume window expires, the controller shall discard partial OTA data immediately
- on expiry, the controller shall clear update-session runtime restrictions
- on expiry, the controller shall persist an update-interrupted event
- after expiry, `query_status` shall return `failed` with `failure_reason = session_expired`

### Persisted update lifecycle events

The controller shall persist the following update lifecycle events in the system-event ring:

- `update_started`
- `update_reconnect`
- `update_invalidated`
- `update_interrupted`
- `update_failed`
- `update_completed`

Logging policy:

- `update_reconnect` shall be persisted only when a disconnected session successfully resumes
- failed reconnect attempts and retry loops shall not be persisted individually
- event logging shall stay high-signal so the `sysevents` ring remains useful for diagnostics instead of becoming a retry trace

### Eligibility rules

Firmware shall reject `begin_update` when any of the following is true:

- battery state is below the allowed update threshold
- watering is active
- a runtime action makes update unsafe
- another update session is already active
- the image metadata does not match the supported product or hardware revision

These rules shall use stable failure reasons surfaced through the maintenance channel.

### Runtime gating

While a maintenance update session is active:

- runtime control commands shall be rejected
- scheduler-triggered watering shall not start
- manual watering shall not start
- the controller shall present update-in-progress state consistently

The goal is the same operational safety model already expected for OTA, but now driven by the maintenance service instead of the removed Wi-Fi OTA path.

### Security and validation

Firmware shall:

- require bonding before any update control or upload action
- validate product and hardware compatibility before accepting the image
- validate full-image SHA-256 before activation
- reject malformed chunk headers and oversized writes safely

The maintenance info characteristic remains intentionally read-only and minimal so it does not become a useful attack surface.

### Open-device update policy

BeetMeister shall not require secure boot or vendor-only signed firmware for the maintenance update path.

The update trust model for this product is:

- BLE bonding is required before update actions are allowed
- image integrity must be validated
- hardware and product compatibility must be validated
- users may install their own BeetMeister-compatible firmware builds

Required image metadata for update acceptance:

- firmware version string
- build label
- full image size
- full-image SHA-256
- target `product_id`
- declared compatible hardware revisions
- runtime protocol version, when available in image metadata
- `image_kind`

The app shall send required metadata before upload begins, and the controller shall validate the staged image against the declared metadata before activation.

Embedded metadata policy:

- BeetMeister update metadata shall be embedded inside the firmware image in a BeetMeister-defined metadata block
- the embedded image metadata is the source of truth
- the app shall parse embedded metadata before upload
- the app shall use that parsed metadata to build the install summary and the `begin_update` request
- the controller shall verify that the transmitted metadata matches the embedded image metadata before activation
- `image_kind` shall be embedded and shall use the enum values `bundled` or `custom`

This avoids dependence on a separate sidecar manifest file for either bundled or custom firmware images.

Metadata block design choice:

- v1 shall use a BeetMeister-defined embedded metadata block as the single metadata source of truth
- the metadata block shall carry all required update identity and compatibility fields
- the maintenance update flow shall not depend on mixing those required fields across multiple metadata sources
- the embedded metadata block shall use a TLV-style extensible format in v1
- unknown optional TLV fields shall be ignored safely
- required TLV fields shall remain mandatory for update acceptance

TLV block rules:

- the metadata block shall begin with a fixed BeetMeister metadata header containing:
  - `magic`
  - `metadata_format_version`
  - `total_length`
  - `header_crc32`
- each TLV entry shall contain:
  - `type:u16`
  - `length:u16`
  - `value:length bytes`
- integer TLV values shall use little-endian encoding
- string TLV values shall use UTF-8 without a trailing null byte
- repeated-value fields shall be encoded explicitly as repeated entries only when the field definition says so
- the compatible hardware revision set shall be encoded as repeated TLV entries of the same type
- the metadata block shall use CRC validation for structural integrity in v1
- the maintenance updater shall rely on full-image SHA-256 for image integrity
- v1 shall not add a separate cryptographic hash for the metadata block alone

Initial required TLV field IDs:

- `0x0001` = `product_id` as UTF-8 string
- `0x0002` = `hardware_rev` as UTF-8 string
- `0x0003` = `firmware_version` as UTF-8 string
- `0x0004` = `build_label` as UTF-8 string
- `0x0005` = `maintenance_protocol_version` as `u32`
- `0x0006` = `runtime_protocol_version` as `u32`
- `0x0007` = `image_kind` as UTF-8 string with enum values `bundled` or `custom`
- `0x0008` = `compatible_hardware_rev` as repeated UTF-8 string entries

Required TLV presence rules:

- every update image shall contain exactly one `product_id`
- every update image shall contain exactly one `hardware_rev`
- every update image shall contain exactly one `firmware_version`
- every update image shall contain exactly one `build_label`
- every update image shall contain exactly one `maintenance_protocol_version`
- every update image shall contain exactly one `runtime_protocol_version`
- every update image shall contain exactly one `image_kind`
- every update image shall contain at least one `compatible_hardware_rev`

Validation rules:

- duplicate singleton required fields shall make the metadata invalid
- zero-length required strings shall make the metadata invalid
- unknown required-by-version fields shall make the metadata invalid
- unknown optional fields shall be ignored

Hardware compatibility rules:

- `product_id` shall require an exact match
- the image may declare compatibility with multiple hardware revisions
- the controller shall accept the image only if its own hardware revision is included in that declared compatibility set
- compatible hardware revisions shall be represented as an explicit list, not a range model

Downgrade policy:

- downgrades are always allowed
- the app shall identify downgrade actions clearly in the install summary
- downgrade allowance does not bypass integrity or compatibility validation

Runtime protocol mismatch policy:

- runtime protocol mismatch is an app-side warning only
- the controller shall not reject an otherwise valid image only because its runtime protocol may not be fully supported by the current app
- the app shall warn clearly before install when this mismatch is detected
- the user may explicitly override that warning and continue with installation

This means the maintenance updater protects against:

- corrupted transfers
- incomplete uploads
- wrong-target images
- slot-size overflow

It does not protect against:

- a bonded updater intentionally installing modified firmware
- publisher authenticity spoofing through unsigned but technically compatible images
- installation of a custom image whose runtime protocol is not fully supported by the current app, if the user explicitly overrides the warning

That tradeoff is intentional so the controller remains open to user-provided builds and is not locked down through secure-boot-style ownership restrictions.

## Android App Behavior

### Discovery and routing

The app shall scan for the maintenance service first.

On discovering a BeetMeister controller:

1. read `maintenance_info`
2. determine product and hardware compatibility
3. determine maintenance protocol compatibility
4. determine whether runtime protocol is supported
5. decide whether to continue to normal runtime flow or force maintenance update flow

### Forced-update flow

After successful pairing:

- if the runtime protocol is supported and firmware is current enough, proceed to the normal runtime BLE flow
- if the runtime protocol is unsupported or below the app's minimum supported runtime baseline, force the guided maintenance update flow

The forced-update flow shall:

- block entry into normal controller controls
- explain that the controller firmware must be updated before normal use
- keep the user in a single guided path
- present clear progress and reconnect handling

### Custom image handling

The app shall distinguish bundled and custom images in the update flow and in normal controller presentation.

Rules:

- bundled images are the default recommended path
- custom images are allowed through an explicit secondary selection path
- custom images shall be labeled clearly before installation
- custom-installed firmware shall be labeled clearly in the settings page near the firmware version
- the app shall warn when a selected custom image reports a runtime protocol version that may not be fully supported after installation
- the warning may be overridden explicitly by the user
- if the source was manual file selection, the app shall present the image as `custom` in user-facing UI even if the embedded metadata claims `bundled`

### Bundled firmware model

The Android app shall bundle the production firmware image and metadata for each supported hardware revision.

This bundled image path is the default user flow.

The app shall select the asset using:

- `product_id`
- `hardware_rev`

The app shall also support a secondary custom-image path where the user may select a firmware image manually from the phone.

The custom-image path shall:

- be clearly presented as a secondary advanced option
- label the selected image as a custom build
- show stronger warnings when compatibility or protocol expectations differ from the app-bundled release
- still use the same controller-side compatibility and integrity checks as the bundled-image flow

The user shall not be asked to:

- browse for a firmware file
- enter a URL
- configure a hotspot
- run a local server

for the default update path.

### Firmware selection summary

After the app has selected or the user has chosen a firmware image, the app shall show a confirmation summary before upload begins.

The summary shall show at least:

- whether the image is bundled or custom
- firmware version
- build label
- target `product_id`
- declared compatible hardware revisions
- runtime protocol version carried by the image, if available
- whether the action is an upgrade, reinstall, or downgrade

If the selected image reports a runtime protocol version that the current app does not fully support for normal runtime use:

- the app shall warn the user clearly
- the app shall still allow the user to proceed after an explicit confirmation override

### Resume behavior

If BLE disconnects during upload:

- reconnect
- verify bond state
- call `query_status`
- continue from `next_offset`

If reconnect or resume fails:

- the app shall retry reconnect-and-resume up to 3 times
- if resume still cannot continue reliably, the app shall report failure clearly to the user
- if the controller reports an expired or invalidated session, the app shall restart upload from zero

If the app process is restarted or the controller reboots before completion:

- discard the staged client-side upload state
- restart the upload from zero

### Device wake behavior during update

During an active firmware upload, the Android app shall acquire the necessary wake lock behavior so the phone does not go to sleep and interrupt the update.

Rules:

- the app shall hold a wake lock for the active upload session
- the wake lock shall be acquired before firmware transfer begins
- the wake lock shall be released on successful completion, intentional abort, terminal failure, or session expiry
- the update flow shall not rely on the user keeping the screen awake manually

### UI and messaging

The user-facing flow shall distinguish:

- pairing problems
- controller out of range or offline
- firmware too old for the runtime protocol
- update rejected because the controller is busy or battery-limited
- upload interrupted and resumed
- upload failed and must restart

This distinction is one of the main reasons for the dedicated maintenance compatibility layer.

## Documentation Changes

### New planning document

Create this file as the implementation plan:

- `docs/planning/ble-maintenance-update-plan.md`

### Normative docs to update during implementation

- `docs/specifications/constraints-and-assumptions.md`
- `docs/specifications/requirements-baseline.md`
- `docs/specifications/ble-and-android-app.md`
- `docs/specifications/ota-and-release.md`
- `docs/specifications/verification-and-acceptance.md`
- `docs/planning/milestone-roadmap.md`
- other architecture/spec docs where Wi-Fi OTA is still described as the active product path

### Documentation intent

The docs shall clearly distinguish:

- runtime BLE protocol
- maintenance BLE protocol
- normal controller control flow
- forced maintenance update flow

The docs shall also explicitly state that Wi-Fi OTA is removed from the active product plan for now because an untested or user-hostile secondary path is worse than a missing feature.

## Implementation Stages

### Stage 1: Documentation And Protocol Baseline

Goal:

- make the BLE maintenance updater the approved architecture before implementation starts

Scope:

- update planning and normative docs
- freeze the maintenance service, maintenance message shapes, TLV metadata rules, failure reasons, and event policy
- remove Wi-Fi OTA from active product scope in the documentation

Exit condition:

- docs are internally consistent enough that firmware and app work can proceed without re-deciding protocol shape

### Stage 2: Firmware Metadata And Discovery

Goal:

- make the controller self-describing through the maintenance channel before any real update transfer exists

Scope:

- add BeetMeister TLV metadata generation to the firmware image
- add firmware-side metadata parsing helpers and validation helpers
- add the maintenance GATT service skeleton
- add pre-bond `maintenance_info`
- add bonded `query_status` support with idle-state responses

Exit condition:

- the app or test tooling can discover the maintenance service, read maintenance info, and see stable maintenance protocol identity on real hardware

### Stage 3: Firmware Update Session Core

Goal:

- implement the controller-side update session state machine and safety gating without full Android UI dependency

Scope:

- add bonded maintenance control handling for `begin_update`, `abort_update`, and `finish_update`
- add `maintenance_status`
- add session creation, `session_id`, offset tracking, expiry, invalidation, and reconnect rules
- add runtime gating during active update sessions
- add persisted update lifecycle events
- add firmware host tests for metadata, session handling, rejection reasons, and status reporting

Exit condition:

- the controller can start and manage an update session correctly, even before the Android app performs a full OTA transfer

### Stage 4: Firmware OTA Data Path

Goal:

- make the controller able to accept firmware bytes and switch slots safely

Scope:

- add `maintenance_data` write-with-response handling
- implement MTU-aware chunk sizing constraints and offset validation
- connect the update session to ESP-IDF OTA begin/write/end flow
- validate full-image SHA-256 and embedded metadata before activation
- preserve rollback behavior and first-boot confirmation rules

Exit condition:

- a test client can upload a valid firmware image over BLE and the controller can reboot into the candidate image safely

### Stage 5: Android Discovery And Forced-Update Routing

Goal:

- make the app recognize maintenance-capable controllers and route users correctly before full upload UX lands

Scope:

- scan for the maintenance service
- read `maintenance_info`
- decide between normal runtime flow and forced-update flow
- surface outdated-runtime and pairing states clearly
- add custom-versus-bundled labeling logic based on firmware metadata and selection source

Exit condition:

- the app can connect to a controller, identify whether maintenance update is required, and route the user into the correct flow

### Stage 6: Android Firmware Selection And Upload UX

Goal:

- deliver the end-user updater flow

Scope:

- bundle firmware assets in the app
- add manual custom-image selection as a secondary path
- parse embedded TLV metadata on the phone
- show the pre-install summary, warnings, downgrade state, and build label
- perform BLE upload with wake lock held
- implement reconnect-and-resume with up to 3 retries

Exit condition:

- a non-technical user can update a controller from the app using the bundled image path without Wi-Fi

### Stage 7: Cleanup, Removal, And Release Hardening

Goal:

- remove stale assumptions and close the release-quality gaps

Scope:

- remove stale runtime `start_ota` references and unsupported Wi-Fi OTA assumptions from code and docs
- complete target, Android, and security validation
- verify event logging behavior is useful without excessive ring churn
- confirm custom image flow, forced-update flow, and rollback behavior on target hardware

Exit condition:

- the BLE maintenance updater is release-ready and the older OTA direction is no longer half-present in the product

### Stage 8: Real-Device End-To-End Validation

Goal:

- validate the updater against a real BeetMeister controller and a real Android phone before release

Scope:

- run end-to-end update validation with the controller physically connected and advertising
- run the Android app on a real phone connected through ADB where possible
- capture controller logs and Android logs during the update flow
- verify the app and controller behavior under real BLE conditions, not only host-side or simulated tests

Required real-device scenarios:

- normal successful update using the bundled firmware path
- user intentionally stops or aborts the update
- Android app process crash during upload
- Bluetooth disconnect during upload followed by successful resume
- Bluetooth disconnect during upload followed by session expiry
- controller invalidation of an older session by a newer update attempt
- low-battery rejection
- busy or watering-active rejection
- custom image selection flow
- runtime protocol mismatch warning and explicit user override
- rollback on failed first boot of the candidate image

Validation notes:

- use ADB-driven automation where available
- allow manual interaction where physical pairing dialogs or device-specific BLE behavior cannot be fully automated
- confirm wake-lock behavior during active upload
- confirm that persistent update lifecycle events are useful without excessive system-event ring churn

Exit condition:

- the updater passes the required real-device scenarios on actual hardware with logs captured for review

#### Stage 8.1: Dedicated Firmware Update Flow And Maintenance-State Split

Goal:

- move firmware update out of the regular Settings screen into a dedicated maintenance flow that owns OTA-specific UI, navigation, and connection handling

Scope:

- add a dedicated firmware update screen that is separate from the normal runtime screens
- enter that screen manually from Settings for optional updates
- auto-enter that screen when the controller is in forced maintenance mode because normal runtime protocol is unavailable or incompatible
- hide normal app navigation while the dedicated firmware update screen is active
- allow the user to leave the screen as expected, but keep an active update running through a sticky foreground-capable Android service with notification support
- provide an in-app return entry when an update is still active outside the screen
- remove the active firmware update controls from the regular Settings screen once the dedicated flow exists
- split maintenance-specific connection state handling from normal runtime connection handling so OTA reconnect and resume no longer rely on the main-screen connection assumptions
- fix the app-side timeout behavior so an active maintenance reconnect or resumed upload is treated as valid progress instead of a failed connection attempt

Required implementation outcomes:

- the app has one dedicated firmware update flow for both optional and forced maintenance cases
- the OTA flow can continue safely when the app screen is left or backgrounded
- reconnect-after-drop resumes the active OTA session without the app timing itself out while maintenance progress is still happening
- successful post-update reconnect returns to normal runtime UI only when the installed firmware is compatible with the app runtime protocol
- incompatible post-update reconnect remains in the maintenance or update-required path instead of falling back to normal runtime control
- the current real-device baseline is preserved: `begin_update` succeeds, sustained OTA writes succeed, and reconnect/resume after a BLE drop continues the same session
- the known remaining blocker from `artifacts/stage8/live-20260620-114205-e2e-final` is removed: the app must no longer kill a healthy resumed OTA session with its own connection-timeout logic while the connection phase is `MaintenanceRequired`

Validation notes:

- verify the dedicated screen works for both a runtime-compatible controller and a forced-maintenance controller
- verify leaving the screen during upload does not abort the update and that the user can return to the active flow
- verify the known real-device failure from `artifacts/stage8/live-20260620-114205-e2e-final` is resolved by preventing maintenance-progress reconnects from being killed by the normal connection timeout logic

Testing focus for Stage 8.1:

- validate the dedicated firmware update flow on a real Android phone connected through ADB and a real BeetMeister controller connected on the bench
- verify optional update entry from Settings opens the dedicated firmware update screen instead of keeping the user in the regular Settings page
- verify forced-maintenance connection auto-enters the dedicated firmware update screen and does not expose normal runtime control while the controller is outdated or incompatible
- verify leaving the dedicated screen during an active upload does not abort the update and that the app exposes a clear way to return to the active update flow
- verify the sticky foreground-capable service and notification behavior while the app is backgrounded and then reopened
- verify BLE disconnect during upload resumes correctly inside the dedicated maintenance flow and does not restart from zero when the controller still reports a resumable session
- verify the known timeout failure from `artifacts/stage8/live-20260620-114205-e2e-final` is resolved: the app must not fire the normal connection timeout while maintenance reconnect or resumed upload is making forward progress
- verify successful update completion returns to normal runtime UI only when the installed firmware is app-compatible
- verify successful installation of an app-incompatible firmware returns to a safe maintenance or update-required path instead of pretending normal control is available
- capture Android logcat, controller serial logs, and user-visible UI evidence for each of these scenarios

Exit evidence for Stage 8.1 testing:

- at least one real phone and the attached controller complete the dedicated-screen OTA flow end to end
- reconnect-and-resume after a real BLE drop is demonstrated on hardware without the app-side maintenance timeout bug recurring
- logs and UI captures are saved under the Stage 8 artifact workflow for later review

Exit condition:

- firmware update is no longer hosted inside the regular Settings page, and the app can complete or actively resume BLE OTA without the normal connection timeout logic terminating a healthy maintenance session

## Test Plan

### Firmware host tests

- `maintenance_info` formatting and parsing
- `begin_update` validation and rejection reasons
- session creation and `session_id` behavior
- in-order chunk acceptance
- offset mismatch rejection without staged-image corruption
- `query_status` resume behavior after link loss
- `finish_update` failure on incomplete transfer
- `finish_update` failure on SHA mismatch

### Firmware target tests

- fresh production device updates successfully from the latest app using BLE only
- runtime protocol mismatch forces maintenance update path instead of generic connection failure
- link-drop resume works inside a live session
- low-battery rejection is surfaced clearly
- watering-active rejection is surfaced clearly
- successful update preserves configuration, calibration, runtime snapshots, and event history
- failed first boot rolls back to the previous confirmed image

### Android tests

- maintenance discovery and routing logic
- forced-update state when runtime protocol is unsupported
- firmware asset selection by product and hardware revision
- resume-after-disconnect behavior
- restart-from-zero behavior after app restart
- user messaging for pairing failure, unsupported runtime protocol, rejected update, resume, and terminal failure

### Security tests

- unbonded clients can read only minimal maintenance info
- unbonded clients cannot start, abort, finish, or upload updates
- malformed control payloads are rejected safely
- malformed chunk headers and oversized writes are rejected safely

## Open Design Defaults Chosen Here

- Future production only is the supported baseline.
- Wi-Fi OTA is removed from active scope instead of kept as an untested secondary path.
- The maintenance service is always advertised.
- Pre-bond access is limited to one minimal read-only info characteristic.
- Maintenance control uses JSON.
- Maintenance data transfer uses binary chunks.
- Resume is supported after BLE link drop within the same session.
- Resume across app restart or controller reboot is not required in v1.
- The app bundles firmware assets instead of downloading them from the network or asking the user to pick files.

## Risks To Manage During Implementation

- Android BLE behavior varies across vendors, so resume and reconnect handling must be validated on more than one mainstream device.
- The open-device OTA trust model must stay explicit in docs and UI so users understand that bonded update authority allows installation of custom compatible firmware.
- The maintenance service must not become another version-locked protocol in practice; additive evolution rules need to be enforced in code review and docs.
- Runtime and maintenance state interactions must be explicit so the controller never starts watering during an active update session.
