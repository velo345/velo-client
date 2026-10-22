# Velo on the production server

Production runs on the shared Debian 12 server (`ssh velo-new`). FlipSMP lives there too
(`/home/pwjzns/flipsmp`, Docker) and its **Caddy container owns ports 80/443** - never bind anything
public on them or recreate those containers.

```
browser ─TLS─> Caddy (Docker, Let's Encrypt) ─> nginx 172.18.0.1:8080 ─┬─> /var/www/velo-client.com (website)
                                                                        ├─> 127.0.0.1:8787 velo-server (API, /v1)
                                                                        └─> 127.0.0.1:8790 velo-panel
old server: client.asteriasmp.net ─> https://api.velo-client.com (pre-0.9.8 clients, while it's online)
```

| Piece | Where |
|---|---|
| Caddy block for the Velo hosts | `caddy/velo.caddy`, appended to `/home/pwjzns/flipsmp/Caddyfile` |
| nginx | `nginx/velo.conf` + `nginx/snippets/` → `/etc/nginx/conf.d/`, `/etc/nginx/snippets/` |
| Services | `systemd/` → `velo-server`, `velo-bot`, `velo-panel`, `velo-backup.timer`, `velo-firewall` |
| Files | `/opt/velo/{server,bot,panel,backups}`, secrets in `/etc/velo/*.env` (root, 600) |
| Panel accounts | `/etc/velo-panel/users.json` - add/reset: `sudo velo-panel-adduser <name>` |
| Firewall | own nft table `inet velo_host` (`host/firewall.nft`) - never `nft flush ruleset` (Docker) |

## Everyday

- **Website:** `velo-site/deploy.sh` (build, upload, IndexNow ping).
- **Server / bot / panel jar:** `./gradlew :server:shadowJar` (or `:bot:` / `:panel:`), then
  `scp <jar> velo-new:/opt/velo/<server|bot|panel>/` → `chown root:velo`, `chmod 640`, restart the service
  (or use the panel's Server page).
- **Logs, restarts, files, backups:** https://panel.velo-client.com (password + authenticator code).
- **Backups:** nightly at ~04:20 into `/opt/velo/backups`, 14 days kept; "Start" on `velo-backup` in the panel runs one now.

## Gotchas

- After changing a `limit_req_zone`/`limit_conn_zone` key in nginx, **restart** nginx - a reload fails
  silently and keeps the old config (only `/var/log/nginx/error.log` says so).
- Append to the Caddyfile in place (it's a single-file bind mount; replacing the file hides the change from
  the container), then `docker exec caddy caddy validate ...` and `caddy reload`.
- `setup-velo-host.sh` is safe to run again; it never touches Docker.
