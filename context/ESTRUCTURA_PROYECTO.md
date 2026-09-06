# CampusLab — Estructura de Proyecto (para agente de IA)

Este documento define la estructura de carpetas estándar a seguir al generar código para cualquier microservicio del proyecto CampusLab. Úsalo como referencia obligatoria antes de crear archivos nuevos.

## 1. Estructura de cada repositorio (nivel raíz)

Todos los repos de backend (`ms-campuslab-bff`, `ms-campuslab-bookings`, `ms-campuslab-catalog`, `ms-campuslab-notify`, `ms-campuslab-report`, `ms-campuslab-audit`) comparten este esqueleto:

```
ms-campuslab-<dominio>/
├── .github/
│   └── workflows/
│       └── deploy.yml
├── src/
│   ├── main/
│   │   ├── java/org/campuslab/<dominio>/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/          # scripts Flyway (solo si el servicio tiene BD)
│   └── test/
│       └── java/org/campuslab/<dominio>/
├── .gitignore
├── Dockerfile
├── docker-compose.yml                 # solo para levantar dependencias locales (Postgres, etc.)
├── pom.xml
└── README.md
```

## 2. Estructura interna de paquetes (por dominio)

Patrón que se repite dentro de `src/main/java/org/campuslab/<dominio>/` en cada microservicio con persistencia (`bookings`, `catalog`, `audit`, `report`):

```
org/campuslab/<dominio>/
├── <Dominio>ServiceApplication.java   # clase principal @SpringBootApplication
├── controller/
│   └── <Entidad>Controller.java
├── service/
│   └── <Entidad>Service.java
├── repository/
│   └── <Entidad>Repository.java       # interfaces JPA
├── entity/
│   └── <Entidad>.java                 # @Entity, mapeo a PostgreSQL
├── dto/
│   └── <Entidad>DTO.java
├── mapper/
│   └── <Entidad>Mapper.java           # opcional: entity <-> DTO
├── messaging/
│   ├── producer/                      # solo si publica eventos (ej. bookings -> Kafka)
│   └── consumer/                      # solo si consume (ej. audit, report, notify)
├── security/
│   └── SecurityConfig.java            # validación JWT, filterChain
├── exception/
│   ├── ResourceNotFoundException.java
│   └── GlobalExceptionHandler.java
└── config/
    └── (beans de configuración: RabbitMQ, Kafka, CORS, etc.)
```

## 3. Variaciones según el rol del servicio

| Servicio | Carpetas que NO aplican | Carpetas adicionales / notas |
|---|---|---|
| `ms-campuslab-bff` | `entity/`, `repository/` (no persiste datos propios) | `client/` — para llamar a los microservicios de dominio (WebClient/RestTemplate) |
| `ms-campuslab-bookings` | — | `messaging/producer/` — publica a `bookings.events` (Kafka) y a las colas RabbitMQ de notificación (`q.cmd.email`, `q.cmd.prep`, `q.cmd.voucher`) |
| `ms-campuslab-catalog` | `messaging/` (no participa en mensajería según el caso) | — |
| `ms-campuslab-notify` | `controller/` (no expone endpoints públicos), `repository/`, `entity/` (sin BD) | solo `messaging/consumer/` — consume las colas RabbitMQ |
| `ms-campuslab-audit` | `messaging/producer/` (solo consume) | `messaging/consumer/` — consume `bookings.events` y `audit.timeline` (Kafka) |
| `ms-campuslab-report` | `messaging/producer/` (solo consume) | `messaging/consumer/` — consume `bookings.events` (Kafka) para agregaciones/KPIs |

## 4. Repos que no siguen esta estructura (no son microservicios Spring Boot)

```
frontend-campuslab/          # Angular + MSAL (estructura estándar Angular CLI)

infra/
├── apps/
│   └── compose.yml
├── mq/
│   └── compose.yml
└── kafka/
    └── compose.yml

docs/
└── (documentación del proyecto, diagramas, etc.)
```

## 5. Reglas para el agente al generar código

- Respetar siempre el paquete raíz `org.campuslab.<dominio>` (ej: `org.campuslab.bookings`, `org.campuslab.catalog`).
- No crear `entity/` ni `repository/` en `ms-campuslab-bff` ni `ms-campuslab-notify` — no persisten datos propios.
- No crear `messaging/producer/` en `ms-campuslab-catalog`, `ms-campuslab-audit` ni `ms-campuslab-report` salvo que se indique explícitamente lo contrario.
- Todo microservicio con BD debe incluir sus migraciones Flyway en `resources/db/migration/`, numeradas y con nombre descriptivo (`V1__init_schema.sql`, `V2__add_stock_to_catalog.sql`, etc.).
- La validación de JWT (issuer, audience, firma, expiración) se implementa en `security/SecurityConfig.java` de cada servicio expuesto (BFF y microservicios con endpoints públicos).
- Los DTOs nunca deben exponer directamente las entidades JPA — siempre pasar por `dto/` (y `mapper/` si existe).
- Los mensajes publicados a RabbitMQ o Kafka deben incluir el envelope común: `type`, `eventId`, `timestamp`, `traceId`, `correlationId`.
