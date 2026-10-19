<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="96" height="96" alt="SpecterAI"/>
</p>

<h1 align="center">SpecterAI</h1>

<p align="center">
  <strong>AI swarm coding</strong> inside JetBrains IDEs — type a command, watch ghost cursors write the code.
</p>

<p align="center">
  <a href="https://github.com/DaniilPascal/SpecterAI/blob/main/LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-green.svg"/></a>
  <img alt="Platform" src="https://img.shields.io/badge/JetBrains-2025.1%2B-orange"/>
  <img alt="Plugin ID" src="https://img.shields.io/badge/id-io.github.daniilpascal.specterai-blue"/>
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-informational"/>
</p>

---

## What it does

SpecterAI turns short in-editor commands into multi-caret “swarm” typing: the model (or local templates) returns code blocks, and the plugin inserts them with animated ghost cursors — in the current file or across several files.

Works in **IntelliJ IDEA**, **Android Studio**, **PyCharm**, **CLion**, **WebStorm**, **GoLand**, **RustRover**, and other JetBrains IDEs on platform **2025.1+**.

| Area | What you get |
|------|----------------|
| Commands | `Specter.AI` / `S.AI` DSL with colored highlighting |
| Swarm | Multi-caret typing, queue, Esc cancel, status-bar progress |
| Optim % | Live quality badges on functions → Explain / Fix |
| Safety | Optional diff preview before any write |
| Providers | OpenRouter by default, or any OpenAI-compatible API |

---

## Quick start

1. Install the plugin (Marketplace or `Settings → Plugins → Install from Disk` → `SpecterAI-1.0.0.zip`).
2. Open **Settings → Tools → SpecterAI**.
3. Paste an **API key** (easiest: [OpenRouter](https://openrouter.ai)).
4. In any editor, type:

```text
Specter.AI("create a hello world function").start(14);
```

5. Press `;` to run — swarm typing starts.

Without a key, enable **local templates** fallback in Settings (no network; demo-quality output).

---

## Commands

Type in the editor and finish with a semicolon.

```text
Specter.AI("your prompt").start(14);
S.AI("your prompt").start(14);
Specter.AI.CEO();
```

| Piece | Meaning |
|-------|---------|
| `Specter.AI` / `S.AI` | Full or short form |
| `"…"` | Prompt for the model |
| `.start(N)` | Typing speed **1…100** (1 = slow, 100 = fast). Omit `N` to use Settings delay |
| `Specter.AI.CEO()` | Easter egg — signature comment at end of file |

DSL colors: keyword · prompt · `.start()` / `.CEO()`.

---

## Where code lands (tags)

The model (or templates) can place blocks with tags:

| Tag | Placement |
|-----|-----------|
| `### near` | Near the caret |
| `### imports` | Import / include / package area |
| `### class` | Inside the enclosing type |
| `### file` | Append at end of current file |
| `### file:FooTest.kt` | Create or open another relative file |

Output language follows the **current file** (Java, Kotlin, Python, C/C++, JS/TS, Go, …).

---

## While AI is busy

- A second `.start();` is **queued** (up to 8).
- **Esc** or **SpecterAI → Cancel AI** — stop generation, preview, or swarm and clear the queue.
- LARGE / XLARGE jobs show live progress in the **status bar**.
- With **diff preview** on: Accept / Reject (Esc = reject, no edits).

---

## Optim %

Live badges next to non-empty functions (Kotlin, Java, Python, JS/TS, Go, C/C++):

- Click → **Explain** / **Fix with AI**
- Toggle: **SpecterAI → Optim %**
- Options in Settings: show name, show score factors, refresh delay

---

## Menu & Settings

**Top menu → SpecterAI**

| Action | Role |
|--------|------|
| AI Swarm | Enable / disable commands |
| Optim % | Show / hide badges |
| Cancel AI | Stop current job |
| Help | In-IDE documentation |

**Settings → Tools → SpecterAI**

| Setting | Role |
|---------|------|
| API key | Stored in IDE Password Safe |
| Base URL | Default `https://openrouter.ai/api/v1` — or any OpenAI-compatible endpoint |
| Model | Presets (GPT, Claude, Gemini, DeepSeek…) or Custom |
| Typing delay / max blocks | Swarm feel & size |
| Fallback to templates | Work without a key / on API errors |
| Diff preview | Confirm plan before writes |
| Optim % options | Badges behaviour |

### Providers (not only OpenRouter)

| Setup | Base URL | Key |
|-------|----------|-----|
| OpenRouter (default) | `https://openrouter.ai/api/v1` | OpenRouter key |
| OpenAI direct | `https://api.openai.com/v1` | OpenAI key |
| Other compatible API | their `…/v1` | their key |

Model id must match what that provider expects (use **Custom…** when needed).

---

## Privacy

With an API key set, prompts and relevant editor context are sent to **your** chosen endpoint. The key never ships inside the plugin zip. Without a key, local templates can run offline. Review your provider’s privacy policy before sending proprietary code.

---

## Build from source

```bash
./gradlew test
./gradlew runIde
./gradlew buildPlugin
```

Artifact: `build/distributions/SpecterAI-1.0.0.zip`

| Property | Value |
|----------|--------|
| Plugin id | `io.github.daniilpascal.specterai` |
| Compatibility | since `251` (2025.1+), open-ended |
| License | [MIT](LICENSE) |

---

## Author

**DaniilPascal** (Даниил Машичев)  
GitHub: [DaniilPascal/SpecterAI](https://github.com/DaniilPascal/SpecterAI)

---

<p align="center">
  <sub>SpecterAI · swarm typing for JetBrains</sub>
</p>
