#!/bin/bash
# Forced command for the restricted `deploy` SSH user (see cloud-init.yaml.tpl). This is the
# ONLY thing that key can ever run - CI never gets an interactive shell or root, so a leaked CI
# secret is limited to exactly what this script allows.
set -euo pipefail

APP_DIR=/opt/video-service
INCOMING_DIR="$APP_DIR/incoming"

case "${SSH_ORIGINAL_COMMAND:-}" in
  "rsync --server"*)
    # File transfers go through rrsync, confined to $INCOMING_DIR - never the real target paths
    # directly, so a partial/failed transfer can never leave a half-written prod file in place.
    exec /usr/local/bin/rrsync "$INCOMING_DIR"
    ;;
  "deploy files")
    cp "$INCOMING_DIR/compose.prod.yaml" "$APP_DIR/compose.prod.yaml"
    cp "$INCOMING_DIR/Caddyfile.prod" "$APP_DIR/Caddyfile.prod"
    cd "$APP_DIR"
    # Caddyfile.prod is bind-mounted, so a content-only change (no different image, no changed
    # volumes line) is invisible to `docker compose up -d` in the "deploy backend" case below -
    # it has no way to tell the file's *contents* changed, only whether the service definition
    # did, so it would never restart caddy on its own. Reload picks the new config up live and
    # without dropping connections. Skipped (not failed) when caddy isn't running yet - the very
    # first deploy has nothing to reload, and "deploy backend"'s `docker compose up -d` starts it
    # fresh with this file already in place.
    if docker compose -f compose.prod.yaml ps --status running --services 2>/dev/null | grep -qx caddy; then
      docker compose -f compose.prod.yaml exec caddy caddy reload --config /etc/caddy/Caddyfile
    fi
    ;;
  "deploy frontend")
    rsync -a --delete "$INCOMING_DIR/frontend/" "$APP_DIR/frontend-dist/"
    ;;
  "deploy backend "*)
    TAG="${SSH_ORIGINAL_COMMAND#deploy backend }"
    if ! [[ "$TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
      echo "deploy.sh: rejected tag '$TAG'" >&2
      exit 1
    fi
    cd "$APP_DIR"
    IMAGE_TAG="$TAG" docker compose -f compose.prod.yaml pull app
    IMAGE_TAG="$TAG" docker compose -f compose.prod.yaml up -d
    ;;
  *)
    echo "deploy.sh: rejected command: ${SSH_ORIGINAL_COMMAND:-<empty>}" >&2
    exit 1
    ;;
esac
