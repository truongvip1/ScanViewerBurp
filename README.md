# Active Scan Timeline Viewer

`Active Scan Timeline Viewer` is a Burp Suite Professional extension for reviewing **all HTTP traffic emitted by the native Active Scanner**. It is not restricted to findings: every Scanner request is listed, along with its response when received.

## Interface

- **All scanner traffic** is an always-on tab that contains every request from Burp Scanner after the extension loads, including scans that produce no findings.
- Tabs **1**, **2**, **3**, and so on are created immediately when you select **Active scan with Timeline** from a request's right-click menu. Each contains the request/response list for that scan.
- Each tab uses an Intruder-like list with request, full inferred payload, every inferred insertion point, numeric status/length/duration, baseline `Delta length`, changed-status flag, response similarity, payload reflection, extraction result, response state, bookmark and note.
- Selecting a row displays the raw request and response underneath the list. When no response was observed, the panel remains empty and clearly explains the state; it never fabricates an HTTP response.
- **Advanced filters** combine status (`4xx`, `5xx`, or status changed), response state, slow-response threshold, bookmark state, and text/regex search with **AND** or **OR**. Search can target metadata, raw request, raw response, or both; raw-message searching runs in a background worker. Filter presets are saved in Burp preferences.
- **Highlight rules** support `Name | #RRGGBB | regex` for metadata, with built-in colors for 5xx, no response, slow response and bookmarks. **Extraction rules** support `Name | REGEX | expression`, `Name | HEADER | header-name`, and `Name | JSON | $.field`; results appear in the `Extracted` column and are saved with the row.
- Select a numbered tab and click **Rename tab**, or double-click the tab itself, to rename it. Right-click a row for Repeater, Comparer, copy, bookmark and note actions.
- **Pause display** pauses only Swing updates: capture and persistence continue without accumulating an unbounded UI event queue. **Compare with base** is a component-aware analysis view: Query, Headers, Cookies, JSON, Form, Body and a line-aligned raw diff each show `location -> original -> changed` in color. The overview also shows base-response metrics (`Delta length`, status change, similarity and payload reflection).

## Start an Active Scan with Timeline

1. In Proxy history, Target, Repeater, or another HTTP-message view, right-click the request.
2. Open Burp's **Extensions** submenu and choose **Active scan with Timeline**.
3. The extension creates the next numbered tab before starting the audit, then starts an Active Scan and routes all subsequent Scanner traffic to that tab.

Choosing **Active scan with Timeline** again creates the next numbered tab and routes new Scanner traffic to it. Run one custom scan at a time: Burp does not publish the native per-request task ID needed to separate overlapping audits.

The action calls Montoya `Scanner.startAudit`, so Burp creates a real Scanner audit task that is visible in the Dashboard. The selected tab's status bar and tooltip show the scanner's `statusMessage` plus its request, insertion-point, finding, and error counts. Burp's public extension API does not provide a definitive task-completed callback, so the extension never treats a quiet traffic stream as completion. All individual requests are retained in **All scanner traffic**.

The **Active scan with Timeline** action uses Montoya's built-in `LEGACY_ACTIVE_AUDIT_CHECKS` configuration. It does not read or reuse a custom scan configuration selected in Burp's native scan wizard, because that configuration is not exposed by the public API.

## What cannot be obtained from the public API

- Burp does not expose the native Scanner's private audit-task ID, exact payload, exact insertion-point metadata, or individual network exception to extensions.
- `Payload` and `Insert point` are marked as **best-effort inferences** by comparing requests with the scan's base request.
- Request state is explicit: `Waiting`, `Response received`, `No response after 120 seconds`, or `Unknown reason`. A late response replaces the provisional state.

The request, response, status, response time, and response length are recorded directly from Montoya HTTP callbacks.

## Persistence across Burp restarts

The extension saves tab metadata, base request/response, raw captured request/response bodies, notes, bookmarks, metrics and extraction output into **Burp project extension data**. Version 2 stores only changed sessions/rows rather than rebuilding the complete history. Dirty markers are acknowledged only after the v2 root has been attached successfully, so a failed first save is retried with the same rows. The status bar shows `Saving changes`, `Saved at...`, or `Save failed`; a failure remains visible until the next write actually begins. A restore error skips only the damaged record, reports the skipped-record count, and never clears the rest of the in-memory history.

On reopening the same saved project, the tabs and all captured rows are restored. Restored tabs show `Restored - scan not running`, because an audit that was interrupted by closing Burp cannot be resumed. A closed tab only hides the UI: use **Reopen closed tabs** to bring it back. **Delete tab history** removes one session independently.

To retain data after Burp closes, use a named project and save it. Montoya keeps project extension data only in memory when Burp is started without a project file. For a Temporary Project, use **Export session...** to create an `.ascan` archive; it includes every row in the selected session plus raw request/response bytes and can be restored with **Import session...**. The project file and archive contain HTTP bodies and potentially cookies or credentials; handle them as sensitive data. **Clear captured traffic** also clears saved Timeline data.

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

The dependency-free checks cover an interrupted first v2 save followed by retry/reload, corrupt project rows, malformed archive import, a response arriving after the provisional timeout, non-cascading raw diff, response metrics/extraction, and combined AND/OR raw-response filtering.

## Install

1. In Burp Suite Professional, go to **Extensions** > **Installed** > **Add**.
2. Select `target\active-scan-timeline-viewer.jar` as a Java extension.
3. Use the **Audit Timeline** suite tab while running native Active Scans.
