# Third-party assets

## Audiowide font

`assets/fonts/Audiowide-Regular.ttf` (used for the in-game title screen and the launcher's home
screen wordmark) is the [Audiowide](https://fonts.google.com/specimen/Audiowide) typeface by
Astigmatic, licensed under the [SIL Open Font License 1.1](https://openfontlicense.org/).

## Inter font

`src/client/resources/assets/velo-client/font/inter_*.ttf` and
`launcher/src/main/resources/net/veloclient/launcher/fonts/Inter-*.ttf` (the UI font of the in-game
menus and the launcher) are [Inter](https://rsms.me/inter/) by Rasmus Andersson, licensed under the
[SIL Open Font License 1.1](https://openfontlicense.org/). The in-game copies are modified: their
TrueType hinting instructions were removed (outlines and metrics unchanged), because FreeType's
hinting interpreter crashed the game while preloading glyphs.

## Phosphor icons

Module icons under `src/client/resources/assets/velo-client/textures/icon/module/` and sidebar
navigation icons under `src/client/resources/assets/velo-client/textures/icon/nav/` are rasterized
from [Phosphor Icons](https://phosphoricons.com) (https://github.com/phosphor-icons/core),
Copyright (c) 2023 Phosphor Icons, used under the MIT License.

## Lucide icons

Icons under `launcher/src/main/resources/net/veloclient/launcher/images/icons/` are rasterized from
[Lucide](https://lucide.dev) (https://github.com/lucide-icons/lucide), used under the ISC license:

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT).
All other copyright (c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THIS SOFTWARE.
```

## Sora and JetBrains Mono fonts

`launcher/src/main/resources/net/veloclient/launcher/fonts/Sora-*.ttf` (launcher display headings, and the
in-game copies `src/client/resources/assets/velo-client/font/sora_*.ttf`) are
[Sora](https://fonts.google.com/specimen/Sora) by Jonathan Barnbrook and Julián Moncada, and
`launcher/src/main/resources/net/veloclient/launcher/fonts/JetBrainsMono-*.ttf` and the in-game
`src/client/resources/assets/velo-client/font/jetbrains_mono_medium.ttf` (versions, numbers, keys)
are [JetBrains Mono](https://www.jetbrains.com/lp/mono/) by JetBrains. Both are licensed under the
[SIL Open Font License 1.1](https://openfontlicense.org/); the license texts sit next to the fonts
(`OFL-Sora.txt`, `OFL-JetBrainsMono.txt`). The bundled files are the Latin static subsets.

## HUD fonts: Space Grotesk, Poppins, IBM Plex Sans

The HUD Style font choices `src/client/resources/assets/velo-client/font/space_grotesk_bold.ttf`
([Space Grotesk](https://github.com/floriankarsten/space-grotesk) by Florian Karsten),
`poppins_regular.ttf` ([Poppins](https://github.com/itfoundry/Poppins) by Indian Type Foundry) and
`ibm_plex_sans_semibold.ttf` ([IBM Plex Sans](https://github.com/IBM/plex) by IBM) are licensed under the
[SIL Open Font License 1.1](https://openfontlicense.org/); the license texts sit next to the fonts
(`OFL-SpaceGrotesk.txt`, `OFL-Poppins.txt`, `OFL-IBMPlexSans.txt`).
