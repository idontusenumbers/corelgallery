# Corel Gallery — status notes

Last updated 2026-11-17. Development notes: design decisions, measurements and known limits.

## What it is / how to work on it
- Zero-dependency Java 17+/Swing app, sources in `src/main/java/gallery/` (~5,900 lines, packages below), resources
  in `src/main/resources/gallery/catalog/` (`folder-names.tsv`, loaded relative to `Catalog`). Gradle 9.5.1 wrapper, configuration cache on
  (`gradle.properties`). Build: `./gradlew build` -> `build/libs/CorelGallery.jar`; run
  `java -jar build/libs/CorelGallery.jar` or `./gradlew run`. No unit tests (`test` is NO-SOURCE).
- Testing: there are no unit tests. Earlier work used Xvfb + `java.awt.Robot` for screenshots and key/mouse tests,
  and small probe programs compiled against `build/classes/java/main` that launch `Main` on a full catalog (all 14
  discs, 128,103 items in the default vector view), scroll the grid from a Swing timer and measure frame gaps, GC pauses
  (GC MXBean), per-thread allocation (ThreadMXBean) and JFR allocation samples (drop each thread's first sample: it
  carries all allocation since before the recording). `-Dsun.java2d.uiScale=2` simulates a Retina screen. The probes are
  not in the repo; recreate as needed. Scrolling from a timer is not the same as a real mouse drag (on macOS that goes
  through the main thread).
- Packages (split 2026-11-17; dependencies only point down this list, no cycles):
  - `gallery.source`: `Source` (+ reusable `Source.Buffer`), `IsoSource`, `FolderSource` (readers).
  - `gallery.drawing`: `Drawing`, `VectorDrawing`, `AnimatedImage` (the format-neutral model).
  - `gallery.codec`: `CcxDecoder`, `CompactDrawing` (thumbnail form of vectors), `GifReader`, `WiDecoder` + `X86Vm`
    (photos).
  - `gallery.catalog`: `Catalog` (index, search, tree, names, dupes API), `ClipartItem`, `CorelIndex` (SEARCH.LST/IDX
    keywords), `WebArt`, `Duplicates`, `DrawingLoader` (loads an item via the catalog; here rather than in codec to avoid
    a cycle).
  - `gallery.export`: `Exporter` (SVG/PDF/EPS/PNG/original; `Exporter.render` makes a transparent ARGB image).
  - `gallery.ui`: `GalleryFrame` (all UI), `ThumbnailCache`, `AppIcon`.  `gallery.Main` is the entry point.
  Only the members other packages need were made public (loadAppIcon, GifReader + read, AnimatedImage constructor,
  VectorDrawing.paint, CcxDecoder.decodeCompact, CompactDrawing).
- Cache policy: never keep code to read caches written by older versions. When a change makes caches incompatible,
  bump the version (`Duplicates.VERSION` for dupes.ser, currently 12) and let them rebuild; the other .ser files have
  no version and are simply discarded when they fail to deserialize (`Catalog.load()` starts over, so the discs have
  to be added again). The package split made the existing caches unreadable on purpose.
- Caches in ~/.corelgallery: catalog.ser (sources + file lists), corel.ser (Corel keywords), names.ser + webart.ser
  (rebuilt together if either is missing), dupes.ser, swi32.dll and icon.png (copied from disc 1, never refreshed by
  Rebuild Indexes). The search index itself is rebuilt in memory at every start, so `folder-names.tsv` edits need no cache
  rebuild. Rebuild Indexes rescans every source but keeps entries of sources that can no longer be opened; catalog.ser is
  the only record of which discs were added. Optional user file ~/.corelgallery/keywords.tsv (path-or-filename TAB words).
  Streams use `Catalog.IO_BUFFER` (64 KiB).

## Features (all done and tested)
- Reads the 14 ISOs directly (ISO9660/Joliet). 216,625 clipart/photo files in the main index (~152.8K ccx, ~60.2K wi,
  ~3.5K jpg) + 829,032 GIFs in the separate web-art store. Full scan ~7 s. Box claims 1M items = 216.6K + 829K GIFs.
