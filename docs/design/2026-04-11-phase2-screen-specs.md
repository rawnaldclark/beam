# Beam Phase 2 — Screen Specs: Chrome Popup and Android Main

**Status:** Approved (defaults on open questions)
**Date:** 2026-04-11
**Scope:** Main-loop screens users touch 95% of the time — Chrome extension popup (380×500) and Android main screen, across all states. Device rows and transfer rows are treated as one unified primitive with variants.

**Out of scope:** Settings screens, pairing ceremony, full transfer history, notification redesign. Those belong to Phase 3 and Phase 4.

**Design tokens referenced throughout** — see `2026-04-11-design-direction-v1.md` §6 for values.

---

## Chrome Popup (380 × 500)

### Screen 1: Main (populated)

```
+----------------------------------------------------+  <- 380px
| [A] beam  . pixel-9-pro                       [B]  |  32px
+----------------------------------------------------+
| STAGED                                         [C] |  20px
| "the quick brown fox jumped over..."     txt  148B |  36px
+----------------------------------------------------+
| DEVICES                                        [D] |  20px
| >* pixel-9-pro         12ms        send  [E]      |  36px
|  * macbook-air         34ms        send  [E]      |  36px
|  o desk-linux           --         offline         |  36px
|                                                    |
| ACTIVITY                                       [F] |  20px
| ^ receipt.pdf    2.4 MB  macbook-air    2m         |  36px
| v hello.jpg      1.1 MB  pixel-9-pro    1h         |  36px
+----------------------------------------------------+
| [G] enter send   arrows select   /  filter   ? help|  28px
+----------------------------------------------------+
                                                        500px
```

**Component legend:**
- [A] Identity strip: beam wordmark + current-device alias + online dot
- [B] Settings gear icon button (20 px, toolbar size)
- [C] Staged payload strip: type chip + preview + size. Visible only when clipboard/drop content exists
- [D] Section header `Devices`
- [E] Device row — selected variant has 2 px accent left rail + `accent-12` fill. Trailing slot shows contextual verb "send"
- [F] Section header `Activity`
- [G] Shortcut footer — static chip set, always visible

**Tokens:**
- Canvas `bg/0`. Staged strip `bg/1` with `border/subtle` 1 px top+bottom hairlines. Selected row: 2 px `accent` left border, `accent-12` fill; non-selected on `bg/0`
- Device alias: `text/hi`, base 13, weight 500. Relay latency: `text/lo`, `font-mono`, xs 11, tabular. Section headers: `text/mid`, sm 12, weight 500
- Status dot: online 8 px circle, offline ring-only. Padding: 12 px horizontal, 8 px vertical

**Interactive affordances:**
- `Enter`: commits staged content to currently selected device row
- `Up/Down`: move selection; skips offline rows
- `/`: enter filter state (Screen 4)
- `Esc`: clear staged payload first, else close popup
- `Ctrl+V`: stage clipboard
- Drop on popup body: stage file, auto-select top online device
- Click device row: selects and immediately commits (one-click parity with Enter)

**State transitions:**
- Enter or row click → Screen 5 (transfer in progress), motion `fast` 120 ms ring fade-in, ease-out
- `/` → Screen 4, motion `fast` on filter input height expand
- Staged strip enters/exits with motion `base` 180 ms height collapse

**Copy:**
- Section headers: `Devices`, `Activity`, `Staged`
- Row verbs: `send` (trailing), `offline` (on offline rows, `text/lo`)
- Footer chips: `enter send`, `arrows select`, `/ filter`, `? help`
- Staged type chip: `txt`, `img`, `file`, `url`

---

### Screen 2: Main (empty activity)

Delta from Screen 1: no Staged strip, no Activity rows. Devices section still populated.

```
+----------------------------------------------------+
| beam  . pixel-9-pro                          gear  |  32px
+----------------------------------------------------+
| DEVICES                                            |  20px
| >* pixel-9-pro         12ms        send            |  36px
|  * macbook-air         34ms        send            |  36px
|  o desk-linux           --         offline         |  36px
|                                                    |
| ACTIVITY                                           |  20px
|                                                    |
|    Nothing sent yet. Paste or drop to start.       |  36px, centered
|                                                    |
+----------------------------------------------------+
| enter send   arrows select   /  filter   ? help    |  28px
+----------------------------------------------------+
```

Activity empty-slot line: `text/lo`, sm 12, centered within the section rect, 24 px top pad. One line only, no illustration.

Copy: `Nothing sent yet. Paste or drop to start.`

All interactive affordances identical to Screen 1, except Enter without stage is a no-op (subtle shake on the staged strip slot; motion `fast`).

