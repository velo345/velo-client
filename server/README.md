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
- That's it for now - no chat, no friends list, no moderation tooling. More
  can be layered on top of the same `/v1/online` shape later.

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

**No other file is needed alongside the jar.** Everything server-side lives
in memory and the only knob is that one environment variable - there's no
`config.json`/`.properties` file to create, copy, or keep in sync. (A reverse
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
Restart=on-failure
Environment=VELO_SERVER_PORT=8787
User=velo-server
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo mkdir -p /opt/velo-server
sudo cp server/build/libs/velo-server.jar /opt/velo-server/
sudo useradd --system --no-create-home velo-server || true
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
| GET    | `/v1/online`            | -                                  | `{users: [{uuid, username, capeId}], serverTimeMillis}` |

## Known limitations (v1)

- Only the built-in Store capes sync across players - a custom `.velocape`
  you imported yourself only shows on your own client, since there's no
  texture-upload endpoint yet. That's the natural next thing to add here.
- No persistence, no moderation/blocklist, no rate limiting beyond a body
  size cap. Fine for a friend group or small community; harden before
  exposing this to a large public audience.
