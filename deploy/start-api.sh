#!/bin/sh
set -eu

read_secret() {
  file="$1"
  if [ ! -r "$file" ]; then
    echo "Required secret file is missing" >&2
    exit 1
  fi
  value=$(cat "$file")
  if [ -z "$value" ]; then
    echo "Required secret file is empty" >&2
    exit 1
  fi
  printf '%s' "$value"
}

DB_PASSWORD=$(read_secret /run/secrets/db_password)
CHECKIN_SIGNING_KEY=$(read_secret /run/secrets/checkin_signing_key)
export DB_PASSWORD CHECKIN_SIGNING_KEY
if [ -r /run/secrets/wechat_app_secret ]; then
  WECHAT_APP_SECRET=$(cat /run/secrets/wechat_app_secret)
  export WECHAT_APP_SECRET
fi
exec gosu 10001:10001 java -jar /app/backend.jar --server.address=0.0.0.0 "$@"
