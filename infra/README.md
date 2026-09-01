# Infrastructure (I0–I4)

Provisions the single Hetzner Cloud server (CLAUDE.md §11 Phase 0: CX33, Nuremberg) that runs the
backend + Caddy + Postgres via `compose.prod.yaml`. Run everything in this file yourself - it needs
your own Hetzner API token, which never gets pasted into a chat session.

## Prerequisites

- Terraform >= 1.7
- A Hetzner Cloud project and API token (Cloud Console → your project → Security → API Tokens,
  **Read & Write**). Export it, never commit it: `export HCLOUD_TOKEN=...`
- An SSH key pair for yourself (the "admin" account) - e.g. `~/.ssh/id_ed25519`.
- A second SSH key pair for CI ("deploy") - generate a fresh one, don't reuse your own:
  `ssh-keygen -t ed25519 -f ./deploy_key -C "video-service-ci"` (do this outside the repo, or
  make sure `deploy_key` never gets committed). The private half becomes the `HETZNER_SSH_KEY`
  GitHub secret in **both** `video-service` and `video-service-frontend`.

## 1. Apply Terraform (I1 + I2)

```bash
cd infra
terraform init
terraform plan \
  -var "admin_ssh_public_key=$(cat ~/.ssh/id_ed25519.pub)" \
  -var "deploy_ssh_public_key=$(cat ./deploy_key.pub)"
terraform apply \
  -var "admin_ssh_public_key=$(cat ~/.ssh/id_ed25519.pub)" \
  -var "deploy_ssh_public_key=$(cat ./deploy_key.pub)"
```

Note the `server_ipv4` output. Verify cloud-init finished before doing anything else:

```bash
ssh bjarne@<server_ipv4>            # your admin_username variable, default "bjarne"
cloud-init status --wait            # should end in "status: done"
sudo tail -50 /var/log/cloud-init-output.log   # check for errors if it doesn't
docker compose version              # confirm Docker installed
```

Confirm the `deploy` user is actually restricted before relying on it:

```bash
ssh -i ./deploy_key deploy@<server_ipv4> whoami
# expected: rejected, NOT a shell prompt (deploy.sh only accepts specific commands)
```

## 2. Manual steps (not automatable from here)

**Domain + DNS (I0/I4).** Once you've registered a domain, point an A record (and AAAA, using
`server_ipv6`) at the Terraform output. The actual `DOMAIN=` line goes into `.env` in step 4 below
- Caddy picks it up on the next `docker compose up -d` and issues a certificate automatically.

**JWT signing key.** `SecurityConfig` falls back to an ephemeral in-memory key if none is
configured - fine for tests, wrong for production (every restart would invalidate all sessions).
Generate a real one and copy it to the server:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -pubout -in jwt-private.pem -out jwt-public.pem

scp jwt-private.pem jwt-public.pem bjarne@<server_ipv4>:/tmp/
ssh bjarne@<server_ipv4> '
  sudo mv /tmp/jwt-private.pem /tmp/jwt-public.pem /opt/video-service/secrets/
  sudo chown root:root /opt/video-service/secrets/jwt-*.pem
  sudo chmod 644 /opt/video-service/secrets/jwt-*.pem
