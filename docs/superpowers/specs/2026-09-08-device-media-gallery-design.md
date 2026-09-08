# Device media gallery design

## Goal

On first launch, Lik requests access to photos on the target Galaxy Fold and immediately shows the granted device library. Android 16 and 17 are supported.

## Data and permission flow

`MainActivity` requests `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED` with the Android runtime permission contract. A lifecycle ViewModel queries the permitted `MediaStore.Images` rows on a worker thread, ordered by date added and ID. When access is denied or limited, the private Google import library remains usable and the screen shows an explicit action to grant or expand access.

A `GalleryPhoto` represents either a private Lik copy or a MediaStore content URI. A `PhotoCatalog` combines both sources into one ordered timeline. IDs are namespaced for MediaStore rows and preserve existing SHA-256 IDs for private copies.

## Gallery and viewer

`PhotoAdapter` decodes both files and content URIs off the main thread. `PhotoViewerActivity` receives only a catalog ID, reloads the current catalog under the active permission, and opens either source. MediaStore metadata supplies format, dimensions, size, and added time where available.

Private Google copies retain selection and permanent deletion from Lik. Device photos are view-only; long press explains that originals are not copied or removed by Lik. The generic system Photo Picker is removed because it mixes local and cloud sources. Google import is explicit: Google Photos → Share → Lik.

## Verification

Instrumentation inserts a test image into MediaStore, grants `READ_MEDIA_IMAGES`, launches the gallery, and verifies that the row is visible and opens. Existing import, gallery, viewer, lifecycle, permission-cancel, and icon tests continue to pass on API 37. The debug APK is then installed and launched on the target Fold running API 36, where the user grants access and validates the real library.
