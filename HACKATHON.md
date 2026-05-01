# TactileLens Hackathon Context

> **Purpose.** Bootstrap context for any agent or contributor joining this repo during the Qualcomm × Google Developer Hackathon. Read this *before* `ARCHITECTURE.md`. ARCHITECTURE.md tells you *how the code works*; this file tells you *why we are building it, what the rules are, and what the open risks are*.
> **Audience.** New Claude Code sessions, new teammates, anyone reviewing the submission.
> **Last updated.** 2026-04-30 (hackathon Day 1).

---

## 1. The hackathon

- **Event.** Qualcomm × Google Developer Hackathon (also marketed as "Qualcomm × LiteRT Developer Hackathon").
- **When.** April 30 – May 1, 2026.
- **Where.** Google offices, Sunnyvale, building MP4. In-person required.
- **Submission opens.** Thursday April 30 at 12:00 PM PT.
- **Submission closes.** Friday May 1 at 1:00 PM PT (HARD).
- **Voting + Judging.** Friday 1:00 – 4:45 PM PT.
- **Winners announced.** Friday ~5:00 PM PT.
- **Hardware.** Samsung Galaxy S25 Ultra, one per team (3–5 members).

## 2. Track and required tech

**Track 2 — Classical Models, Vision & Audio.**

- Models from Qualcomm AI Hub or LiteRT Hugging Face Model Zoo. AI Hub models do NOT require quantization or conversion.
- Target: Qualcomm Hexagon NPU on Snapdragon.
- Required tech: **Google LiteRT, using the new compile model API (NOT the legacy TF Lite interpreter).** This is the gate — anything still on the old interpreter is disqualified at Stage 1.

## 3. What must be submitted

**Required:**

- Public GitHub repo URL.
- Source code, assets, and instructions to make the project functional.
- Open-source license (MIT works), detectable AND visible at the top of the repo page.
- README with: app description, team names + emails, setup-from-scratch instructions, run + usage instructions.
- Devpost text description.

**Highly recommended (improves Presentation & Documentation score):**

- Tests + testing instructions.
- Notes section.
- References used while developing.
- Well-commented code.
- Demo video.

**Not yet checked in this repo (as of writing):**

- [ ] LICENSE file at repo root, visible in About.
- [ ] README updated with team names + emails + setup + run instructions.
- [ ] Devpost description drafted.
- [ ] Demo video shot.

## 4. Judging — two stages

**Stage 1 (pass/fail gate).** Did you actually use LiteRT? Does the project fit the theme? Fail → not scored.

**Stage 2 (four equally weighted criteria).**

1. **Technological Implementation** — NPU utilization, latency, performance, energy efficiency.
2. **Application Use-Case and Innovation** — problem solving, creativity, uniqueness, UX.
3. **Deployment and Accessibility** — ease of installation and use.
4. **Presentation and Documentation** — clarity, code quality, documentation.

**Tie-breaker (verbatim from the rules).** *"If two or more Submissions are tied, the tied Submission with the highest score in the first applicable criterion listed above will be considered the higher scoring Submission."*

The first criterion is **Technological Implementation**. NPU utilization is the single most leverage-able area. If LiteRT is not on the Hexagon NPU with verified logs, we lose the tie-breaker by default.

## 5. The "newly created" rule — OPEN RISK

Verbatim from Devpost rules: *"Project must be newly created by the Entrant after the start of the Hackathon Submission Period"* — i.e., after **April 30, 12:00 PM PT**.

The rule does NOT define "newly created" at the file/commit level. **Conservative interpretation:** re-typing from memory after noon PT. **Looser interpretation:** the project as a whole is new, and using prior scaffolding is fine.

**Status as of writing.** This repo (`TactileLens`) has commits dating before noon PT today, including the merged haptic + audio integration on `frontend-ui` and `haptic-audio-integration`. Open questions:

- Is this the submission repo, or do we create a fresh one at noon PT?
- If this is the submission repo, has the team requested official clarification from `support@devpost.com` (per Official Rules §12)?

**Action item.** Lead must resolve this before any new code is written. The answer determines whether subsequent integration work belongs in `TactileLens` or in a fresh submission repo. The team's pre-hackathon planning doc proposed a two-repo strategy (reference repo for prep, submission repo created fresh at noon PT) precisely to side-step this ambiguity.

## 6. Prizes

