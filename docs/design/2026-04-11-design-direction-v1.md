# Beam — Design Direction v1

**Status:** Approved (post-critique revision)
**Date:** 2026-04-11
**Scope:** Full UI/UX redesign of the Chrome extension and Android app

---

## 0. Executive summary

**Research took four parallel streams:** OS-integrated transfer (AirDrop / Universal Clipboard / Quick Share), FOSS peers (LocalSend / KDE Connect / PairDrop), premium utility reference (Linear / Raycast / Arc / Superhuman), and a friction audit of Beam's own 25 screens across both platforms.

**Headline finding:** Beam is competing in two markets at once — it is *functionally* a LocalSend (encrypted peer transfer) but *ergonomically* it wants to be a Linear (dense, keyboard-first, distinctive). The gap between those two references is the whole redesign opportunity. Nobody else is currently occupying "LocalSend's security model in Linear's chassis."

**Proposed direction:** A single-surface, dense, row-first utility where **the device list IS the home screen** — no picker, no palette overlay, no grid of avatars. Paste or drop → Enter → top online device is the zero-click hero flow. Transfers animate inline on the owning row. Pairing, settings, and history are full-surface replacements, not modals. Dark-only in v1, one accent (Signal Cyan), zero shadows, Inter + tabular figures, Lucide 1.5 px icons.

**What changed after critique** from the initial synthesis:
1. The "command palette" framing is dropped — the input is a **filter that appears when you type**, not a palette with slash commands.
2. The "same-account auto-accept" trust tier is renamed to what Beam's crypto actually expresses: **"all paired devices auto-accept; unpairing is the revocation mechanism."**
3. SAS verification **stays emoji**, not numeric — the research-backed security property of visual-shape SAS is not worth trading for aesthetic consistency.
4. Font bundling on Android is **cut**; Android uses its system stack with tabular figures. Chrome keeps Inter via `@font-face` (cheap).
5. Tablet two-pane, slash commands, relay latency chip, context-sensitive keyboard footer, light mode token definition, clipboard row composite, rotating placeholder, and drag-from-Files are all **deferred from v1**.
6. Popup lifetime is honestly acknowledged: Chrome popups dismiss on focus loss, so **transfer completion state lives in the SW, not the popup**; the popup re-renders the correct state on reopen.

---

## 1. Research — competitive landscape

**Apple AirDrop, Universal Clipboard, Handoff.** Zero-config trust for same-Apple-ID devices, silent auto-accept within accounts, explicit accept for cross-account. Best-in-class "hide the UI when you can" — Universal Clipboard has effectively zero UI. What to copy: silent routing for owned devices. What NOT to copy: avatar grid picker, Share Sheet as sole entry, opaque progress ring with no percent, heads-up notifications as primary surface.

**Google Quick Share.** Vertical list picker (not grid), percent+count+rate progress, always-cancel, "Your devices" grouped at top. Better progress legibility than AirDrop. Too Material for Beam's target aesthetic.

**LocalSend.** Current FOSS benchmark. Memorable two-word auto-aliases ("Fuzzy Kangaroo"), clean dark mode, mDNS discovery, QR fallback, Quick-Save toggle. Weaknesses: looks like a "default Flutter app"; generic Material 3 layout. **Positioning implication: Beam must match LocalSend's functional floor, then leapfrog on craft.**

**PairDrop / Snapdrop.** Radial picker + ring-progress around the peer avatar. Paste-to-send is a first-class gesture. The ring-progress-on-the-target-object is the single best pattern to steal.

**KDE Connect.** Plugin-grid maximalism; feels like a system utility. Killer feature: file-manager context menu integration. Won't copy the kitchen-sink interface.