---

### Screen 3: Main (first-run, zero paired devices)

```
+----------------------------------------------------+
| beam                                         gear  |  32px
+----------------------------------------------------+
|                                                    |
|                                                    |
|   No devices paired.                               |  lg 16
|   Pair one to start sending.                       |  base 13, text/mid
|                                                    |
|   [  Pair a device  ]                              |  32px primary
|                                                    |
|                                                    |
+----------------------------------------------------+
| p pair   , settings                                |  28px
+----------------------------------------------------+
```

- Empty state block vertically centered, 32 px between lines, 24 px above button
- Heading `text/hi` lg 16 weight 600; subline `text/mid` base 13 weight 400
- Primary button: `accent` fill, `text/hi` (on-accent), 32 px tall, `radius-md 6`, 16 px horizontal pad. Full-width at 280 px, horizontally centered
- Copy: `No devices paired.`, `Pair one to start sending.`, button `Pair a device`
- Affordances: Enter triggers Pair a device; `p` triggers pairing; Esc closes
- Transition: Pair a device → pairing surface (Phase 3), motion `base` slide-left 180 ms
- Footer reduced to two chips: `p pair`, `, settings`

---

### Screen 4: Main (filter active)

Delta from Screen 1: filter input appears inline above the Devices list. Staged strip hides while filtering.

```
+----------------------------------------------------+
| beam  . pixel-9-pro                          gear  |
+----------------------------------------------------+
| [/] mac_                                      esc  |  32px filter bar
+----------------------------------------------------+
| DEVICES  2 of 3                                    |  20px
| >* macbook-air         34ms        send            |  36px
|  * mac-mini-studio     18ms        send            |  36px
|                                                    |
| ACTIVITY                                           |  20px
| ^ receipt.pdf    2.4 MB  macbook-air    2m         |  36px
+----------------------------------------------------+
| enter send   arrows select   esc  clear            |  28px
+----------------------------------------------------+
```

- Filter bar: `bg/1`, 1 px `border/subtle` bottom. Leading `/` glyph in `text/lo` `font-mono`. Input `text/hi` base 13. Trailing `esc` hint `text/lo` xs 11
- Section header gains result count `2 of 3` in `text/lo`, sm 12, tabular
- Filter matches are prefix + substring on device alias, case-insensitive
- Enter commits to the top filtered row. Esc clears filter and returns to Screen 1 with prior selection restored
- Motion: filter bar enters with `fast` 120 ms height expand ease-out; clear uses `fast` ease-in
- Copy: input placeholder `Filter devices`, footer `esc  clear`

**Default for open question:** filter applies to Devices only. Activity remains unfiltered but dimmed to `text/lo` while filter is active, reinforcing focus.

---

### Screen 5: Main (transfer in progress)

Delta from Screen 1: the targeted device row replaces its leading status dot with an inline ring-progress primitive. Two viewpoints — sender and receiver.

**Sender:**
```
 DEVICES
 >(42%) macbook-air      34ms     receipt.pdf  2.1/5.0MB
  *    pixel-9-pro       12ms     send
  o    desk-linux         --      offline
```

**Receiver (same row, incoming):**
```
 DEVICES
 >(78%) macbook-air      34ms     receipt.pdf  in
```

- Ring: 16 px canonical icon slot replaced by a 16 px ring. Track `bg/2`, fill `accent`, 1.5 px stroke. Percentage sits to the right of alias in xs 11 `text/mid` during active transfer
- Trailing slot replaces `send` verb with filename + bytes transferred / total, `font-mono` xs 11, tabular. Receiver shows filename + `in`; sender shows bytes pair
- During active transfer: row remains selectable but Enter is a no-op; `Ctrl+.` cancels the in-flight transfer
- Other device rows remain fully interactive; staged strip clears on commit
- Motion: ring progress is continuous, no tweened stepping. Ring fade-in on start uses `fast` 120 ms
- Copy: cancel hint appears in footer while a transfer is active: `enter send   arrows select   ctrl+. cancel`

---

### Screen 6: Main (transfer complete, holding)

Delta from Screen 5: 600 ms success-hold before the row returns to idle.

```
 DEVICES
 >(ok) macbook-air       34ms     sent  receipt.pdf  5.0MB
  *    pixel-9-pro       12ms     send
```

- Leading slot: ring replaced by a filled check glyph, 16 px, `success` color. Row background flashes to `success-12` for the 600 ms hold
- Trailing slot: `sent  receipt.pdf  5.0MB`, `text/hi` base 13 for the verb, `text/mid` for filename, `font-mono` for size
- Hold timing: 600 ms plateau, then 180 ms `base` ease-in fade back to Screen 1 idle state
- Row does NOT reorder during the hold (default for open question — reordering into Activity happens after the hold completes)
- Keyboard: Enter during the hold window starts a new transfer immediately
- Copy: verb `sent` (sender), `received` (receiver)

