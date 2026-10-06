# APK archive previews

The APK screen uses MediaStore candidates. It previously parsed every filename before
showing the scan result and always rendered the same generic installation symbol.

The list now publishes indexed files immediately and loads archive metadata only for
composed rows. A 64-entry artwork cache and two-reader semaphore bound retained images
and concurrent parsing. Scan rows retain text metadata without bitmaps. A replacement
scan invalidates in-flight cache writes; a cancelled composition cannot publish its reply.
Pending metadata stays visible during metadata filtering but cannot be bulk-selected as
if its version were already known. Filename matches remain usable without archive metadata.

Names, versions and icons come from that archive's resources. Installed package metadata
is used only for version comparison. No APK is installed or executed. A pinned read-only
descriptor and a unique private path prevent path replacement and resource-cache aliasing.
Scoped-storage fallback accepts only a matching MediaStore row and validated regular-file FD.
The temporary alias, FD and archive asset manager close after success, failure or cancellation.

Limits include 256 MiB input, bounded ZIP/resource structure, 192px icon output and a
three-second cooperative budget. Android synchronous parsing is not safely interruptible;
the budget is checked between stages, and a cancelled result is discarded after cleanup.
Unsupported bundles, unreadable archives and resource limits keep the filename and an
explicit default icon, rather than borrowing an installed application's icon.

Regression coverage includes same-package archives with different resources, cancellation,
resource budgets, LRU eviction, concurrency, lazy rendering, filtered selection, long names,
dark large text and scrollable details. The debug-only device probe parses this repository's
own APK twice under an ordinary App UID and saves its actual decoded icon. It also prewarms
installed resources, then parses two generated resource-only APKs with the same package and
resource IDs but different labels, versions and icon pixels. The Root cleanup
probe and formal/30006/30007/30008 upgrade checks remain separate verification gates.

App 30009 retains the delivered 30008 Root/AIDL, module scripts, lease and configuration
schemas. Runtime diagnostics recognize that exact pair as compatible without labelling
their version numbers equal. Unknown versions still warn. Existing 30008 module users can
update the APK alone. A newly packaged module carries build 30009 metadata and the new APK,
but its backend payload remains byte-for-byte equivalent to the delivered 30008 engine.
