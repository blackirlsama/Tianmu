"""Prepare a dedicated site deployment without changing the existing staging database.

Private values remain in --config; this script never prints credentials.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--config', type=Path, required=True)
parser.add_argument('--release', required=True)
parser.add_argument('--initialize-db', action='store_true')
args = parser.parse_args()
config = args.config.resolve()
config.mkdir(mode=0o700, parents=True, exist_ok=True)
os.chmod(str(config), 0o700)
secret_dir = config / 'secrets'
secret_dir.mkdir(mode=0o700, exist_ok=True)

def write_private(path, body):
    path.write_text(body, encoding='utf-8')
    os.chmod(str(path), 0o600)

def secret(name, value=None):
    path = secret_dir / name
    if not path.exists():
        path.write_text(value or secrets.token_hex(24), encoding='utf-8')
        os.chmod(str(path), 0o400)
        os.chown(str(path), 10001, 10001)
    return path.read_text().strip()

values = {name: secret(name) for name in ('site-mysql-root-password', 'business-db-password', 'agent-db-password', 'minio-secret-key')}
values['replication-password'] = secret('replication-password', secrets.token_hex(12))
values['minio-access-key'] = secret('minio-access-key', 'tianmu-site-' + secrets.token_hex(5))
values['jwt-secret'] = secret('jwt-secret', base64.b64encode(secrets.token_bytes(48)).decode())
values['redis-password'] = secret('redis-password', Path('/opt/tianmu-staging/config/secrets/redis-password').read_text().strip())
if not (secret_dir / 'model-api-key').is_file():
    raise SystemExit('Install the model key in the private secrets directory first')

def env(name, settings):
    write_private(config / name, ''.join(key + '=' + str(value) + '\n' for key, value in settings.items()))

env('release.env', {'SITE_CONFIG_DIR': str(config), 'SITE_RELEASE': args.release, 'SITE_NETWORK': 'tianmu-staging_default'})
env('business.env', {
    'TIANMU_SERVER_ADDRESS': '0.0.0.0', 'TIANMU_JDBC_URL': 'jdbc:mysql://business-mysql:3306/tianmu_site?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&sslMode=DISABLED',
    'TIANMU_DB_USER': 'site_business', 'TIANMU_REDIS_HOST': 'redis', 'TIANMU_REDIS_PORT': 6379, 'TIANMU_REDIS_DATABASE': 1,
    'TIANMU_ES_URL': 'http://elasticsearch:9200', 'TIANMU_MINIO_URL': 'http://minio:9000', 'TIANMU_MINIO_BUCKET': 'tianmu-upload',
    'TIANMU_ROCKETMQ_NAMESERVER': 'rocketmq-namesrv:9876', 'TIANMU_CANAL_HOST': 'canal', 'TIANMU_CANAL_PORT': 11111,
    'TIANMU_CANAL_USER': '', 'TIANMU_CANAL_PASSWORD': '', 'AGENT_BRIDGE_ENABLED': 'true', 'BUSINESS_ACTIVE_USER_STATES': 'normal',
    'BUSINESS_TENANT_ID': 'tianmu-site', 'BUSINESS_DATETIME_ZONE': 'Asia/Shanghai',
    'JAVA_TOOL_OPTIONS': '-Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai',
})
env('agent.env', {
    'SERVER_ADDRESS': '0.0.0.0', 'SERVER_PORT': 8201, 'SPRING_PROFILES_ACTIVE': 'business,deepseek,persistent',
    'BUSINESS_BASE_URL': 'http://127.0.0.1:8101/api',
    'AGENT_JDBC_URL': 'jdbc:mysql://business-mysql:3306/tianmu_site_agent?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED',
    'AGENT_DB_USER': 'site_agent', 'AGENT_REDIS_HOST': 'redis', 'AGENT_REDIS_PORT': 6379,
    'AGENT_OBJECT_STORE': 'minio', 'AGENT_MINIO_URL': 'http://minio:9000', 'AGENT_MINIO_BUCKET': 'tianmu-agent-artifacts',
    'AGENT_MINIO_PREFIX': 'site-artifacts/', 'HARNESS_TRACE_SINK': 'kafka', 'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092',
    'KAFKA_TRACE_TOPIC': 'Tianmu.agent.trace.v1', 'HARNESS_TRACE_DIRECTORY': '/data/trace', 'HARNESS_TRACE_DETAILS_ENABLED': 'true',
    'PREPARATION_MODEL': 'deepseek-flash', 'HARNESS_MODEL': 'deepseek-v4-pro', 'HARNESS_LOOP_REQUEST_TIMEOUT': '240s',
    'HARNESS_LOOP_MODEL_TIMEOUT': '60s', 'AGENT_PREPARATION_INTENT_TIMEOUT': '20s', 'AGENT_PREPARATION_UNDERSTANDING_TIMEOUT': '20s',
    'AGENT_PREPARATION_EMOTION_TIMEOUT': '10s',
})
env('canal.env', {
    'canal.auto.scan': 'false', 'canal.destinations': 'example',
    'canal.instance.master.address': 'business-mysql:3306', 'canal.instance.dbUsername': 'site_canal',
    'canal.instance.dbPassword': values['replication-password'], 'canal.instance.connectionCharset': 'UTF-8',
    'canal.instance.filter.regex': r'tianmu_site\..*',
    'JAVA_OPTS': '-Xms64m -Xmx192m -Xmn64m -XX:ActiveProcessorCount=2',
})

tls = config / 'tls'
tls.mkdir(mode=0o700, exist_ok=True)
if not (tls / 'cert.pem').exists():
    (tls / 'openssl.cnf').write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=v3\n[dn]\nCN=120.53.45.251\n[v3]\nsubjectAltName=IP:120.53.45.251\n', encoding='utf-8')
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365', '-keyout', str(tls / 'key.pem'), '-out', str(tls / 'cert.pem'), '-config', str(tls / 'openssl.cnf')], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(str(tls / 'key.pem'), 0o600)

if args.initialize_db:
    acceptance_path = config / 'acceptance.json'
    if not acceptance_path.exists():
        write_private(acceptance_path, json.dumps({'account': 'acceptance@tianmu.local', 'password': 'Tm' + secrets.token_hex(6) + '9!'}))
    acceptance = json.loads(acceptance_path.read_text())
    def literal(value): return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"
    sql = 'CREATE DATABASE IF NOT EXISTS tianmu_site CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE DATABASE IF NOT EXISTS tianmu_site_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;\n'
    for user, password, database in [('site_business', values['business-db-password'], 'tianmu_site'), ('site_agent', values['agent-db-password'], 'tianmu_site_agent')]:
        sql += 'CREATE USER IF NOT EXISTS ' + literal(user) + "@'%' IDENTIFIED BY " + literal(password) + '; GRANT ALL ON ' + database + '.* TO ' + literal(user) + "@'%';\n"
    sql += "CREATE USER IF NOT EXISTS 'site_canal'@'%' IDENTIFIED WITH mysql_native_password BY " + literal(values['replication-password']) + "; ALTER USER 'site_canal'@'%' IDENTIFIED WITH mysql_native_password BY " + literal(values['replication-password']) + "; GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'site_canal'@'%';\nUSE tianmu_site;\n"
    sql += (Path(__file__).parent / 'business-schema.sql').read_text()
    hashed = hashlib.md5(('TianMu' + acceptance['password']).encode()).hexdigest()
    sql += "\nINSERT IGNORE INTO user(user_id,email,password,nickname,state,role) VALUES(10001," + literal(acceptance['account']) + ',' + literal(hashed) + ",'链路验收账号','normal','user'),(10002,'scope-check@tianmu.local'," + literal(hashlib.md5(secrets.token_bytes(32)).hexdigest()) + ",'权限隔离账号','normal','user');\n"
    sql += "INSERT IGNORE INTO user_stats(user_id,video_count,coin_count) VALUES(10001,0,10),(10002,0,10);\n"
    result = subprocess.run(['docker', 'exec', '-i', 'tianmu-site-business-mysql-1', 'sh', '-c', 'export MYSQL_PWD="$(cat /run/secrets/site-mysql-root-password)"; exec mysql --default-character-set=utf8mb4 -uroot'], input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        message = result.stderr.decode(errors='replace')
        for value in list(values.values()) + [acceptance['password']]: message = message.replace(value, '[redacted]')
        raise SystemExit(message)
    print(json.dumps({'databaseInitialized': True, 'acceptanceAccountsCreated': True, 'acceptanceVideosCreated': False, 'credentialsPrinted': False}))
else:
    print(json.dumps({'privateConfigurationPrepared': True, 'credentialsPrinted': False}))
