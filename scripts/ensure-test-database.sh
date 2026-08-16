#!/usr/bin/env bash
set -euo pipefail

test_database="${MYSQL_TEST_DATABASE:-ai_order_platform_test}"
test_user="${MYSQL_TEST_USER:-ai_order_test}"
test_password="${MYSQL_TEST_PASSWORD:-change-me-test}"
test_bucket="${MINIO_TEST_BUCKET:-ai-order-test-private}"

if [[ ! "${test_database}" =~ ^[A-Za-z0-9_]+_test$ ]]; then
  echo "MYSQL_TEST_DATABASE must end with _test" >&2
  exit 1
fi

if [[ ! "${test_user}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_TEST_USER contains unsupported characters" >&2
  exit 1
fi

if [[ "${test_password}" == *"'"* ]]; then
  echo "MYSQL_TEST_PASSWORD must not contain a single quote" >&2
  exit 1
fi

if [[ ! "${test_bucket}" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] ||
   [[ ! "${test_bucket}" =~ (^|[.-])test([.-]|$) ]]; then
  echo "MINIO_TEST_BUCKET must be a valid test-only bucket name" >&2
  exit 1
fi

docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' <<SQL
CREATE DATABASE IF NOT EXISTS \`${test_database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${test_user}'@'%' IDENTIFIED BY '${test_password}';
ALTER USER '${test_user}'@'%' IDENTIFIED BY '${test_password}';
GRANT ALL PRIVILEGES ON \`${test_database}\`.* TO '${test_user}'@'%';
FLUSH PRIVILEGES;
SQL

docker compose exec -T minio sh -c '
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc mb --ignore-existing "local/'"${test_bucket}"'" >/dev/null
'

echo "Dedicated local test infrastructure is ready."
