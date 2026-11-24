# Device API for scripted clients

This fork (studio-lunii) makes the local API usable by a program, not only by the web UI. Every
route below is under `http://localhost:8080/api/device`. Bodies are JSON.

## Errors

A refused request answers with a status that says what kind of problem it is, and this body:

```json
{"success": false, "error": "PACK_ALREADY_ON_DEVICE", "message": "The pack … is already on the device"}
```

| Status | `error` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | missing field, bad UUID, bad folder name, unknown format |
| 404 | `LIBRARY_PACK_NOT_FOUND` | the `path` given is not in the library |
| 404 | `PACK_NOT_ON_DEVICE` | the pack (or folder) to act on is not on the device |
| 409 | `PACK_ALREADY_ON_DEVICE` | remove it first, or skip it |
| 409 | `CONTENT_FOLDER_EXISTS` | a residual folder blocks the upload; see *Orphan folders* |
| 409 | `FOLDER_STILL_REFERENCED` | the folder belongs to an indexed pack and was not removed |
| 503 | `NO_DEVICE` | no device plugged |
| 507 | `NOT_ENOUGH_SPACE` | not enough free space on the device |
| 500 | `INTERNAL_ERROR` | anything else; `message` says what |

`POST /api/library/convert` uses the same body for its failures.

## Transfers

`POST /addFromLibrary` `{"uuid": "…", "path": "<library entry>"}` answers at once with
`{"transferId": "…"}`. Device operations (uploads, removals, reorders, extractions, orphan
removal) run **one at a time on the server**, in the order received, so several uploads can be
sent back to back without corrupting the pack index.

`GET /transfer/{transferId}`:

```json
{
  "transferId": "…", "uuid": "…", "operation": "add",
  "state": "RUNNING", "done": false, "success": false,
  "transferred": 1048576, "total": 4194304, "progress": 0.25,
  "createdAt": 1764000000000, "startedAt": 1764000001000
}
```

`state` is `QUEUED`, `RUNNING`, `SUCCEEDED` or `FAILED`. A failed transfer also carries `error`,
`reason` and `message`. A transfer is `SUCCEEDED` only once the pack index has been written. The
last 200 finished transfers are kept; `GET /transfers` lists them all, oldest first. An unknown id
answers 404 `TRANSFER_NOT_FOUND`.

The web UI keeps using the event bus: `storyteller.transfer.<id>.done` now also carries `error`
and `message` on failure.

## Orphan folders

An upload that fails part-way can leave a `.content/<XXXXXXXX>` folder that the index does not
reference. It takes space and makes any later upload of the same pack refuse with
`CONTENT_FOLDER_EXISTS`.

- `GET /orphans` → `{"folders": ["0A0B0C0D", …]}`. Listed in the device queue, so an upload in
  progress is never reported as an orphan.
- `POST /orphans/remove` `{"folders": ["0A0B0C0D"]}` → `{"success": true, "results": [{"folder":
  "0A0B0C0D", "removed": true}]}`. Only folders named explicitly are removed, and only if the index,
  re-read at that moment, does not reference them. **Removal is permanent.**

A typical recovery: an upload fails with `CONTENT_FOLDER_EXISTS` for pack `…5555aaaabbbb`; its
folder is the last eight hex digits, upper case (`AAAABBBB`). If `GET /orphans` lists it, remove it
and upload again.