**Grand Prize (judges' choice, Qty 1)** and **Runner-Up (public vote, Qty 1).** Each winning team receives:

- Nintendo Switch 2 console for each team member.
- Qualcomm DevRel support to publish on Google Play Store.
- Blog + Live Stream opportunities.

Prize delivery: ~4 weeks after winner announcement. US winners may need a W-9; non-US a W-8BEN. All taxes are the winner's responsibility.

## 7. What this project is, today

- **Name.** TactileLens (originally planned as "Texture Sense"; renamed during pivot).
- **Pitch.** A snap-and-swipe Android app. User takes a photo of a surface; swipes finger across the photo on screen; haptic + audio feedback approximate touching that surface. Runs entirely on-device via the Snapdragon NPU.
- **Use case.** Low-vision accessibility (feel a photo a friend sent, or a product online); novel demo for sighted judges (blindfolded material identification).
- **Package.** `com.tactilelens.app`.

## 8. Divergences from the original "Texture Sense" plan

The team has a pre-hackathon planning doc (`PROJECT_KNOWLEDGE.md`, lives outside this repo) describing a project called "Texture Sense" with specific design choices. The current implementation has diverged. **Treat the planning doc as historical context, not as binding spec. This repo's `ARCHITECTURE.md` is the source of truth for code-level decisions.**

| Concern | Original "Texture Sense" plan | Current TactileLens implementation |
|---|---|---|
| Project name | Texture Sense | TactileLens |
| Package | `com.texturesense` | `com.tactilelens.app` |
| Demo materials | SANDPAPER / KNIT / BUBBLE_WRAP / MARBLE | WOOD / PAPER / ROCKS / SAND / FABRIC |
| Audio engine | libpd + Pure Data via Kortholt + Oboe | Sample-pack `AudioTrack` + gain envelope (Option A) |
| Haptic | Composition primitives + override library + procedural engine | Composition primitives, per-material recipes + procedural fallback (already shipped) |
| Architecture | 2-layer, interface-first, mocks-first, manual DI | Same — preserved |
| LiteRT integration | Via `LiteRTTextureAnalyzer` (Track 2 standard) | NOT YET WIRED — see §10 |
| Photo + swipe interaction | Compose `pointerInput` swipe events drive renderers | Same — already shipped |

## 9. What's been built so far

See `ARCHITECTURE.md` for the full layout. Headlines:

- **Composition root** (`AppContainer.kt`). Manual DI, single instance, lifecycle owned by `MainActivity`.
- **`AudioRenderer` interface + `SamplePackAudioRenderer` impl.** Sample-loop + gain envelope, native sample rate, no rate-scrub. Runs on the AudioTrack legacy path because Oboe + AAudio MMAP fails to bind on S25 Ultra.
- **`HapticRenderer` interface + `CompositionHapticRenderer` impl.** Per-material signature on touch-down, per-dot-crossing primitive on drag. Wobble jitter + Pulsar 1D selector for the procedural fallback.
- **`AnalysisClient` interface + `MockAnalysisClient` placeholder.** Cycles canonical materials, fakes 1.5s inference latency.
- **`InteractiveGridZone`.** Per-material visual density, dot-crossing detection, 40ms throttle.
- **`ResultsScreen`.** Material picker dropdown, axes display, grid container.

**Audio assets present.** `sand/loop.wav` is real; `wood/`, `glass/`, `rocks/` are sand-copies (placeholders). Sources for real loops listed in `app/src/main/assets/audio/SOURCES.md`.

**ML model.** None yet. ML team is still working on it.

## 10. What's missing for hackathon submission viability

Ranked by criticality, given the NPU-utilization tie-breaker:

| # | Item | Status | Notes |
|---|---|---|---|
| 1 | LiteRT compile model API in `build.gradle.kts` + a `LiteRTAnalysisClient` running on Hexagon NPU with verified logs | NOT STARTED | The single most load-bearing technical item. Drives the tie-breaker. |
| 2 | Real model from ML team (DTD-trained MobileNet-class classifier, ideally) | IN PROGRESS by ML team | We don't block on this — wire LiteRT against a placeholder model first. When the real model lands, integration is `.tflite` swap + post-processing tweak. |
| 3 | DTD-label → (Material?, axes) lookup table | NOT WRITTEN | Editorial decision, doesn't need ML team. Hardcoded in `LiteRTAnalysisClient` post-processing. |
| 4 | NPU-vs-CPU latency benchmark for the pitch | BLOCKED on #1 | Required for Stage 2 criterion 1 evidence. |
| 5 | Real audio loops for wood/glass/rocks | PLACEHOLDERS | Sources in `assets/audio/SOURCES.md`. Drop-in. |
| 6 | LICENSE file at repo root (MIT) | NOT VERIFIED | Required submission item. |
| 7 | README with team names + emails + setup + run instructions | NOT WRITTEN | Required submission item. |
| 8 | Devpost text description | NOT WRITTEN | Required submission item. |
| 9 | Demo video | NOT SHOT | Highly recommended (Presentation & Documentation score). |
| 10 | `local.properties` untracked from git | STILL TRACKED, points to teammate's path | Hygiene. Add to `.gitignore`. |

## 11. Architectural decisions locked from prior sessions

These are the locked decisions from the alignment session that produced the current haptic + audio integration. **Do not re-litigate without surfacing in the team channel first.** Full rationale lives in `ARCHITECTURE.md` §10.

1. Hybrid axis-driven strategy (Strategy B with kinda A polish): per-material polish for canonical materials, axis-driven procedural for null material.
2. ML contract: axes today, `play(recipe)` as documented future hook.
3. Audio: samples-only, no DSP renderer.
4. Procedural haptic: improved (Wobble jitter + Pulsar 1D selector).
5. Grid: per-material density, event-driven dot crossings, 40 ms throttle.
6. Frontend boundaries: integration touches grid + ResultsScreen params + MainActivity wiring; not splash, theme, scan animation, layout.
7. Package: `com.tactilelens.app`.

## 12. Open questions

Update this list as questions resolve. Each item names who unblocks it.

**Hackathon-administrative:**

- [ ] Is `TactileLens` the submission repo, or do we create a fresh one at noon PT? — **Lead, answer before any new code is written.**
- [ ] Has the team submitted a written request for clarification on "newly created" to `support@devpost.com`? — **Lead.**
- [ ] Are AI coding assistants (Copilot, Cursor, Claude Code) explicitly allowed during the build? Confirmed not addressed in public rules. — **Lead, ask on Discord/Slack.**
- [ ] Confirm no teammate has prior Qualcomm-funded work that touches this project (per the financial-or-preferential-support disqualification clause). — **Lead.**

**Technical:**

- [ ] Does AI Hub host an NPU-ready DTD-trained MobileNet, or do we use ImageNet MobileNet as a placeholder? — **ML 1, by hour 2 of the build.**
- [ ] What is the final DTD-label → (Material?, axes) lookup table? — **Lead, editorial.**
- [ ] What is the post-pivot demo-objects list, given the materials are WOOD / PAPER / ROCKS / SAND / FABRIC? Need to bring physical samples for all five (e.g., wood block, sheet of paper, gravel/pebble, sand sample, knit scarf). — **Lead, by Day 1 morning at venue.**

**Procedural-future (not blocking the hackathon, documented for post-event):**

- [ ] If/when ML migrates to wire-format-B (emitting `HapticRecipe` directly), what's the recipe shape? — **ML team, post-hackathon.** Today: ML emits axes; renderer derives recipes. The 5 questions originally listed in `ARCHITECTURE.md` §12 are moot under the LiteRT classical track because the model emits class probabilities, not haptic primitives.

## 13. Where things live

**This repo (`TactileLens`):**

- `HACKATHON.md` — this file. Hackathon constraints + open risks.
- `ARCHITECTURE.md` — technical architecture source of truth.
- `app/src/main/assets/audio/SOURCES.md` — audio loop provenance + sourcing instructions.
- `app/src/main/java/com/tactilelens/app/ui/INTEGRATION_GUIDE.md` — original frontend integration note (pre-integration shape; superseded by ARCHITECTURE.md but retained for context).

**External (live outside this repo):**

- `PROJECT_KNOWLEDGE.md` — the team's pre-hackathon planning doc for "Texture Sense." Historical context only; superseded for code-level decisions by this repo's `ARCHITECTURE.md`.
- `texture-sense-reference/` (sibling repo at `~/Projects/TextSense/texture-sense-reference`) — lab-harness work and research notes on haptic + audio that informed the current implementation. Specifically `specs/haptics-lab.md` and `specs/plans/2026-04-30-groundwork-*.md`.

## 14. Working principles for the next 25 hours

From the team's self-imposed rules, which still apply:

1. **Two layers, not three.** UI + Data. No domain layer.
2. **Single Gradle module.** No multi-module.
3. **Manual DI through `AppContainer`.** No Hilt, no Dagger.
4. **Interfaces first, mocks first.** Already true in this repo.
5. **Lock data contracts in hour 0.** Already locked. `AnalysisResult`, `TextureAxes`, `Material`, `HapticRecipe`, the renderer interfaces — frozen.
6. **NPU is non-negotiable by hour 6.** If LiteRT is not running on Hexagon NPU with verified logs by hour 6, that is a Sev 1 — drop other work until it is fixed.
7. **Threading rules are non-negotiable.** See `ARCHITECTURE.md`.
8. **Must-ship is sacred. Nice-to-have is optional. Cut without remorse.**
9. **Stand-up every 4 hours, 10 minutes, blockers only.**
10. **Person 5 (Devpost Rep) is the one who clicks submit.** Lead is fixing bugs at 12:55 PM, not filling out forms.
11. **Honesty in the pitch.** Don't claim "see textures through your phone" (that's vOICe / months of training). Do claim what Stiles & Shimojo 2015 supports for naive cross-modal identification of maximally-distinct stimuli.

## 15. Anti-goals

Do not:

- Add login, account, cloud sync.
- Train a model inside the app (training in Colab; only inference on-device).
- Add background services. App is foreground.
- Add multi-user or shared state.
- Add analytics SDK (privacy is part of the pitch).
- Multi-module Gradle.
- Hilt or Dagger.
- Compose Navigation library — single screen.
- Multi-screen architecture.
- Copy-paste code between the reference repo and submission repo. Re-type from memory.
- Live-camera streaming pipeline (KILLED in the pivot to photo + swipe).

If you find yourself doing one of these mid-build, stop.

---

## End of document

Pair this with `ARCHITECTURE.md` for full context. Update §10 (missing items), §12 (open questions), and §11 (locked decisions) as the build progresses.