---

### Screen 7: Main (transfer failed, retry available)

```
 DEVICES
 >(!) macbook-air        34ms     transfer dropped   [retry]
  *   pixel-9-pro        12ms     send
```

- Leading slot: ring replaced by a 16 px danger triangle glyph, `danger` color. Row fill `danger-12`. Row remains selected
- Trailing slot: error message `text/hi` base 13 weight 500, followed by a ghost chip `retry` that slides in from the right, `radius-pill 999`, 1 px `border/strong`, `text/hi` sm 12, 8 px horizontal pad, 20 px tall
- Slide-in motion: `base` 180 ms ease-out, 12 px travel
- Keyboard: Enter retries. R also retries. Esc dismisses the error, reverting to idle
- Copy: error string one sentence, blames the system, sentence case, no exclamation. Examples: `transfer dropped`, `relay timed out`, `peer closed the link`. Chip label: `retry`
- Footer delta while error row is selected: `enter retry   esc dismiss   arrows select`

---

### Screen 8: Main (surface-wide error banner)

```
+----------------------------------------------------+
| beam  . pixel-9-pro                          gear  |
+----------------------------------------------------+
| (!) relay unreachable. retrying in 4s.       retry |  28px banner
+----------------------------------------------------+
| DEVICES                                            |
|  * pixel-9-pro         --         unavailable      |  dimmed
|  * macbook-air         --         unavailable      |  dimmed
|  o desk-linux          --         unavailable      |  dimmed
|                                                    |
| ACTIVITY                                           |
| ^ receipt.pdf    2.4 MB  macbook-air    2m         |  dimmed
+----------------------------------------------------+
| retry  r                                           |  28px
+----------------------------------------------------+
```

- Banner: `bg/1`, 1 px `border/subtle` bottom, leading 16 px danger glyph, message `text/hi` base 13, trailing `retry` ghost chip. Countdown is tabular, updates every second
- Device rows: text shifts to `text/disabled`, status dots desaturated, verb slot shows `unavailable`. Rows not selectable; arrow keys no-op; Enter no-op
- Activity rows dim to `text/lo` but remain click-targetable for re-open
- Motion: banner enters with `base` 180 ms slide-down from top
- Copy: `relay unreachable. retrying in 4s.` Button chip: `retry`. Footer: `retry  r`
- Transition out: on reconnect, banner exits `base` 180 ms slide-up

---

### Screen 9: Main (offline device highlighted)

Delta from Screen 1: user has arrow-keyed onto an offline row. Normally offline rows are skipped.

```
 DEVICES
  *    pixel-9-pro        12ms        send
  *    macbook-air        34ms        send
 >o    desk-linux          --         last seen 2h ago
```

- Selected offline row: 2 px `accent` left rail (dimmed to `accent` at 60%), no `accent-12` fill (reserved for actionable selection). Trailing slot replaces `send` with `last seen 2h ago`, `text/lo` sm 12
- Enter is a no-op and produces a `fast` 120 ms horizontal shake (4 px, two cycles) on the selected row
- Footer: `enter send` chip replaced with a dimmed `offline  cannot send` chip at `text/disabled`
- Up/Down from here jumps back to the nearest online row on first press; a second Up/Down continues the normal walk
- Copy: `last seen 2h ago`, `offline  cannot send`

**Default for open question:** clicking an offline row does NOT queue a send. Queued sends are out of Phase 2 scope.

---

## Android Main Screen

Android uses the same unified row primitive at 64 dp row height, but elevates the top online device into a persistent Hero Card at the top of the screen to match thumb ergonomics. Typography sp values match the px scale; spacing tokens in dp.

### Screen 10: Main (populated)

```
+----------------------------------------+
| beam           pixel-9-pro    . [gear] |  56dp app bar
+----------------------------------------+
|                                        |
|  +----------------------------------+  |
|  |  macbook-air          34ms       |  |  128dp hero card
|  |  .  online                       |  |
|  |                                  |  |
|  |  Tap to pick file                |  |
|  |  Send clipboard                  |  |
|  +----------------------------------+  |
|                                        |
|  OTHER DEVICES                         |
|  * pixel-tablet        22ms    send    |  64dp
|  o desk-linux          --      offline |  64dp
|                                        |
|  ACTIVITY                              |
|  ^ receipt.pdf  2.4 MB  macbook  2m    |  64dp
|  v hello.jpg    1.1 MB  pixel    1h    |  64dp
+----------------------------------------+
|  [ Pair ]              [ Settings ]    |  56dp bottom bar
+----------------------------------------+
```