**Linear / Raycast / Arc / Superhuman (the aesthetic anchor).** Inter Variable 13 px body / 11 px metadata, 2-3 weights (400/500/600), cool-gray neutrals, one accent, 4 px grid, tabular figures, zero or near-zero shadows in dark mode, 1.5 px-stroke Lucide-family icons, 120-200 ms custom `cubic-bezier(0.2, 0, 0, 1)` motion. Keyboard-first communicated via always-visible shortcut chips. Sentence case, verb-first commands, dry empty states.

---

## 2. Beam's current friction catalog

**High severity:**
- Popup device selection is ambiguous — auto-selects "first online" but users don't understand why the highlight moves.
- PIN countdown expiry has no re-pair affordance — user stares at a stale `0s` counter.
- Settings "Paired Devices" section may be a stub.
- Context menu sends to offline devices silently fail — "(offline)" label doesn't prevent the click.
- Auto-copy clipboard requires popup to be open — architectural constraint surfacing as silent failure.

**Medium severity:** no retry on failed transfers; QR generation has no ETA; icon picker buttons unlabeled; clipboard history grows unbounded; Android empty state assumes extension is installed; Android PIN entry uses an invisible-field pattern that's non-obvious; Android SAS labels sometimes absent.

**Low severity:** keyboard shortcuts defined but not visible; no pull-to-refresh on Android; Android TopAppBar title hardcoded instead of showing device name.

**Strengths to preserve:** consistent dark-only palette, real-time progress %, status dots, SAS emoji verification, actionable empty states, Material 3 accessibility on Android, naming-form live validation.

---

## 3. Vision

Beam is a secure cross-device conduit for people who move content between their own devices dozens of times a day. The design target is a **dense, keyboard-first status surface** that treats paired devices the way a terminal treats a list of hosts: always visible, always addressable, no picker theatre. The closest analog isn't AirDrop or LocalSend — it's a keyboard launcher with a transfer log attached.

Where AirDrop hides, Beam shows state. Where LocalSend asks you to click through a grid, Beam lets you paste and press Enter. Where KDE Connect gives you ten plugins, Beam gives you one action done right.

**The test for every design decision:** does it preserve the zero-click hero flow (popup open → paste/drop → Enter → sent)?

---

## 4. Information architecture

### Chrome popup (380 × 500)

Single column. No tabs, no drawer, no routes. Four zones top-to-bottom:

1. **Identity strip** (32 px): this device's name, online dot, settings gear right-edge. No logo.
2. **Device + activity list** (flex, ~400 px): two section headers — `Devices` and `Activity`. Rows share a 36 px grid and a common component. Online devices sort first in `Devices`. Activity is the last 8 transfers, newest first.
3. **Filter input** (appears inline at top of list when the user types): not a command palette. Just a filter over the visible list. Dismisses on Escape.
4. **Shortcut footer** (28 px): a static set of chips — `↵ send` `↑↓ select` `p pair` `, settings` `esc back`. No context-sensitive renderer in v1.

**Hero flow (zero clicks):** popup opens → clipboard or dropped file is staged → top online device is pre-selected → Enter sends → row animates ring-progress in place. Arrow keys override the selection.

**First run:** zones 1, 3, 4 render normally. Zone 2 collapses to an empty-state block: `No devices yet. Pair one to start sending.` with a primary `Pair a device` button.

**Secondary surfaces** (Settings, Pairing, Full Transfer History): each **replaces the popup body** with a 28 px back row at the top. Escape or the back row returns. Settings is reached via `,`. Pairing via `p` or the empty-state primary.

### Android app

Single scrolling surface. No bottom nav, no FAB, no drawer. Zones:

1. **Top app bar** (56 dp): device name as dynamic title, online dot, settings icon trailing.
2. **Hero card** (128 dp): the top online device, a single large row with two contextual verbs ("Send clipboard" / "Tap to pick file"). Tap sends if content is staged.
3. **Devices section** (64 dp rows).
4. **Activity section** (64 dp rows).
5. **Bottom bar** (56 dp): two text buttons — `Pair`, `Settings`. No FAB.

