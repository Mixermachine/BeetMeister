Project documentation in this repository is normative unless a file explicitly says otherwise.
The documents below are the implementation source of truth for BeetMeister.

## Reading order

1. `specifications/requirements-baseline.md`
2. `architecture/project-overview.md`
3. `specifications/constraints-and-assumptions.md`
4. `architecture/system-architecture.md`
5. `specifications/firmware-behavior.md`
6. `specifications/storage-and-partitions.md`
7. `../hardware/wiring/controller-and-electrical-specification.md`
8. `../integrations/home-assistant/mqtt-specification.md`
9. `specifications/ble-and-android-app.md`
10. `specifications/ota-and-release.md`
11. `specifications/verification-and-acceptance.md`
12. `planning/milestone-roadmap.md`
13. `planning/open-risks.md`

## Directory roles

- `architecture`: system purpose, glossary, boundaries, and runtime model
- `specifications`: normative controller behavior, interfaces, storage, OTA, and verification
- `planning`: milestones and unresolved risks only

## Source-of-truth rules

- Terms defined in `architecture/project-overview.md` shall be used consistently everywhere else.
- New externally visible states or commands shall not be introduced outside the central specifications.
- Hardware and integration documents inherit controller state names, thresholds, and data formats from the central specifications.
