# Velo Client server

The small backend that lets Velo Client users see *each other*: the "which
client is this player using" badge (previously only ever shown next to your
own name - see `VeloBadge`'s doc) and each other's equipped cosmetic cape now
work for other Velo Client users too, as long as everyone involved is
pointed at the same server.

## What it does

- Tracks who's currently online with Velo Client running (in memory only -
  nothing is written to disk, nothing survives a restart, and that's fine:
  "online right now" isn't meaningful data to keep around).
- Verifies identity the same way real Minecraft servers do: your access
  token never leaves your own machine. The mod asks Mojang directly to
  "join" using a one-time ID this server handed out, then this server asks
  Mojang's public `hasJoined` endpoint whether that really happened. See
  `MojangSessionVerifier.java` if you want the details.
- Publishes which of the built-in Store capes (the shared, bundled-with-every-client
  ones) each online player has equipped, so other clients can render it
  without needing to download anyone's texture.
- Stores players' own **custom capes** (imported PNG) so everyone else on
  the server sees them too. The mod uploads your equipped custom cape once
  (the "Share Custom Cape" toggle on the Velo Network module, on by default);
  other clients download it once and cache it. Uploads are decoded and
  re-encoded server-side, content-addressed by SHA-256, and kept on disk in
  the data directory, so they survive restarts. Limits: PNG up to 2048x1024
  (2:1 cape, or 1:1 cape+elytra, width a multiple of 64); 6 MB per upload; one
  custom cape per account. Animated (GIF) capes are refused - they're Velo Store
  items, shared by item id and only shown for players who own them.
