# Velo Client Discord bot

What it does:

- **Link channel** - members paste a code from Velo Client's Store in the configured channel; the bot deletes the
  message before redeeming it and awards the **Velo Neon** cape. `/unlink` undoes it.
  Optional "linked" role.
- **VIP role for Velo+** - `/setup vip-role`: members who linked their Discord get the role while they have Velo+
  (bought, or given in the launcher's Owner panel) and **lose it again when Velo+ ends**. The bot checks every minute
  and right after linking, so linking after getting Velo+ gives the role at once. With `vipStrict` (default, in
  `bot-config.json`) anyone holding the role without an active Velo+ loses it too - set it to `false` if you hand the
  role out by hand.
- **Ping roles** - a panel with one button per role (News, Updates, Giveaways, Polls...). Press to get or drop
  the role. `/roles add|remove|list|panel`, `/announce` pings a role with an embed.
- **Tickets** - a panel with a button per *ticket type*; each type has its own form, staff roles, ping roles,
  category and channel name (all in `bot-config.json`). The ticket is a private channel with Claim / Close
  buttons. Closing saves a **transcript** to the log channel (and DMs it to the member). Opening, claiming
  and closing are logged. `/ticket add|remove|close|types|staff-add|staff-remove|category`.
- **Ticket types** include **Staff application** and **Partner inquiry** (added to existing configs once - delete
  them from `bot-config.json` if you don't want them, they won't come back).
- **Info posts** - `/post post:<rules|download|free-cape|boosters> channel:#...` posts a designed message with a
  banner picture: the server rules, how to download Velo Client (with a Download button to the website), how to
  claim the free Velo Neon cape (step by step, with a screenshot), and the booster perks (with a Claim button).
  Pictures live in `src/main/resources/posts/` (regenerate with `tools/discord/make_posts.py`).
- **Booster rewards** - every boost Discord announces is recorded on the Velo server and is worth **14 days of
  Velo+** (plus the one-time **Booster cape**). Boosts **stack**: boosting twice (at once or later) gives 28 days,
  added on top of any Velo+ left. Linked members get it right away; others get a **Claim booster reward** button
  (also on the `/post boosters` panel) that pays out every boost not rewarded yet. Needs Discord's boost messages
  switched on (Server Settings > Overview > System Messages > "Send a message when someone boosts").
- **Giveaways** - `/giveaway start` with a prize that is a cosmetic (type to search; its picture is shown), Velo
  Coins or Velo+ days, a duration (`30m`, `12h`, `3d`, `1w`), winners, an optional picture and an optional role
  that may enter. Members press **Enter** (only linked members - the prize lands on their account by itself, no
  claiming). Winners are drawn at random when it ends. `/giveaway end|reroll|cancel|list`. Survives restarts.
- **Honeypot** - `/setup honeypot channel:#dont-type-here` posts a warning; anyone without Manage Messages or a
  staff role who writes there is removed and their last day of messages is deleted (softban by default - they can
  rejoin, handy for hacked accounts; or `action:ban`). Catches go to `/setup mod-log` (or the ticket log).
- **Welcome messages** - `/setup welcome channel:#welcome [rules:#rules]`: each new member gets a card with their
  avatar, name and member number on the Velo art, and a short note (rules, download button, account linking).
  `/setup welcome-test` posts your own card; `/setup welcome-off` stops them. The card background is made by
  `tools/discord/make_brand.py`, which also makes the server banner and animated icon in `assets/discord/`.
- **Velo staff** - `/setup staff-role role:@Mod level:helper|mod|admin`. The bot tells the Velo server who holds a
  staff role (linked members only) every 5 minutes and a few seconds after someone gains/loses a staff role or
  leaves the server. Everyone on the team gets the **Velo Staff cape** and **Velo+** only while they hold the role;
  mods also get the in-game `/velostaff` tools (look up players, censor custom capes), admins also service bans and
  player reports. **Helpers** get the cape and Velo+ but no moderation tools. Taking the role away removes it all.
- **Donations, bug reports and player reports** are posted by the *Velo server* through channel webhooks (see
  below) - the bot creates the webhooks for you with `/setup webhooks`.

## Setting it up

1. <https://discord.com/developers/applications> > New application > **Bot**: copy the token and switch on
   **Message Content Intent** and **Server Members Intent** (ticket transcripts need the first).
2. Invite it: OAuth2 > URL generator > scopes `bot` + `applications.commands`; permissions: *Manage Roles,
   Manage Channels, Manage Messages, View Channels, Send Messages, Embed Links, Attach Files, Read Message History,
   Mention Everyone*. Put the bot's role **above** the ping roles it should hand out.
3. Run it:

```bash
./gradlew :bot:shadowJar
DISCORD_TOKEN=... VELO_ADMIN_TOKEN=... VELO_SERVER_URL=https://api.velo-client.com \
  DISCORD_GUILD_ID=<your server id> java -jar bot/build/libs/velo-bot.jar
```

| Variable | |
|---|---|
| `DISCORD_TOKEN` | the bot token |
| `VELO_ADMIN_TOKEN` | the same value as the Velo server's `VELO_ADMIN_TOKEN` |
| `VELO_SERVER_URL` | the Velo server (default `https://api.velo-client.com`) |
| `DISCORD_GUILD_ID` | your server's id - makes the commands appear at once (otherwise they take up to an hour) |
| `BOT_DATA_DIR` | where `bot-config.json` lives (default `bot-data`) |

`DISCORD_TOKEN` is the Bot Token from the Discord Developer Portal > your application > **Bot**. It is not
the Application ID or Client ID. The bot does not use a GitHub token. The Velo server's optional
`VELO_PLUS_GITHUB_TOKEN` is separate and is only for fetching Addons from the private
`velo345/velo-client-plus` repo.

4. In Discord (needs *Manage Server*):

```
/setup link-channel channel:#link-discord          where members paste their copied code
/setup ticket-category category:<a category>      where tickets are created
/setup ticket-log channel:<a channel>             logs + transcripts
/setup support-role role:@Team                    handles every ticket
/setup linked-role role:@Linked                   (optional) for linked members
/setup vip-role role:@VIP                         Velo+ members (automatic, see above)
/setup webhooks donations:#donations reports:#bug-reports player-reports:#player-reports   -> prints URLs for the Velo server
/setup staff-role role:@Moderator level:mod       Velo staff (level:helper = cape + Velo+ only, level:admin for admins)
/setup welcome channel:#welcome rules:#rules      welcome cards for new members
/setup honeypot channel:#dont-type-here           bot trap
/setup mod-log channel:#mod-log                   honeypot catches, giveaway problems
/setup boost-channel channel:#boosts              (optional) where boost thank-yous go
/post post:rules channel:#rules                   also: download, free-cape, boosters
/roles add role:@News label:News emoji:📰 description:Release news
/roles panel channel:#roles
/ticket panel channel:#support
```

5. Put the webhook URLs in the Velo **server's** environment and restart it:
   `VELO_DISCORD_DONATIONS_WEBHOOK`, `VELO_DISCORD_REPORTS_WEBHOOK`, `VELO_DISCORD_PLAYER_REPORTS_WEBHOOK`
   (keep that channel staff-only - reports can include chat messages), plus `VELO_DISCORD_INVITE`
   (your invite link, shown in Velo Client) and `VELO_PUBLIC_URL` (so purchase embeds can show the wings
   a buyer wears). Each Tebex coin purchase then appears in #donations with the player's skin, what they
   wear and own; every bug / crash report appears in #bug-reports.

## Ticket types (`bot-config.json`)

Created with four defaults (Support, Bug report, Purchase / coins, Other). Edit the file and run
`/setup reload`, then `/ticket panel` again to refresh the posted panel.

```json
{
  "id": "bug",
  "label": "Bug report", "emoji": "🐞", "description": "Report a bug",
  "buttonStyle": "danger",              // primary | secondary | success | danger
  "color": "#ed4245",                   // embed colour in the ticket
  "categoryId": "123456789",            // its own category (else the general one)
  "staffRoleIds": ["111", "222"],       // who sees and handles this type
  "pingRoleIds": ["111"],               // pinged when one opens (default: the staff roles)
  "channelName": "{type}-{number}-{user}",
  "maxOpen": 1,                         // open tickets of this type per member (0 = general limit)
  "welcome": "The team will be with you soon.",
  "questions": [                        // the form - at most 5
    {"id": "what", "label": "What happened?", "style": "paragraph", "placeholder": "...", "required": true, "maxLength": 1200},
    {"id": "versions", "label": "Velo / Minecraft version", "style": "short", "required": true, "maxLength": 100}
  ]
}
```

Add as many types as you like (each is a button; five per row). Also set in the file: `maxOpenTicketsPerUser`,
`ticketPanelTitle` / `ticketPanelText`, `rolesPanelTitle` / `rolesPanelText`.