**Component legend:**
- Hero card: top online device, 128 dp tall, `radius-lg 8`, `bg/1`, 1 px `border/subtle`. Two-verb footer: `Tap to pick file`, `Send clipboard`
- Other devices list — same row primitive at 64 dp
- Activity list — history variant
- Bottom bar: two text buttons, 56 dp tall, `text/hi` md 14 weight 500

**Tokens:**
- Canvas `bg/0`. Hero `bg/1`. Rows `bg/0` with `border/subtle` top hairline between items. Section headers `text/mid` sm 12 weight 500, 16 dp leading pad, 12 dp top pad
- Hero device alias: `text/hi` xl 22 weight 600. Latency: `text/lo` `font-mono` sm 12 tabular. Verb rows: `text/hi` md 14 weight 500, each 40 dp tall, divider `border/subtle`

**Interactive affordances:**
- Tap hero `Tap to pick file`: opens system file picker
- Tap hero `Send clipboard`: commits clipboard to hero target
- Tap other device row: retargets hero card to that device (Screen 17)
- Long-press any device row: retarget hero without committing
- Share-sheet into Beam: stages payload and surfaces hero card in send-ready mode
- Pull-to-refresh: re-pings relay

**State transitions:**
- Commit → Screen 13 (in progress), motion `fast` 120 ms ring fade in
- Long-press → Screen 17 retarget, motion `base` 180 ms hero alias crossfade

**Copy:**
- Section headers: `Other devices`, `Activity`
- Hero verbs: `Tap to pick file`, `Send clipboard`
- Bottom bar: `Pair`, `Settings`

---

### Screen 11: Main (empty activity)

Delta from Screen 10: Activity section replaced with a one-line dry empty state.

Activity block: 64 dp tall, centered `text/lo` sm 12.

Copy: `Nothing sent yet. Share into beam to start.`

All other regions identical to Screen 10.

---

### Screen 12: Main (first-run, zero paired devices)

```
+----------------------------------------+
| beam                           [gear]  |
+----------------------------------------+
|                                        |
|                                        |
|      No devices paired.                |  xl 22 text/hi
|      Pair one to start sending.        |  base 13 text/mid
|                                        |
|      [    Pair a device    ]           |  56dp primary
|                                        |
+----------------------------------------+
```

- Vertically centered block. Primary button: `accent` fill, 56 dp tall, full-width minus 32 dp lateral margin, `radius-md 6`
- Copy: `No devices paired.`, `Pair one to start sending.`, button `Pair a device`
- Tap: launches pairing surface (Phase 3)
- No bottom bar in first-run; only the header gear remains

---

### Screen 13: Main (transfer in progress)

Delta from Screen 10: a Transfer Row appears immediately below the hero card, before `Other devices`. The hero card itself remains idle and re-targetable.

```
|  HERO ... (as Screen 10) ...          |
|                                        |
|  IN PROGRESS                           |
|  (68%) macbook-air   receipt.pdf       |  64dp
|        3.4 / 5.0 MB              [x]   |
|                                        |
|  OTHER DEVICES                         |
```

- Leading ring: 24 dp canonical ring, `accent` stroke, `bg/2` track, 2 dp stroke weight
- Two-line row: line 1 alias + filename (`text/hi` base 13 + `text/mid`); line 2 byte progress (`font-mono` sm 12 tabular) + trailing cancel `x` icon button (40 dp tap target)
- Copy: section header `In progress`. Cancel icon content description: `Cancel transfer`
- Motion: ring continuous, row enters `fast` 120 ms slide-down

---

### Screen 14: Main (transfer complete / success)

Delta from Screen 13: In-progress row transitions to a success-hold for 600 ms, then collapses into the top of the Activity list.

```
|  IN PROGRESS                           |
|  (ok) macbook-air   sent receipt.pdf   |
|       5.0 MB                           |
```

- Ring replaced by 24 dp filled check glyph, `success` color. Row fill `success-12` for the 600 ms hold
- After hold: row exits `base` 180 ms ease-in height collapse; a new Activity row materializes at top with `fast` 120 ms fade-in
- Copy: verb `sent` (outgoing), `received` (incoming)

---

### Screen 15: Main (transfer failed)

Delta from Screen 13: row shifts to danger state with inline retry.

```
|  IN PROGRESS                           |
|  (!) macbook-air   transfer dropped    |
|      [ retry ]                  [x]    |
```

