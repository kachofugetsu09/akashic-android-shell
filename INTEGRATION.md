# Phone control integration

This branch is the buildable integration entry for the phone reliability drafts. It does not publish a release or deploy a server. Shell 0.5.0 requires the paired phone-use snapshot-reference protocol; the evaluated plugin is `b5793174e061eee631c41fe2849daf88ed48f8fd`.

## Source composition

- PR7/9/11/15: isolated display power, connection ownership, one active effect, node/display and Intent checks, MIME propagation.
- PR8/10/13/14: durable app-owned ADB identity, foreground-service recovery, one helper startup, atomic Binder handoff.
- PR16/17/21/23/24: snapshot references, reader/cache ownership, honest app metadata, producer frame watermark, exact task handoff/resume.
- PR18/19/20: honest audio state, confirmed text fast path, full-value readback before reporting success.
- PR26/27: logical display geometry and screenshots, whole-display finish without package-management claims.

The PR23/24 combination advances the existing frame watermark immediately before each real exact-task move. Query/version checks and saving the handed task ID happen first. Cancelling a wait without a handed task does not advance the watermark. This is a required combination change beyond either individual draft.

The Binder fault probe now takes an existing positive display ID only for read-only geometry, plus a local ImageReader for frame-watermark preconditions. It still uses a fault Binder for all task effects and preserves the twelve unknown-result/lifecycle assertions. It does not claim real task migration.

## Validation boundaries

The device owner keeps timestamped raw evidence outside the repository. Build artifacts must be matched to the exact final integration commit and SHA-256; earlier APK results remain attached to their earlier source heads.

On Pixel 7 / Android 16, earlier stages established physical USB-free LAN operation, fresh input during forced Doze with the main panel off, genuine app-owned TLS pairing and repeated app-process recovery, old connection/snapshot rejection, exact input and independent persisted text, and native reader/producer-frame checks. Individual results are observations, not arbitrary-app reliability guarantees.

Geometry candidate `db2873c` passed six actual screenshot/color/touch stages: natural size, 720×1280 override, an app round trip under override, size reset, 180° rotation, and return to 0°. PNG decoding, four asymmetric color centers, and independent touch coordinates agreed. Default scroll under override changed the independent offset from 0 to 904. Settings were restored on that owned display.

The real helper selected two distinct same-package fixture tasks on its own displays, moved one to the main display, then moved only the recorded task back while preserving the other. Host WMS captures at five gates verified exact IDs and display locations. The probe then returned both fixture tasks and released only its own displays; original running tasks remained on their original displays. This controlled test is separate from user-operated handoff and the fault-Binder tests.

## Remaining limits

- Task enumeration and launch/move are not atomic. TaskInfo does not expose all historical task identity fields; known external-task guards do not prove absolute isolation under every race or Android implementation.
- Audio focus is shared on this ordinary virtual-display path. `audio_muted` is false. Silent focus fixtures observed cross-display loss and regain; this does not establish media isolation.
- Custom editors may reject ACTION_SET_TEXT. Masked, transformed, delayed, or truncated values can report unconfirmed after a partial effect. The tool does not retry input or modify the user's clipboard.
- Frame watermarks reject older producer frames; they do not prove an application's final render is complete or make the accessibility tree and screenshot atomic.
- Finish releases the whole owned display. It does not manage packages, remove recents entries, kill processes, or guarantee stopped audio.
- Physical panel samples cannot exclude flashes between samples. Previously observed SystemUI quick-pickup pulses remain recorded as all-off acceptance failures even when the trigger was identified.
- The release build variant uses this repository's local demonstration signing key. A local release/R8 APK is not a published GitHub release or a production deployment.
