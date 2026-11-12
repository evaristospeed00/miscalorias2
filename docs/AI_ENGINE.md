# miscalorIAs AI engine: audit and implementation status

## Project audit

The application is a native Kotlin/Jetpack Compose Android app (`minSdk 26`, `targetSdk 36`). `MainActivity` owns the current photo-selection and diary flow. `FoodImageLabeler` uses ML Kit's generic on-device image labels; it is not a food detector or segmentation model. `FoodDatabase` is a private SQLite catalog, and `NutritionEstimator` scales its four stored nutrients from a user-entered gram amount. There is no backend, Internet access, depth capture, food-instance masks, sourced nutrient-record identifiers, or measured evaluation set in the original app.

The starter catalog values are generic references, not verified FoodData Central records. They should not be presented as precise recipe or restaurant-food measurements.

## Local-first architecture

The selected architecture preserves the app's local-only photo handling:

1. **Capture:** optional ARCore `ArDepthCaptureActivity` provides an AR camera preview, RGB JPEG, same-frame ARCore depth when available, depth/RGB timestamps, camera image intrinsics, and camera pose. ARCore's automatic depth is enabled only when supported at runtime. Unsupported devices can still capture RGB; callers must retain the app's regular camera/gallery fallback.
2. **Recognition:** the existing ML Kit image labels remain suggestions only. They are not food-specific segmentation, ingredient recognition, or mass estimates.
3. **Segmentation:** no suitable, licensed, trained food-instance model is shipped. A mask provider is therefore an explicit missing component; the geometry API accepts a mask but does not fabricate one.
4. **Geometry:** `DepthGeometry` integrates metric depth above a caller-provided support-plane distance, projects each valid pixel using camera focal lengths, and converts volume to mass only using an explicitly supplied food density. The current estimator assumes an approximately fronto-parallel support plane and an already aligned mask/depth map. It returns no estimate when valid masked-depth coverage is too low.
5. **Nutrition:** `NutritionEngine` scales per-100-g nutrient records deterministically, retains the record source/ID in each result, supports exact normalized aliases, and rejects invalid quantities. No arbitrary semantic/fuzzy match is made; for example, generic cooked beef is not an alias for carne asada.
6. **Correction:** the existing diary entry form allows manual gram entry and already recalculates its displayed nutrition from the selected local food. The new exact-precision engine is independent and awaits a sourced catalog adapter and camera-flow integration.

Photos and depth are written to the app's private cache by the AR capture activity. No network permission, cloud endpoint, API key, or third-party image upload is introduced. Cache payloads should be deleted by the caller after analysis is integrated.

## Current implementation versus blockers

Implemented in this change:

- Optional ARCore dependency and manifest declaration; camera hardware and ARCore remain optional for Play distribution.
- Runtime ARCore install/camera permission handling, depth-support check, metric depth acquisition, and an RGB/depth/calibration result contract.
- Raw depth serialization as unsigned millimeter samples and conversion back to meters.
- Deterministic masked-depth volume integration and density-based mass conversion with focused unit tests.
- Traceable nutrient-record types, exact alias resolution, and proportional nutrient calculation with focused unit tests.

Not yet end-to-end:

- ARCore capture is connected to the existing camera entry point. When ARCore is unsupported, denied, or cannot start, the flow falls back to the original system-camera preview. Captured RGB/depth files are discarded after the on-device label suggestion completes.
- The depth image is not yet used to calculate grams: that requires the missing food mask, aligned depth coordinates, and food-specific density. Do not treat captured depth alone as a portion estimate.
- Food instance segmentation and food-specific recognition need an appropriately licensed model trained/evaluated for the intended food classes. FoodSeg103 has 103 classes, not universal food coverage; its model/dataset licensing and redistribution terms must be checked before adoption.
- No local USDA/Open Food Facts/Mexican food database snapshot is bundled. FDC's API requires a protected API key, which must not be put in an APK. A local-only release needs a reviewed, versioned data export or an owner-operated proxy; current starter values are not source-verified.
- Food density values must be sourced or measured by food/preparation before automated gram estimates are enabled. The geometry API intentionally requires density and support-plane depth from the caller.
- There is no independently weighed photo dataset or device evaluation, so classification, mask IoU, gram/calorie MAE, MAPE, and latency are not measured or claimed.
- Device testing is required for ARCore installation, permission denial, unavailable/stale depth, orientation alignment, and actual RGB/depth pixel registration.

## Research and reuse/licensing notes

- [DietVision Flutter](https://github.com/leopoldmaillard/diet-vision-flutter) uses semantic food groups, two camera angles, a reference marker, and user-adjustable food/thickness. Its repository declares MIT. Its technical report describes image-based volume estimation and feedback. No source was copied.
- [FoodLens (YOLO11-seg)](https://github.com/porpita1342/FoodLens) reports FoodSeg103-based instance masks and remote GPU inference, while explicitly listing volume/RGB-D processing as unresolved challenges. No `LICENSE` file is exposed at the repository root; do not reuse code or weights without permission and license verification.
- [FoodLens (SegFormer/Depth Anything V2)](https://github.com/AbSamrah/FoodLens) describes segmentation, monocular depth, CLIP and a remote service. Monocular depth is not metric scale. No `LICENSE` file is exposed at the repository root; do not reuse code or weights without permission and license verification.
- [Recalo](https://github.com/laiso/recalo) is Kotlin/Compose and declares GPL-3.0. Its BYOK vision flow does not provide pixel segmentation or calibrated volume. No source was copied.
- [ByteBite](https://github.com/Ismail-Abu/ByteBite) reports single-image Nutrition5k regression and an Android LiteRT app. Its reported dataset-specific regression is not multi-food segmentation or a general per-ingredient database. No `LICENSE` file is exposed at the repository root; do not reuse code or weights without permission and license verification.
- [ARCore Depth API](https://developers.google.com/ar/develop/java/depth/developer-guide) obtains depth from motion and may fuse ToF hardware; a ToF sensor is not required. Depth mode must be enabled and checked at runtime. Raw depth can be sparse; full depth is denser but smoothed.
- [USDA FoodData Central API](https://fdc.nal.usda.gov/api-guide/) requires a data.gov key and identifies its data as CC0/public domain. Protect a production key server-side; this app currently has no backend.

## Setup and costs

The Android build uses the ARCore SDK and Google Play Services for AR; users need a compatible device and Play Services for AR installed/updated to use depth. AR is optional, so unsupported devices must keep the RGB/manual-portion fallback. No AI-provider credentials or GPU service costs are required by the current local-only implementation. A hosted segmentation service, if later approved, would require a backend, secure credentials, explicit photo-upload/privacy disclosures, and GPU operating budget.

## Validation

Run `.\gradlew.bat testDebugUnitTest` from the repository root for the nutrition and geometry unit tests. Tests cover proportional nutrients, alias non-guessing, metric depth integration, food density input, and insufficient depth coverage. They do not establish real-world model accuracy. Run device/instrumentation checks only after the camera entry point is integrated and the parallel UI redesign is stable.
