# Velo Client

A cheat-free, anti-cheat-safe, all-in-one QoL/performance/server-testing
Fabric client mod for Minecraft 1.21.11, in the spirit of Lunar Client /
Badlion but built explicitly for legitimacy on strict-anticheat servers.

*Everything you're allowed to have, nothing you're not.*

See [SAFETY.md](SAFETY.md) for a module-by-module breakdown of what every
feature does and why it doesn't provide an unfair advantage.

## Status

This repository contains a working Fabric mod with 41 modules across
Performance, Rendering, HUD, Server Tools, QoL, Utility, Debug, and
Cosmetics, plus a companion JavaFX launcher shell. It has been smoke-tested
in a real launched client (`./gradlew runClient`), not just compiled.

The marketing site (source and copy under [`velo-site/`](velo-site/)) is
live at **https://velo-client-mc.vercel.app** — including the Imprint and
Privacy Policy required for a project with an identifiable operator.

### Implemented

- **Gradle + Fabric Loom project** targeting Minecraft 1.21.11 / Java 21,
  plus a `launcher` Gradle subproject (JavaFX).
- **Module system**: `Module` / `AbstractModule` / `ModuleRegistry`
  ([src/main/java/net/veloclient/velo/module/](src/main/java/net/veloclient/velo/module/)),
  with category + safety-tag metadata, an optional `Configurable` interface
  for modules with adjustable settings, and a `manifest.json` exporter that's
  the single source of truth for both the in-game panel and the launcher.
- **A custom in-game widget toolkit** ([src/client/java/net/veloclient/velo/client/gui/widget/](src/client/java/net/veloclient/velo/client/gui/widget/))
  instead of vanilla's beveled-gray buttons: an animated flat button, a
  pill-shaped toggle switch with a sliding knob, a filled-track slider, and a
  scrollable row region. `VeloWindow` ([gui/window/](src/client/java/net/veloclient/velo/client/gui/window/))
  is the shared draggable-window base every panel is built on - grab the
  header to move it, click ✕ to close, with an animated open/close
  scale+fade. All panels (mod menu, per-module settings, theme editor, cape
  library, log viewer) are built on this, sized to fit their content instead
  of overflowing the screen.
- **Config, profile, and theme systems** under `~/.velo-client/` (per-module
  JSON, per-server profile snapshots with address-pattern auto-matching, a
  Safe Mode master switch, and a theme.json shared by the mod and launcher).
- **In-game panel** (Right Shift by default): draggable window, category
  tabs, search, per-module toggles, a gear icon opening real settings
  (sliders/toggles/choices/text fields) for any module that has them, a
  theme editor, a cape library screen with a real OS-native file picker for
  importing textures (PowerShell's `OpenFileDialog` on Windows, `osascript`
  on macOS, kdialog/zenity on Linux — no AWT/Swing, which doesn't reliably
  run headless-free inside this JVM), an "Open Mods Folder" button, and a
  live drag-and-drop HUD layout editor with per-element disable and grid
  snapping.
- **Profiles**: every module's settings plus the whole HUD layout are saved
  under named, switchable profiles (create/rename/delete from an in-game
  tab) instead of one global config — switching applies everything at once.
- **Command Keybinds**: bind any key to instantly run a chat command (e.g.
  `/spawn`); add, edit, and delete as many bindings as you want from their
  own screen, independent of vanilla's fixed keybinding list.
- **Velo Client branding** on the vanilla title screen and pause menu (a
  small watermark added via `ScreenEvents`, not a full screen replacement,
  so vanilla layout/mod compatibility is untouched).
- **HUD/QoL** (all Always Safe): FPS, ping, coordinates, clock, CPS, armor &
  durability, potion timers, held item, keystrokes (real bound-key labels,
  an actual mouse-button shape with live per-button CPS), action bar log,
  session stats, manual waypoints, toggle-sprint/sneak, and vanilla-style
  zoom (configurable FOV).
- **Performance & Rendering**: frame time graph, memory/GC monitor, GPU
  utilization, particle limiter, **Performance Boost** (presets plus
  individually-tunable view distance/entities/particles/VSync mode —
  Off/On/Adaptive — mipmaps and HUD update rate, all applied live),
  **PolyBlur** (motion-triggered blur using vanilla's own tested post-effect
  shader, not custom GLSL), a Sodium/Lithium/Iris/Starlight detector,
  independent FOV sliders (movement/nausea/damage tilt), and Full Bright.