- **Friends & messaging** (`/v1/social/*`, see `SocialService.java`): friend
  requests by username or UUID, accept/deny, unfriend, block (blocked players
  can't message you or send requests, and are never told), direct messages
  (last 300 per conversation kept so offline friends still get them), shared
  waypoints, and live presence - whether a friend is online and on which
  server / singleplayer world / Realm, unless they chose "appear offline"
  (stored server-side, so it survives launcher and server restarts).
  Clients long-poll `/v1/social/poll` for events. Persisted to
  `social.json` in the data directory.
- **Rate limits** (`RateLimiter.java`): per player - friend requests 5/min and
  30/hour, a 30-minute cooldown before re-requesting someone who declined,
  messages 8 per 5 s / 60 per minute / 600 per hour, waypoint shares
  10/min, and smaller caps on block/unblock/status/presence. Over the limit
  answers HTTP 429 with a "try again in ..." message the UI shows as-is.
- The game and the launcher each hold their own session (`kind` = `game` /
  `launcher`) for the same account; only game sessions count for badges and
  capes.

- **News, polls & bug reports** (`NewsRoutes.java`): launcher news posts (block-based, with
  uploaded images in `news/images/`) and community polls, both stored in `news.json`; anyone can
  read them, signed-in players can vote (one vote per account, changeable until the poll ends).
  Bug and crash reports from the game and the launcher go to `reports/` (one gzip'd JSON each,
  with the player's message, versions, system, mods, log tail and crash report) - signing in is
  optional, limited to 4 per 10 minutes / 20 per day per player or IP. Owners (`VELO_OWNERS`)
  write posts/polls and read reports in the launcher's News tab; scripts can use the
  `X-Velo-Admin-Token: $VELO_ADMIN_TOKEN` header on the `/v1/admin/news/*` and
  `/v1/admin/reports*` endpoints instead.

## Running it

Requires a JDK 21+ to run (nothing else - Gson is bundled into the jar).

```bash
./gradlew :server:shadowJar
java -jar server/build/libs/velo-server.jar
```

By default it listens on port `8787` on all interfaces. Override with the
`VELO_SERVER_PORT` environment variable:

```bash
VELO_SERVER_PORT=9000 java -jar server/build/libs/velo-server.jar
```

**No other file is needed alongside the jar.** Configuration is three
optional environment variables - there's no `config.json`/`.properties`
file to create:

| Variable | Default | What it does |
|---|---|---|
| `VELO_SERVER_PORT` | `8787` | Port to listen on |
| `VELO_DATA_DIR` | `./data` (relative to the working directory) | Where uploaded custom capes (`capes/`, `capes.json`, `banned-capes.json`) and friends/messages (`social.json`) are stored |
| `VELO_ADMIN_TOKEN` | unset (admin endpoint disabled) | Secret for removing a player's custom cape (see "Moderation") |

Online sessions still live only in memory; custom capes and friends/messages
are written to the data directory (back it up).

### Updating a running server

```bash
./gradlew :server:shadowJar          # on your dev machine
# copy server/build/libs/velo-server.jar to /opt/velo-server/ (e.g. Termius SFTP), then:
sudo systemctl restart velo-server
curl https://your.domain/v1/health   # {"status":"ok",...}
```

A `502 Bad Gateway` from the reverse proxy means this process isn't running
or isn't on the port the proxy forwards to - see `journalctl -u velo-server`. (A reverse
proxy like Caddy, below, keeps its own config, but that's a separate process
running next to this one, not something this jar reads.)

Check it's alive:

```bash
curl http://localhost:8787/v1/health
# {"status":"ok","onlineCount":0}
```

### Running it in the background (systemd)

```ini
# /etc/systemd/system/velo-server.service
[Unit]
Description=Velo Client server
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/velo-server/velo-server.jar
WorkingDirectory=/opt/velo-server
Restart=on-failure
Environment=VELO_SERVER_PORT=8787
Environment=VELO_DATA_DIR=/opt/velo-server/data
Environment=VELO_ADMIN_TOKEN=change-me-to-a-long-random-string
User=velo-server
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo mkdir -p /opt/velo-server/data
sudo cp server/build/libs/velo-server.jar /opt/velo-server/
sudo useradd --system --no-create-home velo-server || true
sudo chown -R velo-server:velo-server /opt/velo-server/data
sudo systemctl daemon-reload
sudo systemctl enable --now velo-server
sudo journalctl -u velo-server -f   # logs
```

### Firewall / networking

Which port(s) to open depends on whether you're putting Caddy in front of
this (see below, and **recommended** for anything reachable over the open
internet):

- **Using Caddy for TLS:** open **80** and **443** only - `80` is needed for
  Let's Encrypt's ACME HTTP challenge (proving you own the domain) and for
  redirecting plain HTTP to HTTPS; `443` is the actual HTTPS traffic players
  connect to. Leave `8787` **closed** to the outside world - Caddy reaches it
  internally via `localhost`, so nothing external needs to touch it directly,
  and leaving it open too would let anyone bypass TLS entirely by hitting it
  straight.

  ```bash
  sudo ufw allow 80/tcp
  sudo ufw allow 443/tcp
  ```

- **No TLS (LAN/VPN/testing only):** open whichever port you configured
  (`8787` by default) directly instead:

  ```bash
  sudo ufw allow 8787/tcp
  ```

### Putting TLS in front of it (recommended)

The server itself only speaks plain HTTP - fine for a LAN/VPN or for
testing, but for anything reachable over the open internet, put a reverse
proxy in front of it so traffic (including access-token-adjacent session
data) is encrypted. [Caddy](https://caddyserver.com/) is a good choice here
specifically because it gets you a real, auto-renewing HTTPS certificate
(from Let's Encrypt) for free, with no manual certbot/cron setup - point it
at a domain and it handles issuing and renewing the cert itself.

**Already have nginx (or another reverse proxy) running on this machine
serving other sites?** Ports 80/443 can only be bound by one process each,
so Caddy will fail to start (`bind: address already in use`) if nginx's
already holding them - and since that nginx is presumably serving things you
need, the fix isn't to fight it for the ports. Skip this whole section and
use the [nginx + certbot alternative](#already-running-nginx-use-that-instead)
below instead - same end result (a real HTTPS cert, auto-renewing), just
adding one more site to the proxy you already have instead of running a
second one.

**Before any of this**, the domain needs to actually resolve to this
machine: create an `A` record (or `AAAA` for IPv6) for `velo.yourdomain.com`
pointing at this server's public IP, with whoever hosts your domain's DNS.
Caddy's automatic HTTPS won't work until that resolves - it proves ownership
by having Let's Encrypt reach the domain, so a DNS record that doesn't point
here yet will make certificate issuance fail.

**1. Install Caddy.** On Debian/Ubuntu, via Caddy's own official repo (the
version in the default Ubuntu/Debian repos is usually too old):

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install caddy
```

(Other distros: see [Caddy's install docs](https://caddyserver.com/docs/install) for `dnf`/`yum`/Arch/Alpine/etc. equivalents.)

This apt package does two things beyond just installing the `caddy` binary:
it creates an (initially near-empty) config file at **`/etc/caddy/Caddyfile`**
- that exact path is where Caddy looks by default, so this isn't a file you
create from scratch - and it registers Caddy as a **systemd service**,
already running and watching that file.

**2. Edit `/etc/caddy/Caddyfile`** (needs `sudo` to edit) and replace its
contents with:

```
# /etc/caddy/Caddyfile
velo.yourdomain.com {
	reverse_proxy localhost:8787
}
```

**3. Tell the running service to pick up the change:**

```bash
sudo systemctl reload caddy
```

That's it - no `sudo caddy run` needed for a real deployment; that command
starts Caddy in the foreground of your current terminal and stops the
moment you close it or press Ctrl+C, which is fine for a quick local test
but not for something meant to stay up. The systemd service the apt package
already set up in step 1 is the persistent equivalent (same idea as the
`velo-server.service` above) - `reload` just tells that already-running
instance to re-read the Caddyfile, no restart/downtime needed.

Check it worked:

```bash
curl -I https://velo.yourdomain.com/v1/health
# HTTP/2 200 with a valid certificate - if this hangs or errors, check
# `sudo journalctl -u caddy -f` and confirm the DNS record above has
# actually propagated (`dig velo.yourdomain.com` should show this
# server's IP).
```

Then point the mod at `https://velo.yourdomain.com` instead of the bare
`http://host:8787` address (see the client-side setup below).

### Already running nginx? Use that instead

If port 80/443 are already nginx's (confirm with `sudo ss -ltnp | grep -E
':80\b|:443\b'` - if that prints `nginx`, this is you), don't install Caddy
at all. Add a new site for this domain to your existing nginx and get a
cert for it with certbot, the same way you would for any other site on the
box:

**1. Add a server block** for the domain (swap in your real one):

```nginx
# /etc/nginx/sites-available/client.asteriasmp.net
server {
	listen 80;
	server_name client.asteriasmp.net;

	# News images are up to 12 MB (capes 6 MB) - nginx's default limit is 1 MB.
	client_max_body_size 16m;

	location / {
		proxy_pass http://localhost:8787;
		proxy_set_header Host $host;
		proxy_set_header X-Real-IP $remote_addr;
		proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
		proxy_set_header X-Forwarded-Proto $scheme;
	}
}
```

**2. Enable it and reload nginx:**

```bash
sudo ln -s /etc/nginx/sites-available/client.asteriasmp.net /etc/nginx/sites-enabled/
sudo nginx -t   # sanity-checks the config before touching anything live
sudo systemctl reload nginx
```

**3. Get a certificate with certbot** (same tool/flow as any other site
you've already certbot'd on this box - if you've used `sudo certbot --nginx`
before, this is nothing new, just a new `-d`):

```bash
sudo certbot --nginx -d client.asteriasmp.net
```

Certbot edits that server block in place to add the `listen 443 ssl`
block and certificate paths, and reuses whatever renewal timer/cron it
already set up from your previous certificates - nothing new to schedule.

Check it worked:

```bash
curl -I https://client.asteriasmp.net/v1/health
# HTTP/1.1 200 (or 2 200) with a valid certificate
```

If you'd already installed Caddy while troubleshooting this and it's not
needed, clean it up so it stops trying (and failing) to restart:

```bash
sudo systemctl disable --now caddy
```

## Velo Coins store (real payments, daily rewards, ads)

The server is the only place coins and owned cosmetics exist: balances, purchases, orders and an
append-only coin ledger live in `store.db` (SQLite) in the data directory. Prices, coin packs and
reward amounts are in `store.json` (created with defaults on first start - edit and restart).
Clients only display what the server answers, so editing local files can't create coins.

**Back up `store.db`** (it's real money). While the server runs, copy it with
`sqlite3 data/store.db ".backup data/store-backup.db"` (or stop the server and copy the file).

### 1. Payments: Tebex (merchant of record)

Tebex takes the payment (cards, PayPal, paysafecard, ...), handles VAT/sales tax, refunds and
chargebacks, and pays out to you - you don't need your own VAT registration in every EU country.
Velo uses the **Tebex Checkout API**, where the server defines each coin pack and price itself
(Lunar Client's store runs on the same API).

1. Create an account and a project at <https://creator.tebex.io>. Pick the currency you want
   prices in (it must match `"currency"` in `store.json`, default `EUR`).
2. Ask Tebex support to enable **Checkout API** access for the project (it's not on by default).
3. *Developers -> API Keys*: copy the **Project ID** and **Private Key**.
4. *Developers -> Webhooks*: add the endpoint `https://YOUR-SERVER/v1/store/webhook/tebex`,
   subscribe to `payment.completed`, `payment.refunded`, `payment.dispute.opened`,
   `payment.dispute.lost`, and copy the **webhook secret**. Tebex sends a validation webhook first;
   the server answers it automatically.
5. For testing: *Settings -> Checkout -> Test Mode* adds a "Test Payments" method that always
   succeeds (real records, real webhooks). **Turn it off before going public.**

### 2. Rewarded ads: ayeT-Studios

Players can watch up to `adsPerDay` (default 3) short videos per day for `adCoins` (default 6)
coins each. [ayeT-Studios](https://www.ayetstudios.com) (rewarded video for the web, no minimum
traffic) confirms every completed view **server-to-server** with an HMAC-signed callback; the
server de-duplicates by transaction id, so views can't be faked from the client. The coin amount
always comes from `store.json`, never from the callback.

1. Sign up as a publisher at <https://www.ayetstudios.com> (*Publishers -> Sign up*).
2. *Placements -> Add placement*: type **Website**, URL = your `VELO_PUBLIC_URL`
   (the ad page is `https://YOUR-SERVER/rewards/ad`). Note the numeric **placement id**.
3. In that placement, add an **AdSlot** of type **Rewarded Video**. Note its **name** (that's `AYET_ADSLOT`).
4. *Callback URL* of the placement/adslot:
   `https://YOUR-SERVER/v1/rewards/ayet?uid={external_identifier}&tid={transaction_id}`
5. *Account settings*: copy your **publisher API key** (it verifies the
   `X-Ayetstudios-Security-Hash` header on callbacks).
6. **ads.txt**: copy the lines ayeT shows for the placement into `data/ads.txt` on the server
   (next to `store.db`). The server serves it at `https://YOUR-SERVER/ads.txt`; no restart needed.

EU visitors without a consent banner (CMP) only get non-personalised ads, so fill and payout are
lower; that's fine to start with.

### 3. Owners

`VELO_OWNERS` lists who gets the owner tools (launcher: *Velo Coins -> Owner tools*; in game:
*Velo Coins* screen): give/take coins, give/remove cosmetics, look up a player's balance and
ledger. Comma-separated Minecraft UUIDs (recommended - names can change) or usernames.

### 4. Environment

| Variable | What it does |
|---|---|
| `VELO_PUBLIC_URL` | Public https address of this server, e.g. `https://client.example.net` (needed for checkout return pages and the ad page) |
| `VELO_OWNERS` | Owner accounts, e.g. `069a79f444e94726a5befca90e38aaf5,Velo2k` |
| `TEBEX_PROJECT_ID` / `TEBEX_PRIVATE_KEY` | Tebex Checkout API credentials (enables buying coins) |
| `TEBEX_WEBHOOK_SECRET` | Verifies Tebex webhooks (required for coins to be credited) |
| `AYET_PLACEMENT_ID` / `AYET_ADSLOT` / `AYET_API_KEY` | Rewarded ads (ayeT-Studios placement id, rewarded-video adslot name, publisher API key) |

With systemd, put them in the unit (or an `EnvironmentFile=` only root can read):

```ini
[Service]
Environment=VELO_PUBLIC_URL=https://client.example.net
Environment=VELO_OWNERS=YOUR-UUID
Environment=TEBEX_PROJECT_ID=...
Environment=TEBEX_PRIVATE_KEY=...
Environment=TEBEX_WEBHOOK_SECRET=...
Environment=AYET_PLACEMENT_ID=...
Environment=AYET_ADSLOT=...
Environment=AYET_API_KEY=...
```

On start the server prints which parts are on, e.g.
`Store: coin checkout ON, Tebex webhooks ON, rewarded ads OFF (set AYET_PLACEMENT_ID, ...)`.

### How it stays safe

- A paid order is credited exactly once: the order goes pending -> paid in the same database
  transaction that adds the coins, and webhook ids are remembered (Tebex retries deliveries).
- The coins credited come from the server's own order (created when checkout started), never from
  the webhook; the paid amount is checked against the order price - mismatches are parked as
  `review` and logged instead of credited.
- Refunds and lost chargebacks take the coins back (the balance may go negative; a negative
  balance can't buy anything).
- Store capes are only shown to other players when the server says you own them.
- Free coins are slow on purpose: everything maxed every day (login streak, all quests, all ads)
  is roughly 60-70 coins - about two weeks for a ~1000-coin cape. Quests count Minecraft's own
  server-tracked statistics, are rate-capped per minute, and only pay after the server has seen
  enough real in-game time that day (from game-session heartbeats).

## Configuring the mod to use your server

The mod ships already pointed at Velo Client's own official server
(`https://client.asteriasmp.net`) with the "Velo Network" module on by
default - **no setup needed** for that case, nothing to create by hand.

Only touch this if you're running your own server instead (e.g. testing
locally, or a different community's deployment):

1. Launch the game once with the mod installed so `~/.velo-client/config/`
   exists (`%APPDATA%\VeloClient\config\` on Windows).
2. Create (or edit) `network.json` in that folder - the only field is
   `serverUrl`:

   ```json
   {
     "serverUrl": "https://velo.yourdomain.com"
   }
   ```

   Point every player who should see each other at the **same** `serverUrl`.
   To opt out entirely, set it to an empty string (`""`) - the module then
   stays a genuine no-op regardless of whether it's toggled on.
3. Restart the game, or toggle the "Velo Network" module off/on in the mod
   menu (Cosmetics category) to reload it without restarting.
4. Badges and capes for other online Velo Client users using the same server
   should now appear within about 30-45 seconds of joining a world together.

## API (for reference)

All requests/responses are JSON. See `VeloServerApp.java` for the exact
handler code.

| Method | Path                 | Body                              | Notes |
|--------|----------------------|------------------------------------|-------|
| GET    | `/v1/health`         | -                                   | `{status, onlineCount}` |
| POST   | `/v1/session/challenge` | `{uuid, username}`               | Returns `{serverId}` to join Mojang's session server with |
| POST   | `/v1/session/verify`    | `{uuid, serverId}`                | Returns `{sessionToken, heartbeatIntervalSeconds, sessionTtlSeconds}` |
| POST   | `/v1/heartbeat`         | `{sessionToken, capeId}`          | Keeps a session alive and publishes the currently-equipped cape (or `null`) |
| POST   | `/v1/session/end`       | `{sessionToken}`                  | Explicit "going offline" - optional, sessions expire on their own too |
| GET    | `/v1/online`            | -                                  | `{users: [{uuid, username, capeId}], serverTimeMillis}` - `capeId` is a Store id or `custom:<sha256>` |
| POST   | `/v1/cape/upload`       | raw PNG bytes, `Authorization: Bearer <sessionToken>` (GIFs are refused: animated capes are Store-only) | Returns `{capeId: "custom:<sha256>"}` |
| POST   | `/v1/cape/remove`       | `Authorization: Bearer <sessionToken>` | Removes your own custom cape |
| GET    | `/v1/cape/<sha256>`     | -                                  | The cape image; immutable, cacheable forever |
| POST   | `/v1/admin/cape/remove` | `{uuid}`, `Authorization: Bearer $VELO_ADMIN_TOKEN` | Removes a player's custom cape and bans that exact image |

The heartbeat response also echoes the `capeId` the server accepted
(`null` if it refused it, e.g. a `custom:` id that isn't that player's own
upload).

## Moderation

Anyone signed in can upload a custom cape, so on a public server set
`VELO_ADMIN_TOKEN`. To remove someone's cape (it disappears for everyone
within ~20 s, and the same image can't be re-uploaded):

```bash
curl -X POST https://velo.yourdomain.com/v1/admin/cape/remove \
  -H "Authorization: Bearer $VELO_ADMIN_TOKEN" \
  -d '{"uuid":"<player uuid, dashed or not>"}'
```

## Known limitations (v1)

- Custom capes sync the cape texture only - a bundled elytra texture stays
  local, and other players' capes render with a fixed lean (no cloth
  physics).
- Moderation is the admin endpoint above; there's no automatic content
  filtering. Rate limiting is one upload per account per 10 s plus body size
  caps - there's no per-IP limit on the auth endpoints yet.
