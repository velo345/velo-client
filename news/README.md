# News posts

Ready-made launcher news posts, one folder per release (`posts/<version>/`): a `post.json` plus the
screenshots it uses in `images/`.

To publish one: Launcher > **News** > **+ New post** > **Import post...** > pick the `post.json`.
The images (`"file:images/..."` in the JSON) are uploaded automatically; check the live preview,
change anything you like, then **Publish**.

`post.json` fields: `title`, `summary`, `tag` (Update / News / Event / Community / Sneak peek / Fix),
`version`, `accent` (`#rrggbb`, or leave it out for the theme color), `pinned`, `cover`, and `blocks`
- each block is one of `heading`, `text`, `image` (+ `caption`), `callout` (`style`: info / success /
warning), `list` (`items`), `divider`, `button` (`text` + `url`). Text supports `**bold**`,
`*italic*`, `` `code` `` and `[links](https://...)`.
