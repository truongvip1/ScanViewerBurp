# Active Scan Timeline Viewer

`Active Scan Timeline Viewer` is a Burp Suite Professional extension for reviewing **all HTTP traffic emitted by the native Active Scanner**. It is not restricted to findings: every Scanner request is listed, along with its response when received.

## Interface

- **All scanner traffic** is an always-on tab that contains every request from Burp Scanner after the extension loads, including scans that produce no findings.
- Tabs **1**, **2**, **3**, and so on are created when requests are added to the scan queue. Each tab contains traffic from one request's audit. Waiting tabs show `Queued` until their turn starts.
- Each tab uses an Intruder-like list with request, full inferred payload, every inferred insertion point, numeric status/length/duration, baseline `Delta length`, changed-status flag, response similarity, payload reflection, extraction result, response state, bookmark and note.
- Selecting a row displays the raw request and response underneath the list. When no response was observed, the panel remains empty and clearly explains the state; it never fabricates an HTTP response.
- **Advanced filters** combine status (`4xx`, `5xx`, or status changed), response state, slow-response threshold, bookmark state, and text/regex search with **AND** or **OR**. Search can target metadata, raw request, raw response, or both; raw-message searching runs in a background worker. Filter presets are saved in Burp preferences.
- **Highlight rules** support `Name | #RRGGBB | regex` for metadata, with built-in colors for 5xx, no response, slow response and bookmarks. **Extraction rules** support `Name | REGEX | expression`, `Name | HEADER | header-name`, and `Name | JSON | $.field`; results appear in the `Extracted` column and are saved with the row.
- Double-click a numbered tab, or right-click it and choose **Rename tab...**, to rename it. Its right-click menu also exposes the retained **Queue state history...**, including records for a request removed from Queue. Right-click a row for Repeater, Comparer, copy, bookmark and note actions.
- **Pause display** pauses only Swing updates: capture and persistence continue, including response extraction. Resume rebuilds directly from the store. Traffic refreshes are coalesced without cancelling a slow filter pass, so continuous Scanner traffic cannot starve the table. Selecting a request opens **Compare with base** by default. It is a component-aware analysis view: Query, Headers, Cookies, JSON, Form, Body and a line-aligned raw diff each show `location -> original -> changed` in color. The overview also shows base-response metrics (`Delta length`, status change, exact match, normalized similarity and payload reflection). Request comparisons are calculated off the Swing thread and cached per row.
- The main toolbar keeps only frequent actions. Filters, rules, import/export, restoring closed tabs and layout reset are under **More...**. The queue similarly puts recovery, retry, removal and history actions under **More...**. Saved divider positions are applied after Burp lays out the tab, then clamped to the usable window size.

## Start an Active Scan with Timeline

1. In Proxy history, Target, Repeater, or another HTTP-message view, right-click the request.
2. Open Burp's **Extensions** submenu and choose **Active scan with Timeline**.
3. The extension adds the request to the queue, creates a numbered tab, and starts the queue. If another audit is already running, the new request waits for its turn.

Choosing **Active scan with Timeline** again appends another request. Only one extension-started audit runs at a time. New traffic switches to the next tab only when its audit starts; late responses remain linked to the original request and tab.

The action calls Montoya `Scanner.startAudit`, so Burp creates a real Scanner audit task that is visible in the Dashboard. The selected tab's status bar and tooltip show the scanner's `statusMessage` plus its request, insertion-point, finding, and error counts. Burp's public extension API does not provide a definitive task-completed callback, so the extension never treats a quiet traffic stream as completion. All individual requests are retained in **All scanner traffic**.

The **Active scan with Timeline** action uses Montoya's built-in `LEGACY_ACTIVE_AUDIT_CHECKS` configuration. It does not read or reuse a custom scan configuration selected in Burp's native scan wizard, because that configuration is not exposed by the public API.

## Sequential scan queue

1. Select one or more requests in Proxy history, Target, Repeater, or another HTTP-message view.
2. Right-click **Extensions > Add to scan queue (N)** to stage the selected requests. Each receives its own numbered tab in the order supplied by Burp. Requests appended to an already running queue run after its current items.
3. Open **Audit Timeline > Scan queue...**, then click **Start / resume queue**. To enqueue and start immediately from the context menu, use **Active scan with Timeline** for a single request or **Scan selected requests sequentially** for multiple requests.
4. The queue window shows order, tab name, request, state, and status. Use **Move up** / **Move down** to reorder waiting items, and **More... > Remove waiting request** to exclude a queued item while retaining its tab history and state history. State colors distinguish queued, dispatching, running, completed, failed and recovery-required items.

