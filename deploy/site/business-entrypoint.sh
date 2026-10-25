#!/bin/sh
set -eu
for name in TIANMU_DB_PASSWORD TIANMU_REDIS_PASSWORD TIANMU_MINIO_ACCESS_KEY TIANMU_MINIO_SECRET_KEY TIANMU_CANAL_PASSWORD JWT_SECRET_BASE64; do
    file="/run/secrets/$name"
    if [ -s "$file" ]; then export "$name=$(cat "$file")"; fi
done
exec java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -jar /opt/tianmu/business.jar "$@"