Navigation model: **single screen + full-screen routes for Settings and Pairing**. Transfer detail opens as a bottom sheet. **No tablet two-pane in v1.**

**Send entry points:** direct open + tap hero; system Share Sheet into Beam (opens pre-targeted to top online device with one primary); long-press a non-default device row to make it the active target.

**Explicit buttons, not swipe gestures, for accept/retry in v1.** Swipe gestures collide with text selection, TalkBack long-press, and Gmail muscle memory.

---

## 5. Interaction model

**Send (Chrome):** open popup → content staged from clipboard or drop → hero row highlights top online device → Enter sends → row's avatar slot crossfades to an inline ring-progress → transfer state lives in the SW, not the popup → if popup closes mid-transfer and reopens later, the row correctly renders whatever state the SW has (in-progress, complete, failed).

**Send (Android):** hero card tap with content staged; or Share Sheet into Beam with a confirmation surface pre-selecting the top online device; or long-press a different row to retarget. No swipe-to-send.

**Receive:** **all paired devices auto-accept.** Incoming transfers appear in the Activity list with ring-progress. On completion, the row resolves to a persistent success state with a mono relative timestamp and an `Open` chip for files. No toast for the common case. Android posts a system notification only when the app is backgrounded. **Unpairing is the revocation mechanism.**

**Pairing ceremony (three full-surface steps):**
1. **QR + PIN reveal.** 240 px QR on Chrome, 320 dp on Android. 8-digit PIN rendered in large tabular mono. Live 60-second countdown. On expiry: the surface auto-regenerates, not a dead zero.
2. **SAS verification with 4 emoji** — keeps the current emoji approach. The research-backed property (visual shape resistant to transcription error, works across scripts, more robust than 4-digit numeric for comparison by human eye) is worth more than aesthetic consistency.
3. **Naming.** Text input pre-filled with a memorable two-word auto-alias (`Swift Heron`, `Copper Fox`). Editable inline.

**Errors:** inline on the owning row for transfer errors (`Failed — retry` chip appears right-aligned). Single-line banner at top of the popup body for surface-wide errors (`Relay unreachable. Retrying in 3 s.`). No modals. Offline devices are non-interactable.

**Keyboard (Chrome):** `Enter` send, `↑/↓` select device, `Esc` back or close, `p` pair, `,` settings, `Tab` cycle sections. **No slash commands.** These six shortcuts live in the static footer chip row.

**Gestures (Android):** tap, long-press (set active target), explicit button taps for everything else. No swipes in v1.

---

## 6. Visual language

### Accent

**Signal Cyan** `#5BE4E4` on dark. Rationale: cyan reads as signal/transmission/relay; it's not taken by the direct aesthetic peers (Linear purple, Raycast red, Arc gradient, Superhuman indigo). **Must pass a contrast audit at 12% subtle-fill on `bg/0` and `bg/1` before v1 ship.**

### Dark-mode tokens (v1 ship)

```
bg/0          #0A0B0D   canvas
bg/1          #111316   surface (lists, cards)
bg/2          #181A1F   elevated (bottom sheets, popovers, full-surface replacements)
border/subtle #1F232A
border/strong #2A2F38
text/hi       #F2F4F7   primary content, labels, device names
text/mid      #9BA3AE   metadata, section headers
text/lo       #6B7280   disabled, placeholder, inactive
accent        #5BE4E4   focus ring, selection fill, progress fill, primary button
accent-hover  #7BEDED
accent-12     #5BE4E4 @ 12%   selection-fill background
success       #5FB88C   muted, not bright
success-12    #5FB88C @ 12%
warning       #D4A55F
danger        #D46F6F
danger-12     #D46F6F @ 12%
online        #5FB88C   status dot
offline       #6B7280   status dot
```

Light mode is **not** defined in v1.

### Type