**Pause queue**, **Pause display**, and pausing an audit in Burp Dashboard are separate controls. Pausing the queue leaves the current audit running; pausing display leaves capture, saving, and queue dispatch running. Use Dashboard to pause the audit itself. Clearing all traffic or deleting the current audit's history is blocked while it owns a queue slot.

The extension polls [Montoya's Audit.statusMessage()](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/scanner/audit/Audit.html) once per second. It advances on explicitly recognized completion text such as `finished`, `completed`, or `Audit finished.`. This API exposes a free-text status, not a completion callback or stable status enum. Blank, waiting, paused, or unrecognized messages hold the queue; no traffic-idle timeout is used. If Burp reports completion in an unfamiliar form, verify it in Dashboard and use **Confirm current audit finished...**. A failed audit or API error pauses dispatch for review.

Queue ordering isolates extension-started audits only. Other native or extension Scanner tasks still contribute traffic to the current capture window because Montoya does not expose their task IDs on HTTP callbacks. Avoid running unrelated Scanner audits concurrently when you need clean per-request logs.

Waiting requests, their base request/response, and queue states are saved in the Burp project. After reload the queue is paused. A previously dispatched or running audit becomes `UNKNOWN_DISPATCH`; the extension cannot reattach to it and will never automatically repeat it. Before resuming, check its Dashboard task and confirm it has stopped. A legacy `REMOVED` queue row is migrated to `NONE`, hidden from Queue, and retains an audit-trail entry. Unloading the extension preserves native Dashboard tasks. Session archives import as history and do not schedule scans.

## What cannot be obtained from the public API

- Burp does not expose the native Scanner's private audit-task ID, exact payload, exact insertion-point metadata, or individual network exception to extensions.
- `Payload` and `Insert point` are marked as **best-effort inferences** by comparing requests with the scan's base request.
- Request state is explicit: `Waiting`, `Response received`, `No response after 120 seconds`, or `Unknown reason`. A late response replaces the provisional state.

The request, response, status, response time, and response length are recorded directly from Montoya HTTP callbacks.

## Persistence across Burp restarts

The extension saves tab metadata, base request/response, raw captured request/response bodies, notes, bookmarks, metrics and extraction output into **Burp project extension data**. Version 2 stores only changed sessions/rows rather than rebuilding the complete history. Dirty markers are acknowledged only after the v2 root has been attached successfully, so a failed first save is retried with the same rows. The status bar shows `Saving changes`, `Saved at...`, or `Save failed`; a failure remains visible until the next write actually begins. A restore error skips only the damaged record, reports the skipped-record count, and never clears the rest of the in-memory history.

On reopening the same saved project, the tabs and all captured rows are restored. Historical tabs from earlier versions show `Restored - scan not running`; queued sessions retain their queue state, while previously dispatched audits are marked `UNKNOWN_DISPATCH`. A closed tab only hides the UI: use **More... > Reopen closed tabs** to bring it back. **More... > Delete tab history** removes one session independently.

To retain data after Burp closes, use a named project and save it. Montoya keeps project extension data only in memory when Burp is started without a project file. For a Temporary Project, use **More... > Export session...** to create an `.ascan` archive; it includes every row in the selected session plus raw request/response bytes and can be restored with **More... > Import session...**. Archive and CSV reads/writes run off the Swing thread; archive export writes a temporary file before replacing the destination. The project file and archive contain HTTP bodies and potentially cookies or credentials; handle them as sensitive data. **Clear captured traffic** also clears saved Timeline data.

## Build

Requirements: JDK 17+ and PowerShell. Maven/Gradle are not required.

```powershell
Set-Location D:\ExtBurp\ScanViewer
.\build.ps1
```

The script downloads the Montoya API from Maven Central on first run and creates:

`target\active-scan-timeline-viewer.jar`

## Regression checks

```powershell
Set-Location D:\ExtBurp\ScanViewer
.\test.ps1
```

The dependency-free checks cover an interrupted first v2 save followed by retry/reload, corrupt project rows, malformed archive import, a response arriving after the provisional timeout, non-cascading raw diff, strict JSON extraction, response metrics for one-digit security flags, and combined AND/OR raw-response filtering. Queue tests exercise sequential dispatch, traffic ownership and late responses across tabs, pause/resume, reorder/removal with state history, migration of legacy removed rows, ambiguous completion text, startup and status-read failures, recovery after reload, and stale manual completion confirmations. These use simulated Montoya audits and persistence; live Dashboard behavior must be checked in Burp Professional.

## Install

1. In Burp Suite Professional, go to **Extensions** > **Installed** > **Add**.
2. Select `target\active-scan-timeline-viewer.jar` as a Java extension.
3. Use the **Audit Timeline** suite tab while running native Active Scans.
