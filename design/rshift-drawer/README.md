# R-Shift redesign: glass drawer = HUD editor (approved 2026-11-24)

Canvas: https://claude.ai/artifact/2vLCZzUMcjgCby9zTTLp6W (board "D2 · R-Shift = Edit HUD"). The `.dc.html` files here are
the boards' full source (Design canvas format, `background.jpg` = `/_blob/2c13934c10ac325461171bc36aad2ab0`).
**D2-EditHud.dc.html is the approved design**; the others are the earlier concepts (A, B, C, D, E, F).

## What R-Shift becomes
- R-Shift opens straight into HUD editing: the game stays visible, every HUD element can be dragged, a corner handle scales
  it, centre guides show while dragging. Clicking an element opens its settings in the drawer.
- A glass drawer on the right (340 px in the mockup, 16 px from the edges, radius 24, `rgba(18,18,20,.55)` + blur,
  1 px `rgba(255,255,255,.14)` border). The `»` button slides it away; a slim glass "Velo" tab on the right edge brings it back.
- **Smart minimize:** while moving/scaling an element with the mouse near the drawer, it slides away until you let go.
- **Drag into the game:** HUD modules have a grip in their category list; dragging it out drops the module on screen
  (enabled, placed where dropped).
- Navigation: Home → Category → Module page, one sliding strip, `cubic-bezier(.2,.8,.2,1)`.
- Home: search, Pinned (unpin directly), categories HUD / PvP / Visuals / Utility / Performance / Advanced with counts.
  Category rows: grip (HUD only), icon, name, pin, switch.
- Header: red 8 px square + "Velo", **profile dropdown** (person icon, name, chevron; glass menu with profiles + check,
  New profile, Manage profiles), collapse button.
- Dock at the bottom: Locker (wide tee icon), Friends, Clips & Rewind, World Map, Settings.

## Look
- Font Geist (UI), Geist Mono (numbers). White = primary; Velo red only as the tiny dot accent.
- **On/Off = toggle switches** (white track + dark knob when on, glass track + white knob when off, knob slides).
- Module page: icon tile + name + one-line description + big switch; settings in grouped glass cards with an uppercase
  group title: iOS-like segmented control (white pill in a dark track), custom slider (white fill + white thumb),
  switches, round colour swatches (white ring when picked, `+` for custom), keycap key capture ("Press a key…"),
  "Advanced" fold-out, "Reset position" for HUD modules. HUD settings change the element live.
- Icons: one set, 24 grid, 1.75–1.9 stroke, round caps (rendered to `textures/icon/nav/d_*.png`).
- Size: the menu ignores Minecraft's GUI scale (own scale from the window height, see Scale.dc.html).
