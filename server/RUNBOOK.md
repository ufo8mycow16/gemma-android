# Gemma 2.0 on Vision — how to update and run

Everything below happens in a terminal on Vision. Nothing here touches Torch, CUDA or the NVIDIA driver,
and nothing deletes `gemma.db`: Gemma backs it up herself every time she starts (in `backups/`).

## 1. Put the new files in place

1. Stop Gemma (Ctrl+C in her terminal).
2. Copy your current folder somewhere safe first, e.g. `cp -a ~/gemma ~/gemma-before-2.0`
   (use your real Gemma folder name if it is different).
3. From the download, copy into your Gemma folder: `app.py`, the folders `gemma_core/`, `web/`, `skills/`,
   and `requirements.txt`. Leave `gemma.db`, `secrets.json`, `google_token.json`, `backups/` and `logs/` as they are.

## 2. Install what she needs (once)

```bash
cd ~/gemma
source venv/bin/activate            # the venv you already use for Gemma
pip install -r requirements.txt
```

`faster-whisper` (transcription) downloads a speech model the first time it is used. If any line fails,
Gemma still runs; she just says that feature isn't installed.

## 3. Start her

```bash
cd ~/gemma && source venv/bin/activate
GEMMA_HOST=0.0.0.0 python3 app.py
```

The first lines show the build (`Gemma 2.0.0 …`), the backup she just made, and the address to open.
The web page is now the new one; the old page is still at `/classic`. Diagnostics: `/diagnostics`.

## 4. Optional connections (each one can be done any time)

| What | How |
|---|---|
| **Pictures (Qwen in ComfyUI)** | Start ComfyUI. Open your working Qwen-Image workflow, turn on *Dev mode* in ComfyUI settings, then *Save (API)*. Save it as `workflows/image_generate.json` in Gemma's folder (an edit workflow as `workflows/image_edit.json`). If ComfyUI isn't on `http://127.0.0.1:8188`, set `GEMMA_COMFY_URL`. Settings → Connections shows *Ready* when it works. |
| **GitHub** | Either run `gh auth login` on Vision, or paste a fine-grained token in Settings → Connections → GitHub. |
| **Gmail, Drive, Contacts** | In Google Cloud Console enable the Gmail, Drive and People APIs for the project you used for Calendar, then run `python3 app.py --connect-google` once and accept the new permissions. |
| **Claude Code** | `npm install -g @anthropic-ai/claude-code`, then run `claude` once and sign in. |
| **Godot checks** | If `godot` isn't on the PATH, set `GEMMA_GODOT=/path/to/Godot`. |
| **Folders Gemma may read/write** | Settings → Connections → Local Files. One folder per line; add ` write` to let her change files there (she still asks first). |

## 5. The phone

**This one time, uninstall the old Gemma app first.** Earlier builds were each signed with a different
throw-away key, so Android refused to update them in place — which is also why the server settings kept
disappearing: every new build had to be a fresh install. From this build on every APK is signed with the
same key, installs over the last one and keeps its settings.

In the app's Settings, enter Vision's address and key and press **Test connection**: when it says
*Connected … Saved.* it is stored (no separate Save needed).

## Useful commands

```bash
python3 app.py --backup            # a full backup now (database, files, settings) in backups/
python3 app.py --export            # a ZIP of everything for you to keep
python3 app.py --add-client phone  # a separate key for a device
python3 tools/stamp_build.py       # writes BUILD_INFO.json (build identity when there is no git)
```

## Going back

Stop Gemma and copy `~/gemma-before-2.0` back. The database is never changed in a way the old version
can't read (new tables and columns only).
