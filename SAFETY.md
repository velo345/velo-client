# Velo Client — Safety Documentation

Velo Client is built around one rule: **if a feature would give an unfair
advantage on a strict-anticheat server, it doesn't ship.** This document is
for players, server admins, and anticheat developers who want to verify that
claim against the actual code rather than take our word for it.

## How to verify this yourself

Every module lives under [`src/client/java/net/veloclient/velo/client/modules/`](src/client/java/net/veloclient/velo/client/modules/)
and every module's outbound behavior is limited to: reading client-local
state (window size, keyboard/mouse state, entities already sent to and
rendered by this client) and drawing to the HUD layer. None of them touch
`ClientPlayNetworkHandler`'s outbound packet methods, none of them mutate
player input, and none of them read data the server hasn't already sent this
client. Grep for yourself:

```
grep -rn "sendPacket\|ClientPlayerEntity#setVelocity\|noClip" src/
```

## Module-by-module rationale

| Module | Category | Safety Tag | Why it's safe |
|---|---|---|---|
| FPS Counter | HUD | Always Safe | Reads `MinecraftClient#getCurrentFps()`, a purely local rendering statistic. Identical information to the vanilla F3 screen. |
| Ping Display | HUD | Always Safe | Reads the latency value the server already broadcasts to every client via the player list (same number shown next to your name in the vanilla tab list). |
| Coordinates | HUD | Always Safe | Reads the local player entity's own position, which the client necessarily already knows to render itself. No world scanning. |
| Clock | HUD | Always Safe | Wall-clock time from the local system clock. Never transmitted. |
| CPS Counter | HUD | Always Safe | Counts rising edges of the vanilla attack/use keybinds' `isPressed()` state — a readout of input you already provided, not a synthesized or accelerated input. No packet is sent, delayed, or modified because of this module. |
| Armor & Durability | HUD | Always Safe | Reads `LivingEntity#getEquippedStack` for the local player — identical data to opening your own inventory. |
| Potion Effect Timers | HUD | Always Safe | Reads `LivingEntity#getStatusEffects()` — the same icons/timers vanilla already renders above the hotbar. |
| Held Item | HUD | Always Safe | Reads `PlayerEntity#getMainHandStack()` — your own held item, already known client-side. |
| Keystrokes | HUD | Always Safe | Reads your own `GameOptions` keybinding pressed-state each frame; draws it, doesn't act on it. |
| Action Bar Log | HUD | Always Safe | Buffers the last few action-bar `Text` messages the server already sent via `ClientReceiveMessageEvents.GAME`; purely a local scrollback. |
| Session Stats | HUD | Always Safe | Counts local death-screen openings and elapsed wall-clock time; never transmitted, resets each session. |
| Waypoints | HUD | Always Safe | Manual, user-placed only (a keybind records your *current* position) — never populated from world scanning, so it's a personal note system, not X-ray-adjacent. |
| Toggle Sprint / Toggle Sneak | QoL | Always Safe | Latches the vanilla sprint/sneak keybinding's own `pressed` state so a tap behaves like a hold — the same keybind, the same eventual input, just not held down physically. No packet, physics, or timing change. |
| Zoom | QoL | Always Safe | Temporarily lowers `GameOptions#getFov()` while held, then restores it — a camera/rendering change only, same mechanism as vanilla's spyglass FOV. Reach/hit-detection are unaffected by FOV. |
| Frame Time Graph | Performance | Always Safe | Times its own render calls with `System.nanoTime()`. Pure client instrumentation. |
| Memory & GC Monitor | Performance | Always Safe | Reads `Runtime` heap stats and `GarbageCollectorMXBean` counts — standard JVM instrumentation, same data vanilla's own F3 memory line shows. |
| GPU Utilization | Performance | Always Safe | Reads `MinecraftClient#getGpuUtilizationPercentage()`, the same figure vanilla's F3 GPU line shows. |
| Particle Limiter | Performance | Always Safe | Drives vanilla's own `GameOptions#getParticles()` option (ALL/DECREASED/MINIMAL) — an ordinary video setting, not a custom particle system. |
| Performance Boost | Performance | Always Safe | Presets plus individually-tunable vanilla video settings (graphics preset, particles, entity shadows, view distance, VSync, mipmaps) bundled into one module; restores your previous values on disable. The "Default" preset only ever touches VSync — it never overrides your own graphics/particle/shadow choices, so enabling it can't silently push a lower-end machine onto Fancy settings. |
| PolyBlur | Rendering | Always Safe | A GPU post-effect pass using vanilla's own shipped `blur.json` shader (the same one behind the pause-menu background blur), triggered only above a motion-speed threshold. Purely visual; wrapped so any rendering failure disables it for the session instead of crashing. |
| Optimization Mod Detector | Performance | Always Safe | Calls `FabricLoader#isModLoaded` for Sodium/Lithium/Iris/Starlight — detection only, never reconfigures another mod. |
| Full Bright | Rendering | Always Safe | Pushes vanilla's own `GameOptions#getGamma()` option far past its UI slider cap — the same brightness curve vanilla's gamma slider already uses, just further along it, exactly like the FOV-beyond-cap precedent. It cannot reveal anything with zero received light as anything other than black; it only changes how already-lit blocks are displayed. Restores your previous value on disable. |
| Cosmetic Capes | Cosmetics | Cosmetic Only | Renders an extra layer on the local player model using vanilla's own `PlayerCapeModel` mesh, reusing the same `submitModel` call vanilla's `CapeFeatureRenderer` uses for the built-in cape. The server is never told about it (no data component, no equipment slot is touched) and non-Velo players simply don't see it. Cape sway is driven by a local verlet-integration cloth simulation over the wearer's own velocity — a rendering-only input. |
| Entity Count Overlay | Server Tools | Check Server Rules | Iterates `ClientWorld#getEntities()` — entities already loaded and rendered by the client for this exact reason. Cannot see anything beyond render distance. |
| Chunk Boundary Overlay | Server Tools | Check Server Rules | Toggles vanilla's own F3+G chunk-border debug renderer (`DebugHudProfile#setEntryVisibility`) independent of whether the F3 panel is open — it's Mojang's own rendering, not a reimplementation. |
| Hitbox Visualizer | Server Tools | Check Server Rules | Same mechanism as Chunk Boundary Overlay, toggling vanilla's F3+B entity-hitbox renderer. Draws the exact box the server already uses for hit detection — nothing wider, nothing extra, and it provides no combat timing information. Off by default; some servers ban any hitbox rendering outright regardless of fidelity, hence the tag. |
| World Border Visualizer | Server Tools | Always Safe | Draws a box along `ClientWorld#getWorldBorder()`'s existing bounds — the server-enforced boundary the client already knows about and is already prevented from crossing. |
| Light Level Overlay | Server Tools | Always Safe | Reads `World#getLightLevel` and the heightmap for already-loaded chunk columns near you and labels them via the same `GizmoDrawing` API vanilla's own debug renderers use. Cannot see through unloaded chunks. |
| TPS & Tick Graph | Server Tools | Always Safe | On singleplayer/LAN, reads the local `IntegratedServer`'s real tick time (same number vanilla's F3 shows). On a remote server, falls back to `ClientConnection`'s own packet-rate counters rather than guessing at a TPS figure the client was never sent. |
| Packet Traffic Monitor | Server Tools | Always Safe | A `Mixin` observes `ClientConnection#channelRead0`/`#send` to tally packet counts by class name — see [`ClientConnectionMixin`](src/client/java/net/veloclient/velo/client/mixin/ClientConnectionMixin.java). It only increments counters; it never reads packet fields, cancels, delays, or mutates anything. |
| Particle Debug Overlay | Server Tools | Always Safe | Reads `ParticleManager#getDebugString()` — vanilla's own F3 "P:" line. |
| Client Log Viewer | Server Tools | Always Safe | A `Log4j2` appender captures this client's own formatted log lines into a bounded ring buffer for in-game viewing/export. Never reads or sends anything beyond this client's own log output. |
| Command Keybinds | Utility | Always Safe | Sends a normal chat command via `ClientPlayNetworkHandler#sendChatCommand` on a keypress — the exact same client→server message typing the command and pressing Enter produces, just bound to a key. No packet is altered, and it can only send commands, never move the player or touch combat. |
| Resource/Shader Reload Hotkeys | Debug | Always Safe | Calls `MinecraftClient#reloadResources()` directly (the same thing F3+T does) and, if Iris is installed, reflectively calls Iris's own public reload API. |
| Keybind Conflict Checker | Debug | Always Safe | Iterates `GameOptions#allKeys` looking for shared bound keys. Read-only. |
| Block/Entity Inspector | Debug | Always Safe | Reads `MinecraftClient#crosshairTarget` (already computed by vanilla every frame to render the block-break/interact outline) plus the targeted block's already-loaded `BlockState`/`BlockEntity` NBT. Nothing beyond the crosshair target is queried. |