- Corel's own keyword DB (Gallery/Programs/SEARCH.LST + SEARCH.IDX on disc 1): 133,257 files, 21,775 keywords.
- Own CCX decoder, exports SVG / PDF / EPS / PNG / original file. .WI photos decode via the Java x86 interpreter.
- GUI layout: top bar = search field, type filter, **"Only in selected category"** checkbox (off = search everything),
  **"Hide duplicates (N)"** checkbox, Add discs…, Report…. Left column: category tree (leaf nodes use the folder icon too)
  with preview, details and export panel under it. Middle: thumbnail grid. Bottom: status line, duplicate progress bar,
  thumbnail-size slider (64–256).
- Tree behaviour: folders merged across discs; counts follow type filter; selecting a folder scopes the search (when the
  scope box is ticked). While searching, the tree shows only folders containing matches (counts = matches; searching runs
  over everything first, scope is applied afterwards). Selecting a grid item selects/expands its category in the tree.
- Quick Look overlay (space or double-click; arrows browse; space/esc/enter close).
- **Clipboard + context menu (newest):** Cmd/Ctrl+C on the grid copies the selected item as an image (`DataFlavor.imageFlavor`;
  vectors rendered transparent at the last PNG size, default 1024 px; bitmaps at native size). Right-click on a cell
  (selects it first; handles pressed+released popup triggers) shows Copy Image, Copy as File (reuses the DragOut transfer
  handler: SVG/PNG/original GIF temp file), Quick Look, Export As > each format (vector formats disabled for bitmaps) and
  Export…. `doExport(Format)` is shared with the Export button. Copy was tested under Xvfb (clipboard held a 856x1024 image);
  pasting into a real external app, Copy as File and the Export As submenu were not tried.
- Drag-out of a result exports SVG (vector) / PNG (bitmap) / original (GIF) to a temp file.
- Export asks a size only for PNG (SVG 1024 units, PDF/EPS 576 pt); format auto-switches to PNG for bitmaps and Original
  for animated GIFs.
- Long names: `CATALOG.CFG` ("folder,Title", disc roots) and `Longname.cfg` ("file,Long name[,nums]") read at scan time,
  cached in ~/.corelgallery/names.ser. Only disc 1 has them.
- Folder titles: `folder-names.tsv` (~1,100 entries, key = lower-case name, `parent/name` or full path) then
  `Catalog.folderTitle(path)`: CATALOG.CFG, table, then `prettify()`. Optional third column = extra search keywords
  (synonyms, singulars) that are indexed but never shown (9 entries, e.g. `christma` -> "xmas holiday"); `Catalog.lookup`
  does the path / parent/name / name lookup for both maps, `folderTitles()` appends the keywords per path level. This
  replaced the old hard-coded `ALIASES` map (2026-11-17), which also matched bare file base names; keywords now apply to
  folders only. Titles feed the tree and search tokens. Mostly
  verified by contact sheets (all 75 numeric photo folders, ~60 cryptic clipart folders, web-art buttons/finishes); the
  ~500 named photo folders and most other clipart sub-folders are inferred. Weak guesses: ONEHALF, COMP (button),
  BACKGRND tile names (CLIG, ITRI), 600RGRD, TEXTANIM, SEAMLESS M#S/M#B, BN/BX/CT/DN/SR/TP rule shape names.
- Same-folder same-base-name copies (any extension/disc) collapse to one item, preferring vector > wi > tif > png > jpg > bmp
  (only 44 groups). Corel keywords from any copy are kept.
- GIF web art: ~829K GIFs on discs 2–6 (+47 on disc 10) in `WebArt`, persisted at ~/.corelgallery/webart.ser (~19 MB),
  separate filter "Web art (GIF)". Substring search over folder+file name (supports -exclude). Animated GIFs play in preview
  and Quick Look (`GifReader`, `AnimatedImage`; delay 0–10 ms treated as 100 ms). ~0.05% (~400) of GIFs are ALL ZERO BYTES
  in the ISOs tested (viewer says so); not a decoder problem, maybe check another rip.
- Excluded from the index: Setup/, Redist/, Webspice/, Photohse/. Other media on discs not browsed (1,125 TTF, midi, wav,
  avi, ~59K .htm).

