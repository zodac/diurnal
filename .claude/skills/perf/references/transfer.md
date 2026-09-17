# The data import's upload

**The Settings Data card posts the `File` itself, never an `ArrayBuffer` of it. Do not "simplify" that back
into a `file.arrayBuffer()` read.** A `Blob` body is streamed off disk by the browser; an `ArrayBuffer` body is
copied out of the tab's own heap, and Chromium is dramatically slower at the second one.

Measured in Chromium against `http://127.0.0.1:8081`, from the picker's `change` event to the confirmation
appearing, 3 reps each, warm file cache:

| Archive                                          | `arrayBuffer()` + POST | POST the `File` |
|--------------------------------------------------|------------------------|-----------------|
| 351 KB - 8,760 logs, 1,000 notes                 | 124-132 ms             | 106-112 ms      |
| 1.8 MB - 27,375 logs, 1,800 x 1,500-char notes   | 539-595 ms             | 479-519 ms      |
| **40 MB - the first row plus 20 x 2 MB files**   | **3,957-4,037 ms**     | **206-209 ms**  |

- **The whole win is the browser's, not the server's.** `curl` posting the same 40 MB file answered in 200 ms
  throughout, so the network and the server were never the cost - 3.8s of it was Chromium materialising and
  copying a 40 MB request body it could otherwise have streamed. The read that produced the buffer goes too,
  along with a 40 MB copy held in the tab for as long as the panel stayed open.
- **A `File` is re-readable**, which is what lets the confirmation re-send exactly what the preview was
  computed from for nothing. That is the property the two-step preview needs - see `TRANSFER.md`.
- The same change is what made the upload's progress rail possible: the post moved to `XMLHttpRequest`, whose
  `upload.onprogress` is the one thing `fetch` still cannot report.

## The server half, for when it IS the server

`unpack` + `ImportParser.parse`, measured standalone on a warm C2 JVM (6 reps, first discarded):

| Archive                                        | unpack | parse  |
|------------------------------------------------|--------|--------|
| 77 KB - 27,375 logs, no notes                  | 2 ms   | 25 ms  |
| 1.7 MB - 1,800 x 1,500-char notes, no logs     | 14 ms  | 90 ms  |
| 1.8 MB - both                                  | 16 ms  | 115 ms |

- **Notes dominate, and the cost is the shared text pipeline**, not the CSV reader: 1,800 notes of 1,500
  characters is 62 ms of `TextValidation.check` against 8 ms of `Csv.parse`. That pipeline is the point - an
  import must not be a way to get values into the database that no other path would accept - so it is not a
  cost to remove.
- **A single run is always the COLD one**, and an import is a once-a-year action: every one of the figures
  above was 3-5x higher on the first call of a fresh JVM (357-490 ms for the same archives). That is JIT
  warm-up, and there is nothing to do about it beyond not making the work bigger.
- **`quarkus:dev` is not a place to measure this.** Dev mode runs with `-XX:TieredStopAtLevel=1` (C1 only),
  which made every server figure here ~3.5x its production value - 495 ms in dev against 135 ms standalone
  for the same 1.8 MB archive. Measure against the packaged jar, or accept the multiplier knowingly.
- **Trigger to revisit**: an archive large enough that the parse, rather than the upload, is what the user
  waits on - which at these rates means a journal of tens of thousands of long notes. The preview and the
  commit each pay it once, by design (see `TRANSFER.md`'s "A stateless two-step preview").