Two modules default to **off** and are tagged **Check Server Rules** rather
than Always Safe purely because *some* strict servers ban the entire category
of "any hitbox/chunk debug rendering" as a blanket policy — not because
either module provides an advantage. Enable them once you've checked a
server's rules, or leave Safe Mode on to keep them off automatically.

The [module manifest](src/main/java/net/veloclient/velo/module/ModuleRegistry.java)
(`exportManifest()`) writes this same id/category/safety-tag/description data
to `~/.velo-client/manifest.json` at runtime, so tooling (including a future
launcher UI) reads from the same source of truth as this table — it cannot
drift out of sync with the code.

## Safety tag definitions

- **Always Safe** — never provides gameplay advantage or information the
  server didn't already send; fine on every server, always.
- **Cosmetic Only** — purely visual/cosmetic, invisible to the server and to
  players not running Velo Client; zero gameplay effect.
- **Check Server Rules** — legitimate and packet-faithful, but some strict
  servers disallow the whole category (e.g. any hitbox-outline rendering,
  regardless of what it's used for). The `Safe Mode` master switch
  (`ModuleRegistry#applySafeMode()`) disables everything except Always Safe
  modules in one click for use on unfamiliar servers.

## What Velo Client will never do

See the "Explicit Disallow List" in the project's design brief: no
X-ray/ESP/tracers, no killaura/aimbot, no reach or hitbox-size modification,
no knockback/velocity modification or anti-KB packet dropping, no fly/speed/
noclip outside vanilla-permitted gamemodes, no timer manipulation, no
macro-perfect auto-totem/auto-eat, no nuker/instant-break, no freecam that
phases through blocks to scout live players, no chat/report spoofing, and no
packet injection of any kind. Every packet this client sends is
indistinguishable from a vanilla client's packets.

## Build transparency

- Source is public; there is no proprietary/closed module.
- The release jar is built by `./gradlew build` with no obfuscation beyond
  standard Loom remapping (obfuscating a supposedly-safe client jar is itself
  a red flag anticheat developers rightly distrust, so we don't do it).
- The mod itself makes no network calls beyond what Fabric Loader makes.
  Update checks, cosmetic-sync (so other Velo Client users can see your
  cape), and opt-in crash telemetry described in the design brief are not
  implemented yet — when they land, this document will list exactly what
  each call sends and why.
- The separate launcher app's "My Servers" widget implements the standard
  Minecraft Server List Ping protocol (the same handshake every vanilla
  client uses to populate the multiplayer server list) to show MOTD/player
  count/version for servers you add. It only pings addresses you explicitly
  enter, sends no authentication, and is a status-only handshake — see
  [`ServerPinger`](launcher/src/main/java/net/veloclient/launcher/net/ServerPinger.java).
  The launcher also implements real Microsoft account sign-in (device-code
  flow → Xbox Live → XSTS → Minecraft Services) — see
  [`MicrosoftAuth`](launcher/src/main/java/net/veloclient/launcher/auth/MicrosoftAuth.java)
  and [README.md](README.md#setting-up-account-sign-in) for the standard,
  publicly-documented endpoints it talks to (login.microsoftonline.com,
  xboxlive.com, api.minecraftservices.com) and why it needs Mojang's
  separate app allow-list approval to actually complete. It never touches
  Mojang's legacy password-based auth and stores no password anywhere.
  One-click install/launch (downloading Fabric/libraries/assets and
  spawning the game) is not implemented yet.

## Reporting a concern

If you believe a module in this repository behaves differently from what's
described here, please open an issue with the module id and the discrepancy
you observed.
