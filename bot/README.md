# Velo Client Discord bot

What it does:

- **`/link <code>`** - members link their Minecraft account (code from Velo Client: Store > Capes > *Get my link
  code*, or the *Link Discord* button in the in-game Store) and unlock the **Velo Neon** cape. `/unlink` undoes it.
  Optional "linked" role.
- **VIP role for Velo+** - `/setup vip-role`: members who linked their Discord get the role while they have Velo+
  (bought, or given in the launcher's Owner panel) and **lose it again when Velo+ ends**. The bot checks every minute
  and right after `/link`, so linking after getting Velo+ gives the role at once. With `vipStrict` (default, in
  `bot-config.json`) anyone holding the role without an active Velo+ loses it too - set it to `false` if you hand the
  role out by hand.
- **Ping roles** - a panel with one button per role (News, Updates, Giveaways, Polls...). Press to get or drop
  the role. `/roles add|remove|list|panel`, `/announce` pings a role with an embed.
- **Tickets** - a panel with a button per *ticket type*; each type has its own form, staff roles, ping roles,
  category and channel name (all in `bot-config.json`). The ticket is a private channel with Claim / Close
  buttons. Closing saves a **transcript** to the log channel (and DMs it to the member). Opening, claiming
  and closing are logged. `/ticket add|remove|close|types|staff-add|staff-remove|category`.
- **Donations and bug reports** are posted by the *Velo server* through channel webhooks (see below) - the
  bot creates the webhooks for you with `/setup webhooks`.

## Setting it up

1. <https://discord.com/developers/applications> > New application > **Bot**: copy the token and switch on
   **Message Content Intent** and **Server Members Intent** (ticket transcripts need the first).
2. Invite it: OAuth2 > URL generator > scopes `bot` + `applications.commands`; permissions: *Manage Roles,
   Manage Channels, View Channels, Send Messages, Embed Links, Attach Files, Read Message History,
   Mention Everyone*. Put the bot's role **above** the ping roles it should hand out.
3. Run it:

```bash
./gradlew :bot:shadowJar
DISCORD_TOKEN=... VELO_ADMIN_TOKEN=... VELO_SERVER_URL=https://client.asteriasmp.net \
  DISCORD_GUILD_ID=<your server id> java -jar bot/build/libs/velo-bot.jar
```

| Variable | |
|---|---|
| `DISCORD_TOKEN` | the bot token |
| `VELO_ADMIN_TOKEN` | the same value as the Velo server's `VELO_ADMIN_TOKEN` |
| `VELO_SERVER_URL` | the Velo server (default `https://client.asteriasmp.net`) |
| `DISCORD_GUILD_ID` | your server's id - makes the commands appear at once (otherwise they take up to an hour) |
| `BOT_DATA_DIR` | where `bot-config.json` lives (default `bot-data`) |

4. In Discord (needs *Manage Server*):

```
/setup ticket-category category:<a category>      where tickets are created
/setup ticket-log channel:<a channel>             logs + transcripts
/setup support-role role:@Team                    handles every ticket
/setup linked-role role:@Linked                   (optional) for linked members
/setup vip-role role:@VIP                         Velo+ members (automatic, see above)
/setup webhooks donations:#donations reports:#bug-reports   -> prints two URLs for the Velo server
/roles add role:@News label:News emoji:📰 description:Release news
/roles panel channel:#roles
/ticket panel channel:#support
```

5. Put the two webhook URLs in the Velo **server's** environment and restart it:
   `VELO_DISCORD_DONATIONS_WEBHOOK`, `VELO_DISCORD_REPORTS_WEBHOOK`, plus `VELO_DISCORD_INVITE`
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
