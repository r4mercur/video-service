#!/bin/sh
# One-time bootstrap for the local Garage cluster, run by the garage-init service against
# the already-running garage container's admin API (port 3903).
#
# Garage has no "root user" like MinIO's env-var-based setup - a layout, an access key and
# a bucket all have to be created explicitly before the S3 API is usable. The garage CLI can
# do this too, but only against its OWN local metadata directory (it refuses to read/generate
# a node identity remotely) - the garage image also ships as a bare static binary with no
# shell, so it can't run this script itself. The admin HTTP API has neither restriction and
# is meant for exactly this kind of external automation, hence a separate curl-based
# container instead of a `docker exec`/wrapper-entrypoint approach.
#
# Every step is safe to re-run (container restart, `compose up` again): layout is only
# staged/applied while version 0 (untouched), and key import / bucket create tolerate a 409
# (already exists) - `bucket/allow` and the website PUT are idempotent by nature.
set -eu

ADMIN="http://garage:3903"
AUTH="Authorization: Bearer ${GARAGE_ADMIN_TOKEN}"

echo "garage-init: waiting for admin API..."
until curl -sf -o /dev/null -H "$AUTH" "$ADMIN/v1/status"; do
    sleep 1
done

LAYOUT_VERSION=$(curl -sf -H "$AUTH" "$ADMIN/v1/layout" | sed -n 's/.*"version": \([0-9]*\).*/\1/p' | head -1)
if [ "$LAYOUT_VERSION" = "0" ]; then
    NODE_ID=$(curl -sf -H "$AUTH" "$ADMIN/v1/status" | sed -n 's/.*"node": *"\([^"]*\)".*/\1/p' | head -1)
    echo "garage-init: staging layout for node $NODE_ID..."
    curl -sf -H "$AUTH" -H "Content-Type: application/json" -X POST "$ADMIN/v1/layout" \
        -d "[{\"id\": \"$NODE_ID\", \"zone\": \"dc1\", \"capacity\": ${GARAGE_CAPACITY_BYTES:-1000000000}, \"tags\": []}]" >/dev/null
    curl -sf -H "$AUTH" -H "Content-Type: application/json" -X POST "$ADMIN/v1/layout/apply" \
        -d '{"version": 1}' >/dev/null
else
    echo "garage-init: layout already applied (version $LAYOUT_VERSION), skipping."
fi

echo "garage-init: importing bootstrap key..."
KEY_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" -H "Content-Type: application/json" \
    -X POST "$ADMIN/v1/key/import" \
    -d "{\"accessKeyId\": \"$GARAGE_BOOTSTRAP_KEY_ID\", \"secretAccessKey\": \"$GARAGE_BOOTSTRAP_KEY_SECRET\", \"name\": \"video-service\"}")
case "$KEY_STATUS" in
    200|409) ;;
    *) echo "garage-init: key import failed with HTTP $KEY_STATUS"; exit 1 ;;
esac

echo "garage-init: creating bucket..."
BUCKET_BODY="/tmp/bucket.json"
BUCKET_STATUS=$(curl -s -o "$BUCKET_BODY" -w '%{http_code}' -H "$AUTH" -H "Content-Type: application/json" \
    -X POST "$ADMIN/v1/bucket" -d "{\"globalAlias\": \"$GARAGE_BOOTSTRAP_BUCKET\"}")
case "$BUCKET_STATUS" in
    200) ;;
    409) curl -sf -H "$AUTH" -o "$BUCKET_BODY" "$ADMIN/v1/bucket?globalAlias=$GARAGE_BOOTSTRAP_BUCKET" ;;
    *) echo "garage-init: bucket create failed with HTTP $BUCKET_STATUS"; exit 1 ;;
esac
BUCKET_ID=$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' "$BUCKET_BODY" | head -1)

echo "garage-init: granting key access on bucket $BUCKET_ID..."
curl -sf -H "$AUTH" -H "Content-Type: application/json" -X POST "$ADMIN/v1/bucket/allow" \
    -d "{\"bucketId\": \"$BUCKET_ID\", \"accessKeyId\": \"$GARAGE_BOOTSTRAP_KEY_ID\", \"permissions\": {\"read\": true, \"write\": true, \"owner\": true}}" >/dev/null

# Garage has no S3 bucket-policy API (PutBucketPolicy always 501s, verified against v1.0.1) -
# anonymous public reads only work through the website config enabled here (see the
# Caddyfile's @publicAssets block, which targets Garage's vhost-routed s3_web port for this
# reason). S3BucketInitializer's own ensureBucketPublicReadPolicy() call still runs on every
# app startup and simply no-ops against Garage's 501.
echo "garage-init: enabling website access..."
curl -sf -H "$AUTH" -H "Content-Type: application/json" -X PUT "$ADMIN/v1/bucket?id=$BUCKET_ID" \
    -d '{"websiteAccess": {"enabled": true, "indexDocument": "index.html"}}' >/dev/null

echo "garage-init: bootstrap complete."