'
rm jwt-private.pem jwt-public.pem   # don't leave the private key lying around locally
```

644 (world-readable), not 600: the app container reads this file as its own internal non-root
user, which docker's bind mount can't map back to a host account - fine here since this is a
single-tenant box with no untrusted local users, unlike `storagebox_ed25519` below (a real SSH
key, read directly by the `ssh` client, which does enforce strict permissions).

**Object storage bucket (§2.1/§9.1).** Create the bucket in the Hetzner Cloud Console (Object
Storage). The backend's own `S3BucketInitializer`
(`src/main/java/com/bjarne/videoservice/config/S3BucketInitializer.java`) sets CORS and the
public-read policy itself on first use - don't configure those in the console too, or the two can
drift apart. Generate an access key pair for the bucket - you'll need it for `.env` in step 4.

## 3. Assemble `.env` and trigger the first deploy

`.env` never travels through CI (it's gitignored and holds secrets) - it's created once, by hand,
directly on the server:

```bash
scp .env.example bjarne@<server_ipv4>:/tmp/.env
ssh bjarne@<server_ipv4>
sudo mv /tmp/.env /opt/video-service/.env
sudo nano /opt/video-service/.env   # fill in every value - domain, bucket keys, DB password, ...
sudo chown deploy:deploy /opt/video-service/.env
```

`deploy` needs to own it (not just read it) because `deploy.sh` - which runs `docker compose`
during every release - executes as that unprivileged user, no sudo.

Then, in **both** GitHub repos (`video-service` and `video-service-frontend`) → Settings → Secrets
and variables → Actions, add:

- `HETZNER_SSH_KEY` - the private half of the `deploy_key` pair from the Prerequisites section
- `HETZNER_HOST` - the `server_ipv4` Terraform output

Push a tag to trigger the first real deploy:

```bash
git tag v0.1.0 && git push origin v0.1.0        # in video-service
git tag v0.1.0 && git push origin v0.1.0        # in video-service-frontend
```

Watch both repos' Actions tabs. Once green, verify:

```bash
curl -H "Host: your-domain.example" http://<server_ipv4>/api/actuator/health   # before DNS propagates
curl https://your-domain.example/api/actuator/health                          # after
```

## 4. Backups (I3) - do this once the stack is live

Order a Storage Box in the Hetzner Robot console (separate from the Cloud Console - Storage Box is
a Robot product). Then:

```bash
ssh-keygen -t ed25519 -f storagebox_ed25519 -N ""
# upload storagebox_ed25519.pub to the Storage Box via the Robot console
scp storagebox_ed25519 bjarne@<server_ipv4>:/tmp/
ssh bjarne@<server_ipv4> "sudo mv /tmp/storagebox_ed25519 /opt/video-service/secrets/ && sudo chmod 600 /opt/video-service/secrets/storagebox_ed25519 && sudo chown root:root /opt/video-service/secrets/storagebox_ed25519"

openssl rand -base64 32 | ssh bjarne@<server_ipv4> "sudo tee /opt/video-service/secrets/restic-password && sudo chmod 600 /opt/video-service/secrets/restic-password"

# Add to /opt/video-service/.env: STORAGEBOX_HOST=uXXXXXX.your-storagebox.de, STORAGEBOX_USER=uXXXXXX

# One-time restic init (run as root on the server, same RESTIC_ARGS backup.sh builds):
ssh bjarne@<server_ipv4>
sudo bash -c '
  set -a; source /opt/video-service/.env; set +a
  restic -r "sftp:${STORAGEBOX_USER}@${STORAGEBOX_HOST}:/backups/video-service" \
    -o "sftp.command=ssh -i /opt/video-service/secrets/storagebox_ed25519 -o StrictHostKeyChecking=accept-new -s sftp" \
    --password-file /opt/video-service/secrets/restic-password \
    init
