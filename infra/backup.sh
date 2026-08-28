#!/bin/bash
# Daily backup: pg_dump -> restic, onto the Hetzner Storage Box (CLAUDE.md §11/I3). Runs as root
# via cron (/etc/cron.d/video-service-backup) since it needs to read /opt/video-service/.env and
# the restic SSH key under /opt/video-service/secrets/.
#
# One-time setup before this works (see README.md):
#   1. Order a Storage Box in the Hetzner Robot console, note its host and username.
#   2. Generate a dedicated SSH key pair, upload the public half to the Storage Box, place the
#      private half at /opt/video-service/secrets/storagebox_ed25519 (chmod 600).
#   3. Pick a restic repository password, write it (nothing else) to
#      /opt/video-service/secrets/restic-password (chmod 600).
#   4. Set STORAGEBOX_HOST and STORAGEBOX_USER in /opt/video-service/.env.
#   5. Run `restic init` once by hand with the same RESTIC_ARGS this script builds.
set -euo pipefail

APP_DIR=/opt/video-service
set -a
# shellcheck disable=SC1091
source "$APP_DIR/.env"
set +a

RESTIC_ARGS=(
  -r "sftp:${STORAGEBOX_USER}@${STORAGEBOX_HOST}:/backups/video-service"
  -o "sftp.command=ssh -i $APP_DIR/secrets/storagebox_ed25519 -o StrictHostKeyChecking=accept-new -s sftp"
)
export RESTIC_PASSWORD_FILE="$APP_DIR/secrets/restic-password"

DUMP_NAME="videoservice-$(date +%F).sql"

docker compose -f "$APP_DIR/compose.prod.yaml" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" \
  | restic "${RESTIC_ARGS[@]}" backup --stdin --stdin-filename "$DUMP_NAME"

# Keep 14 daily, 8 weekly, 6 monthly snapshots - restic prunes the rest.
restic "${RESTIC_ARGS[@]}" forget --prune --keep-daily 14 --keep-weekly 8 --keep-monthly 6