## Thumbnails and scrolling (rewritten 2026-11-17)
- `ThumbnailCache` keeps two tiers for a window around the scroll position, set by `GalleryFrame.updateWanted()` on every
  viewport change: decoded **art** for the visible page + `PRELOAD_PAGES` (20) ahead in the scroll direction and up to
  `KEEP_PAST_PAGES` (20) behind (never more pages behind than ahead; near the end of the list fewer are kept), and
  **rasters** only for the visible page + `RASTER_AHEAD_PAGES` / `RASTER_BEHIND_PAGES` (2 each). Everything else is evicted.
  Direction = last scroll direction (down after new results or a size change). Work order: painted cells first (`urgent`),
  then visible, then ahead nearest first; work outside the current window is skipped. No pause during fast scrolling
  (tried and removed). Render threads = cores − 1.
- Art: CCX/CMX -> `CompactDrawing` (16-bit coords, segment types, per-shape Paint/pen arrays; paints built at decode time;
  drawn through one reusable Shape/PathIterator), mean 30 KB, median 13 KB. Renders pixel-identically to `VectorDrawing`
  (checked on 3,000 random files at 64/128/256). Bitmaps -> the original if it fits, else a copy shrunk to exactly the drawn
  size (`ThumbnailCache.Bitmap`, `reduced` flag); a copy too small for the current size counts as missing and is reloaded
  from the original, so bitmaps are never enlarged unless the original is smaller. Unloadable -> `BROKEN` (grey crossed box).
- `CcxDecoder` parses once and feeds a `Sink` (`PathSink` -> VectorDrawing with pre-sized Path2D; `CompactDrawing.Builder`).
  Per render thread: `CcxDecoder.Scratch` (Inflater + page buffer) and `Source.Buffer` (IsoSource reads into it). Solid
  fills and colours are shared via small direct-mapped caches (`Fill.solid`, `VectorDrawing.color`); pens are made once
  per file.
