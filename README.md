# Monitoring (med brug af feature flag)

**Stack:** Java 21, Javalin, Micrometer, Prometheus, Grafana, Docker Compose.

Projektet indeholder et færdigt Grafana-dashboard. Ingen React frontend og ingen database, så backend kaldes direkte via HTTP-requests, eksempelvis med `curl` eller Postman.

Demoen anvender **feature flags** til at aktivere og deaktivere en rabatfunktion i en webshop (se
evt. [eksemplet med feature flags](https://github.com/Tine-m/feature-flags.git)). Inklusion af feature flag i illustrerer et vigtigt DevOps-princip: Deployment er ikke afslutningen på udviklingsprocessen. Vi skal også kunne overvåge, hvordan softwaren fungerer i drift, opdage problemer og reagere på dem. Hvis målinger viser, at den nye rabatfunktion medfører mange fejl eller lange svartider, kan vi deaktivere den ved hjælp af feature flaget – **uden at skulle deploye en ny version af applikationen**.

Demoen illustrerer hvordan monitoring og feature flags tilsammen kan understøtte en mere kontrolleret og pålidelig softwarelevering. Feature flags er dog ikke en
forudsætning for monitoring

## 1. Start


```bash
docker compose up --build -d
```

Første build downloader Maven-afhængigheder og Docker-images og kan tage lidt tid.

- App: http://localhost:8080/api/health
- Metrics: http://localhost:8080/metrics
- Prometheus: http://localhost:9090/targets (target `discount-app` skal være **UP**)
- Grafana: http://localhost:3000 (brugernavn `admin`, adgangskode `admin`)
- Grafana-dashboard: **Dashboards → Teaching → Discount feature — Monitoring demo** (oprettes automatisk).

## 2. Afprøv endpoints

Aktivér feature flag og kald dicount endpoint med hhv. gyldig og ugyldig rabatkode.

I Git Bash / macOS / Linux:

```bash
curl -X POST 'http://localhost:8080/admin/discount-feature?enabled=true'
curl 'http://localhost:8080/api/discount?code=SAVE10&amount=100'
curl -i 'http://localhost:8080/api/discount?code=WRONG&amount=100'
```

I PowerShell (Windows):

```powershell
Invoke-RestMethod -Method Post 'http://localhost:8080/admin/discount-feature?enabled=true'
Invoke-RestMethod 'http://localhost:8080/api/discount?code=SAVE10&amount=100'
Invoke-RestMethod 'http://localhost:8080/api/discount?code=WRONG&amount=100'
```

For at udløse en **bevidst HTTP 500-fejl**:

```bash
curl -i 'http://localhost:8080/api/discount?code=CRASH&amount=100'
```

I PowerShell:

```powershell
try { Invoke-WebRequest 'http://localhost:8080/api/discount?code=CRASH&amount=100' } catch { $_.Exception.Message }
```

CRASH beregnet til at fremkalde en teknisk fejl med flaget aktiveret. 

Slå funktionen fra:

```bash
curl -X POST 'http://localhost:8080/admin/discount-feature?enabled=false'
```

PowerShell:

```powershell
Invoke-RestMethod -Method Post 'http://localhost:8080/admin/discount-feature?enabled=false'
```

Kald discount endpoint med gyldig rabatkode. Når flaget er slået fra, returnerer discount-endpointen HTTP 409; det er en **forventet afvisning**, ikke en HTTP
500-fejl.

## 3. Skab trafik

Aktivér flaget igen.
Kør derefter loop med 30 gentagelser.
Kør fra **Git Bash / macOS / Linux**:

```bash
for i in {1..30}; do
  curl -s -o /dev/null 'http://localhost:8080/api/discount?code=SAVE10&amount=100'
  curl -s -o /dev/null 'http://localhost:8080/api/discount?code=CRASH&amount=100'
  sleep 1
done
```

Kør fra **PowerShell**:

```powershell
1..30 | ForEach-Object {
  try { Invoke-WebRequest 'http://localhost:8080/api/discount?code=SAVE10&amount=100' | Out-Null } catch {}
  try { Invoke-WebRequest 'http://localhost:8080/api/discount?code=CRASH&amount=100' | Out-Null } catch {}
  Start-Sleep -Seconds 1
}
```

Dashboardet viser trafik og fejl som *requests pr. sekund* samt kumulative antal accepterede rabatter og tekniske fejl.
Vent ca. et minut på at `rate(...[1m])` får et tydeligt forløb.

## 4. Eksempler på PromQL

```promql
# Alle discount requests pr. sekund
sum(rate(demo_http_requests_total{route="discount"}[1m]))

# HTTP 500-fejl pr. sekund
sum(rate(demo_http_requests_total{route="discount",status=~"5.."}[1m]))

# Andel 5xx blandt discount-requests (0-100 %)
100 * sum(rate(demo_http_requests_total{route="discount",status=~"5.."}[1m]))
  / clamp_min(sum(rate(demo_http_requests_total{route="discount"}[1m])), 0.000001)

# Samlet antal anvendte rabatkoder
 discount_applied_total

# Samlet antal tekniske fejl
 discount_errors_total

# Antal forsøg de seneste 5 minutter
 increase(discount_attempts_total[5m])
```

**Vigtigt:** `*_total` er det navn, Prometheus eksponerer for Micrometer Counters. En Counter er kumulativ og nulstilles
ved genstart. `rate` kræver flere datapunkter og er ustabil ved meget lidt trafik.

## 5. Fejlsøgning

- Se containerstatus: `docker compose ps`.
- Se backendlogs: `docker compose logs -f app`.
- Hvis Prometheus target er DOWN: kontrollér at appen kører, og at `http://localhost:8080/metrics` svarer. Prometheus
  bruger **`app:8080`**, ikke `localhost:8080`, internt i Docker-netværket.
- Hvis Grafana er tom: vælg dashboardet under **Teaching**, vent på data, og tjek tidsintervallet (seneste 15 minutter). Ellers prøv: `docker compose restart grafana`
- Hvis port 8080, 9090 eller 3000 allerede bruges: stop den anden service eller tilpas host-porten i `compose.yaml`.
- Stop: `docker compose down`.
