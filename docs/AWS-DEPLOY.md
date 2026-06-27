# AWS-DEPLOY.md — ship with the fewest AWS services possible

**Design principle:** minimize the AWS surface. We deliberately collapse what
would normally be 8–10 services into **two** (EC2 + S3), plus DNS. Everything
else runs in Docker on a single box. This keeps cost, ops burden, and the number
of things that can break as low as possible for two part-time founders.

---

## What we use (and what we deliberately avoid)

| Need | Minimal choice | What we avoid (and why) |
|---|---|---|
| Compute | **1× EC2** instance running Docker Compose | ECS/Fargate, EKS — extra moving parts |
| Database | **Postgres + TimescaleDB in Docker** on the EC2 box, data on an EBS volume | RDS — added cost; revisit when paying customers justify it |
| Queue | **Postgres `job_queue` table** | SQS — avoided entirely (see system knowledge §7) |
| Cache | **Redis in Docker** | ElastiCache — unnecessary at this scale |
| TLS + routing | **Caddy in Docker** (auto HTTPS via Let's Encrypt) | ALB + API Gateway + ACM — three services replaced by one container |
| Auth | **Self-issued JWT** in the backend | Cognito — added service; only if SSO demanded |
| Object storage | **S3** (backups + exports + digest cards) | — (S3 is the one genuinely-worth-it managed service) |
| DNS | Route 53 **or** any external registrar | — |
| Secrets | `.env` file on the box, `chmod 600`, never committed | Secrets Manager — optional later |

Net AWS services: **EC2 + S3 (+ DNS).** That's it.

---

## Topology

```
            Internet
               |
          (DNS A record)
               |
        ┌──────────────────────────── EC2 (1 instance) ───────────────┐
        │  Caddy  ──auto-TLS──  reverse proxy                          │
        │     ├── api.<domain>  -> backend:8080 (Spring Boot, Java 21) │
        │     └── app.<domain>  -> web:3000     (Next.js)             │
        │  Postgres+TimescaleDB (Docker, data on EBS)                  │
        │  Redis (Docker)                                              │
        │  in-process worker (categorise/digest/budget) in backend     │
        └──────────────────────────────────────────────────────────────┘
                                   |
                                  S3  (nightly pg_dump backups, exports, cards)
```

---

## One-time setup

### 1. Provision the box
```
- EC2: t4g.small (ARM, cheap) to start; t4g.medium when load grows.
- OS: Amazon Linux 2023 or Ubuntu 22.04 (ARM).
- EBS: separate 30–50 GB gp3 volume mounted at /data for Postgres durability.
- Security group: inbound 80 + 443 only (and 22 from your IP). Nothing else.
- Elastic IP attached so the address is stable.
```

### 2. Install Docker + Compose
```bash
sudo dnf install -y docker && sudo systemctl enable --now docker   # AL2023
# or apt on Ubuntu
sudo usermod -aG docker $USER
# install the docker compose plugin
```

### 3. DNS
```
A   api.<yourdomain>  -> <Elastic IP>
A   app.<yourdomain>  -> <Elastic IP>
```

### 4. Lay down the deploy files
`/opt/cadence/` on the box:
```
/opt/cadence/
  docker-compose.prod.yml
  Caddyfile
  .env                 # chmod 600, from ENV-VARIABLES.md (prod values)
  /data/postgres/      # on the mounted EBS volume
```

### 5. Caddyfile (auto-TLS, two vhosts)
```
api.<yourdomain> {
    reverse_proxy backend:8080
}
app.<yourdomain> {
    reverse_proxy web:3000
}
```
Caddy fetches and renews Let's Encrypt certs automatically — no ACM, no ALB.

### 6. docker-compose.prod.yml (shape)
```yaml
services:
  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    depends_on: [backend, web]

  postgres:
    image: timescale/timescaledb:latest-pg16
    environment:
      POSTGRES_USER: ${DATABASE_USER}
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD}
      POSTGRES_DB: cadence
    volumes:
      - /data/postgres:/var/lib/postgresql/data
    restart: always

  redis:
    image: redis:7
    restart: always

  backend:
    image: <your-registry>/cadence-backend:latest   # or build on box
    env_file: .env
    depends_on: [postgres, redis]
    restart: always
    # Flyway migrations run on startup; worker runs in-process

  web:
    image: <your-registry>/cadence-web:latest
    environment:
      NEXT_PUBLIC_API_BASE: https://api.<yourdomain>
    depends_on: [backend]
    restart: always

volumes:
  caddy_data:
```

> No separate worker service: the categorisation/digest/budget jobs run as
> in-process scheduled tasks inside the backend (virtual threads + the Postgres
> `job_queue`). One fewer container to operate.

---

## Deploy / update flow

Build images (locally or in CI), push to a registry (ECR or Docker Hub — Docker
Hub keeps you off another AWS service), then on the box:

```bash
cd /opt/cadence
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f backend   # watch migrations
```

If building on the box instead of a registry, replace `image:` with `build:`
context paths and run `docker compose ... up -d --build`.

---

## Backups (the one thing you must not skip)

Nightly `pg_dump` to S3, plus EBS snapshots:

```bash
# cron on the box, e.g. 03:00 daily
docker exec cadence-postgres pg_dump -U "$DATABASE_USER" cadence | gzip \
  | aws s3 cp - "s3://<your-bucket>/backups/cadence-$(date +%F).sql.gz"
```
Also enable a daily EBS snapshot schedule on the /data volume via AWS Backup or
a lifecycle rule. S3 lifecycle: expire backups after 30–90 days.

---

## Prod `.env` (the box) — minimum

See `ENV-VARIABLES.md` for the full list. Minimum to run Phase 2:
```
DATABASE_URL=postgres://cadence:<strong-pw>@postgres:5432/cadence
DATABASE_USER=cadence
DATABASE_PASSWORD=<strong-pw>
JWT_SIGNING_SECRET=<32+ byte random>
REDIS_URL=redis://redis:6379
SERVER_PORT=8080
DEFAULT_ORG_PRIVACY=categories_only
ANTHROPIC_API_KEY=sk-...
AWS_S3_BUCKET=<your-bucket>
AWS_REGION=<region>
# Phase 3 adds: STRIPE_*, EMAIL_*, model + cron vars (see ENV-VARIABLES.md)
```
The box needs an IAM instance role granting `s3:PutObject`/`GetObject` on the
backup bucket only — no access keys in the env.

---

## When to graduate (and what to add, one at a time)
Only add a service when a real problem forces it:
- Paying customers + data-loss fear → move Postgres to **RDS** (keep everything
  else as is).
- Real traffic spikes → put **CloudFront** in front of `app.` for static assets.
- A customer demands SSO → add **Cognito** for that org.
Until then, two services is the whole footprint. Resist adding more.