- Rasters: TYPE_INT_ARGB_PRE (Metal's format: no conversion on upload), reused from a pool capped at 32 MB. Entry table is
  UI-thread only; finished rasters are published through `SwingUtilities::invokeLater`; eviction uses generation stamps.
- Retina: the grid, preview pane and Quick Look read the scale from the paint Graphics2D and render at device resolution,
  drawn 1:1 (`drawImageCentred`, `drawDeviceImage`). `setSize(logical, scale)`; margins and the broken-X stroke scale too.
  A raster drawn for a smaller size is shown centred at its own size, never stretched; a larger one is shrunk.
  Verified only with `-Dsun.java2d.uiScale=2`, not on a real Retina screen.
- Measured (20 s scripted scroll, slow + fast, both directions): allocation 27.6 GB -> ~2.8 GB, GC pauses 59 -> ~0 slow
  and 9–15 fast (max ~7 ms), worst fast frame 44–66 -> 33–46 ms; remaining slow frames do not coincide with GC: the UI
  thread waits on Java2D's Metal upload of new thumbnails (MTLSwToSurfaceBlit). Same at uiScale 1 and 2.
- Remaining allocation ≈ 62% compact art arrays (cache contents; decoding 20 pages ahead while scrolling fast), the rest
  Java2D per-draw objects and bitmap decoding.

## Hide duplicates (Duplicates.java, cached ~/.corelgallery/dupes.ser, `VERSION` constant currently 12)
Why: the same drawing exists in many folders/discs, and byte hashes differ (e.g. HOME/Furnitre/NWAGE379 13,624 B has real
curves; HOME/Housfurn/NWAGE379 27,050 B is ~16x more straight segments at a different scale). The DISP previews are NOT
byte-identical (≈24% of pixel bytes differ though palette matches), so matching is perceptual.
- DISP chunk of each CCX: 4-byte prefix, BITMAPINFOHEADER, 256x4 palette, 8-bit bottom-up pixels, 96x96 (10,284 B). Phase 1
  reads a 64 KB prefix of each file (whole file if DISP is cut off) in parallel per source (sorted by ISO LBA for sequential
  reads) and builds a `Preview`: 32x32 ink thumbnail (255−luma), 4x4 RGB thumbnail, 64-bit aHash. Flat previews skipped.
- Phase 2 candidates: 4 interleaved 16-bit hash slices (Hamming ≤3) filtered by `similar` (ink diff ≤0.35, colour diff ≤20;
  buckets >20,000 only compare hash-order neighbours) + direct pairing of files with the same base name (groups of 2–60,
  not same source+folder). (32-table LSH was far too slow: >5 min.)
- Phase 3 full-res compare of candidate pairs: strong ink share, count of differing pixels, mean colour diff, with 3x3
  min/max tolerance on luminance and min-channel. `accept()`: colour ≤60 (same name) / ≤24; same name ⇒ strong ≤0.06;
  different name ⇒ strong ≤0.005 and ≤12 px differ. Different-name files in the same source+folder are NEVER merged
  (alphabets, calendar days, symbol sets).
- Phase 4: union-find clusters, then a greedy keeper: best by (decodes OK, −curve fraction, −shapes, segments, sourceId, path);
  only members directly matched to the keeper are hidden (avoids chains through hub items).
- Tunable via -D: gallery.dupInk, dupColor, dupSame, dupColorSame, dupColorInk, dupOther, dupPixels. Test hook
  `Duplicates.PAIR_DEBUG`.
- Result on all 14 discs: ~29 s, 24,719 files hidden in 23,857 groups. Reported pairs NWAGE850 and NWAGE878
  (Cartoons/Cartobje disc 10 vs HOME/Furnitre disc 11) now group, keeper HOME/Furnitre.
- Known limits: Symbols colour sets with near-identical line-art previews can collapse (Blue/Green/Red); previews that
  differ by a tiny glyph can be treated as identical; some true dups with different file names are missed (CAKE01 vs
  NA0541, SPACE01 vs NA1351); the full GUI window was never run on the whole 14-disc catalog, only the logic.
- Iterations that failed (don't repeat): exact DISP MD5; 16x16 thumbnails (hid 28K, groups up to 2,340); luminance-only
  tolerant check (missed yellow-on-white); Hamming ≤8; 32-table random LSH.

## CCX decoder facts, validated against Corel's own WMF exports
Method: render .CCX with our decoder and Corel's WMF export via LibreOffice, compare pixels. Five references (BIRD657,
BIRD669, CORALRFF, OWL_MOON, romn0017): mean abs diff 1.3–3.5 of 255. File = RIFF CDRX with chunks cont, ccmm, DISP, pack
(u32 length, 'CPng', zlib at +12), LIST.
1. Path node types: 0x00 move, 0x40 line, 0xC0 control, 0x80 curve end; a cubic is C0, C0, 80.
2. Pens: rpen with flags&2 carry a 2x3 matrix (width scaled by sqrt|det|) and the outline is painted BEHIND the fill;
   flags 1 pens are centred over the fill.
3. CMYK->RGB: R = 255*(1−C−K), clamped at 0.
4. 'proc' chunks (EASTEG02, disc 9 Clipart/AFRICA/Eastereg) hold the real shapes; decoder descends into them and frames
   by the union of item bounds. Untested how many files use proc.
rotl entries (12 B): [running id, 1, colour idx, 1, pen idx, 1]. Fountain header (18 B): u16 0, u32 kind, s32 angle*10,
then 8 bytes unknown.

## .WI photos — via Java x86 interpreter
`57 49 04 01` header + wavelet bitstream; decoder is `Gallery/Programs/swi32.dll` (disc 1), run by `X86Vm` (~700 lines,
41 integer mnemonics, KERNEL32 stubs) through `WiDecoder`. DLL is read from disc 1 at run time
(`Catalog.wiCodec()`: -Dgallery.swi32, ~/.corelgallery/swi32.dll, else scan sources); NOT bundled. Pixel-identical to a
Python/unicorn oracle on 9 samples; ~0.4–0.6 s per photo. Output RGB top-down, stride rounded to 4 (all samples 384x256, so
odd widths untested). A pure algorithm port stays optional.

## Not done / ideas
- Radial gradients approximate (centre/extent); fountain offsets and edge pad ignored. Text records in CCX; .cdr/.wmf/.emf
  input; EPS output unviewed.
- Untested: portrait/odd-width WI; PNG size dialog; real-app paste of the clipboard image; the full-catalog GUI.
- Possible follow-ups: multi-select copy/export; copy as SVG text; more duplicate recall for differently named files;
  per-folder "hide duplicates" explanation in UI.
- Thumbnails: reuse evicted art arrays through power-of-two size-class pools (proposed, not done: ~3–4x less allocation
  while scrolling, ~1.4x art memory, must not recycle art a render thread is using). The window has no overall memory cap
  (41 pages of art). Not tried: real mouse drags, a real Retina screen, fractional scales (1.25/1.5).