- Leading 24 dp danger triangle. Row fill `danger-12`. Error text one sentence, `text/hi` base 13
- Inline retry chip: `radius-pill 999`, 1 px `border/strong`, 36 dp tall (48 dp tap target via surrounding padding), `text/hi` sm 12, label `retry`
- Tap retry: returns to Screen 13. Tap x: dismisses row entirely
- Copy examples: `transfer dropped`, `relay timed out`, `peer closed the link`

---

### Screen 16: Main (surface-wide error)

```
+----------------------------------------+
| beam           pixel-9-pro    . [gear] |
+----------------------------------------+
| (!) relay unreachable. retrying in 4s. |  48dp banner
|                                 retry  |
+----------------------------------------+
|  HERO (dimmed)                         |
|  +----------------------------------+  |
|  |  macbook-air        --           |  |
|  |  .  unavailable                  |  |
|  |                                  |  |
|  |  unavailable                     |  |
|  +----------------------------------+  |
|                                        |
|  OTHER DEVICES (dimmed)                |
```

- Banner: `bg/1`, 1 px `border/subtle` bottom, 48 dp tall, leading 20 dp danger glyph, countdown text tabular, trailing `retry` text button
- Hero card and all rows dim to `text/disabled`. Hero verbs collapse to a single `unavailable` line, non-tappable. Activity rows remain tappable for re-open
- Motion: banner enters `base` 180 ms slide-down; countdown updates every second
- Copy: `relay unreachable. retrying in 4s.`, `unavailable`, `retry`

---

### Screen 17: Main (long-press to retarget)

Delta from Screen 10: user long-pressed `pixel-tablet` in Other devices. Hero card alias crossfades to the new target; verb rows re-bind.

```
|  HERO                                  |
|  +----------------------------------+  |
|  |  pixel-tablet         22ms       |  |
|  |  .  online                       |  |
|  |                                  |  |
|  |  Tap to pick file                |  |
|  |  Send clipboard                  |  |
|  +----------------------------------+  |
|                                        |
|  OTHER DEVICES                         |
|  * macbook-air        34ms     send    |  <- demoted, was hero
|  o desk-linux         --       offline |
|                                        |
|  [ undo retarget ]                     |  40dp ephemeral chip
```

- Long-press: 300 ms haptic trigger. On release, hero alias + latency crossfade `base` 180 ms
- Demoted prior-hero re-enters Other devices list at top with `fast` 120 ms fade-in
- Ephemeral undo chip: pinned 16 dp above bottom bar, 40 dp tall, `bg/1`, 1 px `border/subtle`, `text/hi` sm 12 label `undo retarget`. Auto-dismisses after 4 seconds with `base` ease-in fade
- Retarget is **session-scoped** (default for open question). On next app open, the original default hero returns unless explicitly pinned (pinning out of Phase 2 scope)

---

### Screen 18: Main (incoming transfer, backgrounded → foreground reopen)

User received a file while Beam was backgrounded. On reopen, the main screen renders a persistent Activity row reflecting the completed transfer, with a subtle `new` marker until acknowledged.

```
|  HERO ... (as Screen 10) ...           |
|                                        |
|  ACTIVITY                              |
|  v receipt.pdf  2.4 MB  macbook  now . |  64dp, dot = new marker
|  ^ hello.jpg    1.1 MB  pixel    1h    |
```

- New-marker dot: 6 dp `accent` circle, trailing the timestamp. Disappears on first scroll or tap
- Timestamp `now` for transfers completed within 60 seconds while backgrounded; otherwise relative (`2m`, `1h`)
- No banner, no toast, no modal. The main screen is the status surface; notifications (Phase 4) handle out-of-app signaling
- Tapping the row opens the file in its default handler and clears the new marker
- Motion: on app resume, new rows fade in `base` 180 ms ease-out with a 40 ms stagger per row (cap at 3 staggered rows)
- Copy: timestamp `now`, new-marker content description `New, unread`

---

## Open questions — defaults applied

All 8 open questions from the synthesis have defaults baked into the spec above:

1. **Filter scope:** Devices only; Activity dimmed but visible.
2. **Semantic 12% fills:** `success-12` and `danger-12` parallel `accent-12`; added to the token set.
3. **Offline click queue:** no queued sends in v1.
4. **Success-hold reordering:** after the 600 ms hold, not during.
5. **Android retarget persistence:** session-scoped.
6. **Ctrl+. cancel fallback:** Backspace while in-progress row is selected is the secondary binding.
7. **Android hero card duplication:** hero device does NOT appear in Other devices.
8. **`?` help shortcut:** transient keyboard cheat-sheet overlay, content TBD in Phase 4.