- **Server Tools**: entity count overlay, chunk boundary + hitbox overlays
  (toggle vanilla's own F3 debug renderers), world border visualizer, a
  light-level/mob-spawn heatmap (via the game's `GizmoDrawing` debug-drawing
  API), a TPS/tick graph, a packet traffic monitor (via a `Mixin` on
  `ClientConnection`), a particle debug overlay, and an in-game log viewer
  with filtering, word-wrapping, one-click copy, and session export.
- **Debug**: resource/shader reload hotkeys, a keybind conflict checker, and
  an extended block/entity inspector.
- **Cosmetics**: a cape library with `.velocape` bundle import/export, and a
  real verlet-integration cloth simulator driving the sway of a cape
  rendered through vanilla's own `PlayerCapeModel`/`submitModel` pipeline.
- **Launcher** (`launcher/`): a JavaFX shell with Home (server ping widget
  using the real Server List Ping protocol), Mods (reads the mod's exported
  manifest), Cosmetics, Profiles, Settings, and a Theme Editor — all reading
  and writing the exact same `~/.velo-client/` files the mod does.

### Known gaps (not implemented, and why)

- **Account sign-in is implemented** ([`launcher/auth/`](launcher/src/main/java/net/veloclient/launcher/auth/) —
  full device-code Microsoft → Xbox Live → XSTS → Minecraft Services chain)
  but **won't work until the app's client id is on Mojang's allow-list.**
  As of 2025, Mojang manually reviews and approves every third-party app
  before it can reach the Java Edition auth APIs (an anti-phishing measure —
  see the "Java Edition Game Service API Review" notice linked from
  `https://aka.ms/AppRegInfo`). The Azure AD app registration itself is a
  separate, free, self-service step (see below); this allow-list approval is
  the one piece that requires Mojang's manual sign-off and there's no way
  around it in code. Until it's approved, sign-in will fail with
  `HTTP 403 ... "Invalid app registration"` from
  `api.minecraftservices.com/authentication/login_with_xbox` — everything
  before that step (device code, Microsoft login, Xbox Live, XSTS) already
  works correctly, confirmed by that exact error only appearing at the very
  last call in the chain.
- **One-click install & launch** (downloading Fabric/libraries/assets and
  spawning the game JVM) isn't implemented — a separate, large piece of work
  from auth. The Play button is present but explains this rather than
  pretending to launch anything.
- **Code-signed installers / auto-updater.** Needs a signing certificate and
  a release pipeline this environment doesn't have.
- **Cosmetic-sync backend.** Without a server, capes only render for your
  own client's view of yourself (e.g. a mirror/third-person mod) — other
  Velo Client users' cape choices aren't synced anywhere yet.
- **True GPU backdrop blur** for the glass panel look. The current
  `GlassPanel` approximates it with layered translucency; a real
  post-processing blur shader is a documented follow-up.
- **Cape mesh is a single physically-driven rigid segment**, not a full
  per-vertex soft-body mesh — the `ClothSimulator` itself is a genuine
  multi-point verlet chain, but the renderer currently uses its first
  segment's angle to bend vanilla's cape model rather than building custom
  per-segment geometry. See [`CapeFeatureRenderer`](src/client/java/net/veloclient/velo/client/cosmetics/render/CapeFeatureRenderer.java).
- **Deep chat modifications** (timestamps, background opacity, filters) and
  a **minimap** are not implemented — both need more extensive rendering
  hooks than this pass covered.
- **Stress-test/benchmark scripting** and the **network latency simulator**
  from the design brief aren't implemented yet.

## Building

Requires network access on first build (Gradle downloads Minecraft,
mappings, Fabric API, and JavaFX).

```bash
./gradlew build          # mod + launcher
./gradlew :launcher:build  # launcher only
```

The mod jar is written to `build/libs/velo-client-<version>.jar`. Java 21 is
required to compile and run; Loom's Gradle toolchain will use whatever JDK
21+ it finds, or you can point it at one explicitly via
`org.gradle.java.home` in `gradle.properties`.

### Running a dev client

```bash
./gradlew runClient
```

This launches Minecraft 1.21.11 with the mod loaded from source, in an
isolated `run/` directory (gitignored). **This opens a real, visible game
window** — it's not headless.

### Running the launcher

```bash
./gradlew :launcher:run
```

### Project layout

```
src/main/java/net/veloclient/velo/           common code (module system, config, profiles)
src/main/resources/fabric.mod.json           mod metadata / entrypoints
src/client/java/net/veloclient/velo/client/   client-only code (rendering, keybinds, GUI, modules, mixins)
src/client/resources/                         client-only assets (lang files, mixin config)
launcher/src/main/java/net/veloclient/launcher/  standalone JavaFX launcher app
```

`src/main` and `src/client` are split via Loom's
`splitEnvironmentSourceSets()` so common code (module registry, config,
profile matching) stays decoupled from rendering code — this is what would
let a future dedicated-server companion plugin (e.g. one that exposes TPS
for the Server Tools graphs) depend on the common jar without pulling in any
client classes.

## Contributing a module

1. Implement `Module` (or extend `AbstractModule`) in the appropriate
   `client/modules/<category>/` package.
2. For anything that draws to the screen, also implement `HudModule`.
3. Register it in
   [`VeloClientMod#registerModules()`](src/client/java/net/veloclient/velo/client/VeloClientMod.java).
4. If it needs a keybind, reuse `VeloKeybinds.CATEGORY` — `KeyBinding.Category.create()`
   throws if the same id is registered twice, which will crash the game at
   startup if a second module creates its own category.
5. Add a row to the table in [SAFETY.md](SAFETY.md) explaining what data it
   reads and why that doesn't constitute an advantage. PRs that add a module
   without a safety rationale won't be merged — see the disallow list in the
   design brief for what will never be accepted regardless of rationale.
6. Before considering a rendering-heavy change done, actually run
   `./gradlew runClient` and look at it — several APIs in this Minecraft
   version (rendering pipeline, debug HUD entries, keybind categories) don't
   match older-version tutorials/mods, and only a live run catches
   registration-order bugs that `compileClientJava` can't.

## License

MIT — see [LICENSE](LICENSE).