- **Primary sans:** Inter Variable (Chrome via `@font-face` and `web_accessible_resources`; Android uses **system stack**, not bundled). Tabular figures on all numerics.
- **Mono:** Chrome uses JetBrains Mono bundled. Android uses the system monospace stack.
- **Scale (px / sp):** 11 / 12 / 13 / 14 / 16 / 22. Body is 13, metadata 12, section headers 14 semibold, surface titles 16 semibold, pairing display 22.
- **Weights:** 400, 500, 600. Never 700.
- **Tabular figures always** for percent, size, speed, PIN, SAS labels, relative timestamps.

### Spacing, radius, elevation, motion

- **Spacing scale:** 4 / 8 / 12 / 16 / 20 / 24 / 32. Rows: 12 px horizontal, 8 px vertical on Chrome; 16 dp horizontal, 12 dp vertical on Android.
- **Radius:** `sm 4` (chips) / `md 6` (buttons, inputs, row fills) / `lg 8` (popovers, bottom sheets) / `pill 999`. Nothing above 8.
- **Elevation:** zero shadows in dark mode. Depth comes from `bg/0 → bg/1 → bg/2` steps and `border/subtle` hairlines.
- **Motion:** `fast 120 ms`, `base 180 ms`, `slow 260 ms`. Easing `cubic-bezier(0.2, 0, 0, 1)` for enters, `cubic-bezier(0.4, 0, 1, 1)` for exits. No hover motion — fill only.

---

## 7. Component inventory

**Atomic primitives:**
- Status dot (6 px / 8 dp, online/offline/warning/danger variants)
- Avatar glyph (20 px / 24 dp, 1.5 px stroke device-type icon, never photos)
- Key chip (mono 11 px, for SAS labels and PIN groupings)
- Shortcut chip (mono 11 px, static set in Chrome footer only)
- Button (primary accent / secondary bordered / ghost text-only — 28 px / 40 dp)
- Text input (32 px / 48 dp, `border/subtle` default, `accent` focus ring, no inner shadow)
- Switch (standard, accent-on)
- Icon (Lucide 1.5 px stroke, 16 px canonical / 20 px toolbar)

**Composite:**
- **Device row** — the unit-of-truth component. 36 px / 64 dp. Grid: `[status dot][avatar glyph][name + mono metadata][trailing action]`. Selection = 2 px accent left border + `accent-12` fill.
- **Transfer row** — same grid as device row. Avatar slot becomes an **inline ring-progress** (18 px / 28 dp, 2 px stroke, real percent) during transfer; crossfades to a success or fail glyph on completion.
- **History row** — denser, no ring, just a glyph.
- **Empty-state block** — single sentence + one primary button, centered. No illustration.
- **Popover card / bottom sheet** — `bg/2`, `radius-lg 8`, hairline border.
- **Inline banner** — surface-wide errors only. `bg/2` fill, `border/subtle`, single-line text, optional right-aligned action.

**Deferred from v1:** command palette / slash commands; context-sensitive keyboard footer renderer; relay latency chip; clipboard row composite; rotating placeholder; tablet two-pane; drag-from-Files on Android; swipe gestures; persistent bottom action bar; light mode tokens.

---

## 8. Cross-platform parity

| Aspect | Chrome | Android | Parity |
|---|---|---|---|
| Color tokens | identical hex | identical hex | **IDENTICAL** |
| Accent | Signal Cyan | Signal Cyan | **IDENTICAL** |
| Type scale | Inter @ `@font-face` | System sans with tabular figures | **NEAR** (same scale, different rendering) |
| Mono | JetBrains Mono bundled | System mono | **NEAR** |
| Row semantics | device / transfer / history | device / transfer / history | **IDENTICAL** |
| Ring-progress | 18 px | 28 dp | **IDENTICAL semantics, scaled** |
| Icon set | Lucide 1.5 px | Lucide 1.5 px | **IDENTICAL** |
| Row height / padding | 36 px / 12 px | 64 dp / 16 dp | DIVERGES (touch target) |
| Primary surface | popup with body replacement | scrolling single screen | DIVERGES |
| Secondary surface | replace popup body | full-screen Compose route | DIVERGES |
| Input mode | keyboard-first | tap + long-press | DIVERGES |
| Transient error | inline banner | snackbar | DIVERGES |
| Elevation | zero shadows | zero shadows | **IDENTICAL** |
| Motion tokens | identical | identical | **IDENTICAL** |

