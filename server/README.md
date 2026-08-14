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

Open whichever port you configured (`8787` by default) to inbound TCP from
wherever your players connect from, e.g. with `ufw`:

```bash
sudo ufw allow 8787/tcp
```

### Putting TLS in front of it (recommended)

The server itself only speaks plain HTTP - fine for a LAN/VPN or for
testing, but for anything reachable over the open internet, put a reverse
proxy in front of it so traffic (including access-token-adjacent session
data) is encrypted. [Caddy](https://caddyserver.com/) does this in one line
and handles the certificate for you automatically:

```
# Caddyfile
velo.yourdomain.com {
	reverse_proxy localhost:8787
}
```

```bash
sudo caddy run
```

Then point the mod at `https://velo.yourdomain.com` instead of the bare
`http://host:8787` address (see the client-side setup below).

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