'
```

The cron job in `/etc/cron.d/video-service-backup` (installed by cloud-init) then runs
`backup.sh` daily at 03:15 automatically.

**Object storage versioning.** Enable bucket versioning in the Hetzner Cloud Console as an extra
safety net alongside the restic backups (CLAUDE.md §11 - the optional `rclone` sync to a second
provider is left for later, not needed at this scale).

## 5. Restore test (do this once, for real)

CLAUDE.md §11: "A backup whose restore has never been executed is not a backup." Before trusting
this setup:

1. `restic -r ... snapshots` - confirm at least one snapshot exists.
2. `restic -r ... restore latest --target /tmp/restore-test`
3. Spin up a throwaway Postgres container, `psql < /tmp/restore-test/videoservice-*.sql`, and spot
   check a few rows against what's actually in production.
4. Delete the throwaway container and `/tmp/restore-test` afterwards.

## 6. Monitoring (Prometheus + Grafana, AP9) - optional, do this once you want dashboards

**DNS.** Add another A record (and AAAA) for `grafana.<your-domain>` pointing at the same
`server_ipv4`/`server_ipv6` as the main domain - Grafana gets its own Caddy site block and
therefore its own automatic TLS certificate (`Caddyfile.prod`).

**Shared secret for Prometheus's scrape auth.** `/api/actuator/prometheus` requires Basic Auth
(`SecurityConfig`). The app reads the password from `.env`; Prometheus's own config file can't
read `.env`, so it reads the same value from a plain file instead - same pattern as
`restic-password` in step 4 above:

```bash
openssl rand -base64 24 | ssh bjarne@<server_ipv4> "sudo tee /opt/video-service/secrets/prometheus-metrics-password && sudo chmod 644 /opt/video-service/secrets/prometheus-metrics-password && sudo chown root:root /opt/video-service/secrets/prometheus-metrics-password"
```

644, not 600 - same reasoning as the JWT keys in step 2: the `prometheus` container reads it as
its own internal user, which the bind mount can't map back to a host account, and this is a
single-tenant box.

Then, in `/opt/video-service/.env`:

```bash
sudo nano /opt/video-service/.env
```

- `APP_ACTUATOR_METRICS_USERNAME=prometheus` (not secret, must match `prometheus.prod.yml`)
- `APP_ACTUATOR_METRICS_PASSWORD=<the value you just generated above>` - must be *exactly* the
  same string as `secrets/prometheus-metrics-password`, or the app rejects Prometheus's scrapes
  with 401. The two are separate files by necessity (env var vs. static YAML config) and are not
  kept in sync automatically.
- `GRAFANA_ADMIN_PASSWORD=<a real password>` - replaces the dev-only `"admin"` default.

Redeploy to pick up the new services:

```bash
ssh bjarne@<server_ipv4> "cd /opt/video-service && sudo docker compose -f compose.prod.yaml up -d"
```

**Verify:**

```bash
curl https://grafana.your-domain.example                    # Grafana login page
ssh bjarne@<server_ipv4> "cd /opt/video-service && sudo docker compose -f compose.prod.yaml exec prometheus wget -qO- http://localhost:9090/api/v1/targets"
# look for "health":"up" on the video-service job
```

Prometheus itself is intentionally never exposed through Caddy (only Grafana is) - its UI is
still reachable ad hoc for raw PromQL queries via an SSH tunnel, since `compose.prod.yaml` binds
it to the server's own loopback only (`127.0.0.1:9090:9090`):

```bash
ssh -L 9090:localhost:9090 bjarne@<server_ipv4>
# then open http://localhost:9090 locally
```

Grafana comes fully provisioned from the repo (`grafana/provisioning/`): the Prometheus
datasource plus three dashboards ("Service Health", "Media Pipeline", "Auth & Content") in the
"Video Service" folder. Prometheus evaluates the alert rules in `prometheus/alerts.yml`
(CLAUDE.md AP9 queue alerting) - firing alerts are visible in its UI and on the Service Health
dashboard, but nothing notifies anyone until an Alertmanager is added. All of these are
bind-mounted server files, NOT part of the app image: the release workflow rsyncs them and
`deploy.sh`'s "deploy files" copies them into place and restarts prometheus/grafana. Remember
that `deploy.sh` itself is baked in by cloud-init - a change to it must be copied to
`/opt/video-service/deploy.sh` manually (as root, keeping it non-writable for `deploy`).

## 7. Growing past Phase 0

Change `server_type` in `variables.tf` (or pass `-var server_type=cx43`) and `terraform apply` -
Hetzner resizes the existing server, no new resources needed (CLAUDE.md §11 Phase 1/2). A second,
`worker`-only container on the same or a separate box is a `compose.prod.yaml` change, not an
infra change (see the commented-out example service in that file).