---

## 9. Voice + microcopy

Verb-first imperatives. Sentence case. No exclamation points. No "oops", "whoops", "awesome". One sentence per error, blaming the system: `Relay unreachable. Retrying in 3 s.` Empty states are dry: `No devices yet. Pair one to start sending.` Pairing uses matching language, not "connecting": `Do these emoji match on both devices?` Notifications are structural: `Mac-Studio sent receipt.pdf (2.4 MB)`. Never use the product name in UI copy except the manifest and app icon.

---

## 10. Decisions locked in

Defaults approved 2026-04-11:

- **D1 Trust policy:** all paired devices auto-accept; unpairing is the revocation mechanism.
- **D2 SAS verification:** 4 emoji (not numeric).
- **D3 Accent color:** Signal Cyan `#5BE4E4`, pending contrast audit at 12% alpha.
- **D4 Popup lifetime:** Chrome popup dismisses on focus loss; transfer state lives in the SW; popup re-renders on reopen. Pairing ceremony also lives in popup and must resume at the correct step.
- **D5 Android fonts:** system stack with tabular figures, not bundled Inter.
- **D6 Device aliases:** pre-fill two-word memorable alias, editable.
- **D7 Received files:** auto-save to `Beam` subfolder in Downloads with a `Reveal` chip.
- **D8 Android notifications:** fire only when app is backgrounded.

---

## 11. Phased implementation roadmap

Four phases, each independently shippable.

**Phase 1 — Design system foundation (1-2 weeks).** Color tokens, type tokens, spacing/radius/motion tokens in a shared source. `@font-face` Inter + JetBrains Mono bundled in the extension. Android `Typography.kt` with tabular-figures feature. Lucide icon set on both platforms. Button / Input / Switch / StatusDot / Icon primitives re-skinned. Contrast audit on Signal Cyan at 12% alpha.

**Phase 2 — Main loop redesign (2-3 weeks).** Chrome popup and Android main screen redesign per `2026-04-11-phase2-screen-specs.md`. Device row + transfer row unified component. Inline ring-progress. Inline error states. SW-side transfer state store. Static keyboard footer on Chrome. Fix the high-severity friction items.

**Phase 3 — Pairing + settings redesign (1-2 weeks).** Pairing flow three full-surface steps on both platforms with emoji SAS preserved. Memorable two-word alias pre-fill. Settings full redesign: Identity, Paired Devices (with explicit Unpair), Behavior, About. Fix medium-severity friction items.

**Phase 4 — Motion, polish, notifications (1 week).** Motion tuning. Notification redesign on Android. Empty-state microcopy pass. Final contrast and accessibility audit. QA matrix (N=2, N=20, 100-file batch, pairing interrupted, network dropout).

**Phase 5 — Deferred (v1.1+):** light mode; tablet two-pane; swipe gestures; bundled fonts on Android; command palette; drag-from-Files; full-surface history view.

---

## 12. Explicit non-goals

No hero illustrations. No emoji iconography in chrome/Compose. No marketing gradients. No splash screen. No tabs. No drawer / hamburger. No FAB on Android. No bottom nav on Android. No avatar-grid picker. No modal error dialogs. No heads-up notifications as primary surface. No skeleton loaders. No Material-filled or SF Symbols filled icons. No confetti, no bounce, no shake. No photographic avatars. No "Beam" in UI copy (except manifest and app icon). No light mode in v1. No slash commands / command palette in v1. No swipe gestures on Android in v1. No tablet two-pane in v1. No bundled fonts on Android in v1.
