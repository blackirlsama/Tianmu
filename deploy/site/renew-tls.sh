#!/bin/sh
set -eu
config=${1:-/opt/tianmu-site/config}
docker run --rm --network host \
  -v "$config/letsencrypt:/etc/letsencrypt" \
  -v "$config/acme-webroot:/var/www/acme" \
  certbot/certbot:v5.4.0 renew --non-interactive --quiet
certificate="$config/letsencrypt/live/120.53.45.251"
openssl x509 -in "$certificate/fullchain.pem" -noout -checkend 86400 >/dev/null
install -m 0644 "$certificate/fullchain.pem" "$config/tls/cert.pem.new"
install -m 0600 "$certificate/privkey.pem" "$config/tls/key.pem.new"
mv "$config/tls/cert.pem.new" "$config/tls/cert.pem"
mv "$config/tls/key.pem.new" "$config/tls/key.pem"
docker exec tianmu-site-frontend-1 nginx -t
docker exec tianmu-site-frontend-1 nginx -s reload
