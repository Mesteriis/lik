# Persistent media catalog

Task 5 implements the Room 2.8.4 metadata catalog in the single `:app` module. KSP 2.3.9 generates the DAO implementation; the version 1 schema is checked into `app/schemas/`. This is original Lik code using official Android/Room APIs, with no code copied from gallery references. Room and KSP are Apache-2.0 dependencies, unrelated to model licenses.

## Ownership and identity

`PhotoStore` remains the owner of committed private files, validation, import, and deletion. Room stores only an opaque private file ID and resolves it through `PhotoStore.fileFor`; no image is moved or duplicated by catalog migration. Device originals remain in MediaStore and are referenced through volume-specific content URIs.

Imported internal IDs remain the existing lowercase SHA-256 values. Device IDs are prefixed hashes of a length-framed `(volume, MediaStore version, row ID, generation added)` tuple. Content revision uses `GENERATION_MODIFIED` and does not change object identity. A reused row in another volume, after a MediaStore version reset, or with another creation generation receives a distinct ID. Old records remain inaccessible, preserving metadata for future relationships. Display name, timestamps, and cross-source content similarity never merge objects.

The initial scan queries each concrete external volume and checks its version again after reading. A null cursor, revoked access, or version change does not publish an ambiguous device inventory. Existing device metadata remains inaccessible; private imports remain available. This is a full foreground scan; batched incremental scanning, cancellation, observers, and paging remain Task 6.

## Record contract

`MediaRecord` stores internal ID, source key, volume/version/generation identity, URI or private file ID, nullable name/MIME/dimensions/size, capture/add/modify timestamps, date source and optional original UTC offset, bucket/folder metadata, content revision, availability, and last-seen time. Timestamps are epoch milliseconds. Unknown values stay null in storage; the legacy gallery model still receives its existing zero/empty display defaults. Offsets are evidence from metadata and are never inferred from the current time zone.

Availability is `AVAILABLE`, `INACCESSIBLE`, or `MISSING`. An absent device row is only inaccessible because limited visibility cannot prove deletion. A missing private file can be marked missing from PhotoStore's authoritative inventory. Metadata is retained, and reappearance restores availability.

## Imported-file migration and recovery

There was no previous Room database, so version 1 needs no SQL upgrade migration. `ImportedCatalogMigration` is the file-to-catalog migration, not a destructive database migration. It enumerates only committed `<sha256>.image` files and reads file size/mtime; it does not open or decode image content. Unknown original names, MIME, dimensions, and capture dates stay unknown. Interrupted `.part` and unrelated files are excluded.

The repository holds PhotoStore's writer monitor while applying the inventory in one Room transaction. Insert-if-absent preserves existing identities and enriched metadata; marking seen updates availability and last-seen time. A failed transaction rolls back the inventory. No completion flag is needed: every refresh safely retries, including a crash after file commit but before catalog registration. A failed directory listing throws instead of being treated as an empty library. The files are never changed by a catalog transaction.

The gallery load boundary catches imported-inventory I/O and access failures after transaction rollback. It returns previously available catalog rows with an explicit imported-source error, skips file enrichment for that failed inventory, and lets the screen finish scanning. The notice explains that some cached photos may be temporarily unavailable. A subsequent successful refresh clears the error. Device-source errors remain separate.

Optional EXIF capture-date enrichment runs after migration and catches unreadable metadata. Existing capture-date ordering is preserved. Negative EXIF caching and enrichment by content revision remain Task 6. The database builder has no destructive fallback; future schema versions require explicit migrations. App backup rules already exclude the database and private files.

## Verification

JVM tests cover source separation, row reuse, framing collisions, invalid IDs, unknown metadata, DST overlap, invalid inventories, and partial migration retries without decoding or changing files. Android Room tests use real SQLite for metadata updates, lost visibility, import restoration, a trigger-induced write failure followed by retry, and persistence across reopen. Existing gallery/Share/viewer/lifecycle tests exercise the catalog integration on API 37.
