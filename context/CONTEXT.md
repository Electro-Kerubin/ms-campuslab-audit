# CampusLab — Contexto técnico del proyecto

Este documento resume el proyecto completo para dar contexto a un agente de IA que asistirá en el desarrollo. Úsalo como referencia antes de generar código.

## 1. Descripción del negocio

CampusLab es una plataforma unificada para una red de **20 laboratorios** de instituciones de educación superior, que reemplaza la gestión actual por correo y planillas Excel. Problemas actuales: choques de horario, equipos no devueltos, nula visibilidad de ocupación.

### Objetivos del sistema
1. Reservar laboratorios y equipos por la web; administrar stock de insumos y coordinar uso de salas.
2. Notificar al estudiante (email/push) y al técnico de laboratorio (ticket de preparación).
3. Generar un panel de operaciones en tiempo real (reservas por hora, tiempo de ciclo, equipos ocupados).
4. Auditar eventos académicos (quién solicitó, aprobó, entregó o recibió de vuelta un equipo).

## 2. Actores y roles

| Rol | Responsabilidad |
|---|---|
| **Admin** | Administra el catálogo de labs/equipos y ve KPIs de ocupación |
| **Técnico (Operador)** | Aprueba reservas, prepara la sala y registra devoluciones |
| **Estudiante (Cliente)** | Solicita y sigue sus reservas |
| **Auditor** | Consulta el timeline. Solo lectura |

## 3. Alcance funcional (módulos)

| Módulo | Descripción | Actores | Reglas clave |
|---|---|---|---|
| **Gestión de reservas** | CRUD de reservas y cambio de estado (`SOLICITADA` → `APROBADA` → `EN_PREPARACIÓN` → `EN_USO` → `DEVUELTA` / `CANCELADA`) | Estudiante, Técnico | No se puede pasar a `EN_USO` sin `APROBAR` |
| **Catálogo** | CRUD de laboratorios, equipos e insumos disponibles | Admin | El stock/cupo disminuye al aprobar la reserva |
| **Notificaciones** | Email/push al estudiante y ticket de preparación al técnico | Técnico, Estudiante | Envío asíncrono (cola) |
| **Reportería** | Panel de KPIs: reservas por hora, tiempo de ciclo, equipos ocupados | Admin | Datos por streaming (Kafka) sin bloquear el core |
| **Auditoría** | Timeline de eventos de la reserva | Auditor | Solo lectura |

## 4. Seguridad e identidad (IDaaS Azure + API Gateway)

- **App Registration** "CampusLab": `clientId`, `redirectUri`, `authority = https://login.microsoftonline.com/<TENANT_ID>/`
- **MSAL Angular**: protege rutas (guards) y adjunta `Bearer <access_token>` a cada request (interceptor)
- **AWS API Gateway** (HTTP API) con JWT Authorizer:
  - `issuer = https://login.microsoftonline.com/<TENANT_ID>/v2.0`
  - `audience(s) = api://<API_CLIENT_ID>`
- **Spring Security** (en BFF y cada microservicio expuesto): valida el JWT vía `security.oauth2.resourceserver.jwt.issuer-uri` y comprueba que el rol pueda usar el endpoint solicitado.

Flujo de llamadas seguras (siempre en este orden):
```
JWT (Azure AD) → API Gateway → ms-campuslab-bff → microservicio de dominio
```

## 5. Microservicios de dominio

| Servicio | Dominio | DB | Responsabilidad | Exposición |
|---|---|---|---|---|
| `ms-campuslab-bookings` | Reservas | **PostgreSQL** | CRUD reservas, estados, coordinación de stock y notificación | `/api/bookings/*` |
| `ms-campuslab-catalog` | Labs/equipos/insumos | **PostgreSQL** | CRUD recursos, stock y cupos | `/api/catalog/*` |
| `ms-campuslab-notify` | Notificaciones | sin DB | Procesa envío email/webpush y ticket de prep. vía RabbitMQ | no público (consumidor RabbitMQ) |
| `ms-campuslab-audit` | Auditoría/timeline | **PostgreSQL** | Consume Kafka y persiste eventos | `/api/audit/*` (read-only) |
| `ms-campuslab-report` | KPIs/analytics | **PostgreSQL** | Agregaciones y endpoints de lectura (consume Kafka) | `/api/report/*` (read-only) |

> Nota: el documento original del caso especifica Oracle como motor de BD; **este proyecto usa PostgreSQL** en su lugar para todos los servicios que requieren persistencia (`bookings`, `catalog`, `audit`, `report`).

Además del listado anterior, el proyecto incluye:
- `ms-campuslab-bff` (Spring Boot + Spring Security): Backend For Frontend detrás del API Gateway.
- Un microservicio/administrador de RabbitMQ y otro de Kafka, según lo que pida cada evaluación puntual.

### Endpoints esenciales (ejemplos de referencia)

**ms-campuslab-bookings**
```
POST /api/bookings                      (crear reserva)
GET  /api/bookings/{id}
PUT  /api/bookings/{id}/status           body: { "status": "SOLICITADA|APROBADA|EN_PREPARACIÓN|EN_USO|DEVUELTA|CANCELADA" }
GET  /api/bookings?status=...&from=...&to=...
```

**ms-campuslab-catalog**
```
GET  /api/catalog/resources
POST /api/catalog/resources
PUT  /api/catalog/resources/{id}         (cupo/stock)
```

**ms-campuslab-report**
```
GET  /api/report/kpis?range=last24h
GET  /api/report/top-resources?range=last7d
```

## 6. Pantallas (frontend Angular)

| Pantalla | Ruta | Roles | Función |
|---|---|---|---|
| Login | `/login` | público | MSAL. Botón «Iniciar sesión con Microsoft» |
| Dashboard | `/dashboard` | todos los autenticados | Admin: ocupación de labs. Técnico: reservas por preparar. Estudiante: próximas reservas y estado |
| Reservas | `/bookings` | Admin, Técnico, Estudiante | Listar, crear (estudiante o técnico) y cambiar estado (técnico/admin) |
| Catálogo de recursos | `/catalog` | Admin, Técnico | Laboratorios, equipos e insumos |
| Reportería | `/reports` | Admin | Reservas por hora, tiempo de ciclo, recursos más usados |
| Auditoría | `/audit` | Admin, Auditor | Trazabilidad de la reserva. Filtros: usuario, fechas, tipo de evento |

## 7. Despliegue (AWS EC2 + Docker Compose)

- `ec2-apps`: bookings-svc, catalog-svc, notify-svc, report-svc, audit-svc
- `ec2-mq`: RabbitMQ (clúster de 2 nodos para las evaluaciones) con Management UI
- `ec2-kafka`: Zookeeper (3 nodos) + Kafka (3 brokers) + Kafka UI
- Un `compose.yml` para apps, otro para mq y otro para kafka
- **Security Groups**: abrir solo los puertos necesarios (AMQP/5672, Kafka/9092, HTTP APIs internas)

### Repositorios GitHub (multi-repo)
```
/frontend-campuslab       (Angular + MSAL)
/ms-campuslab-bff         (Spring Boot, Spring Security)
/ms-campuslab-bookings    (Spring Boot + PostgreSQL)
/ms-campuslab-catalog     (Spring Boot + PostgreSQL)
/ms-campuslab-notify      (Spring Boot, consumer RabbitMQ)
/ms-campuslab-report      (Spring Boot, consumer Kafka + PostgreSQL)
/ms-campuslab-audit       (Spring Boot, consumer Kafka + PostgreSQL)
/infra                    → /apps/compose.yml · /mq/compose.yml · /kafka/compose.yml
/docs
```

Estrategia de ramas: **GitFlow** en todos los repos (`main`, `develop`, `feature/*`, `bugfix/*`, `release/*`, `hotfix/*`). Convención de commits: **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`).

## 8. Topología RabbitMQ

**Exchanges:**
- `cmd.direct` (direct)
- `cmd.topic` (topic)
- `cmd.dead.dlx` (direct, para DLQ)

**Colas (6 = 3 flujos + 3 DLQ):**

| Cola principal | Propósito | DLQ | Binding direct | Binding topic |
|---|---|---|---|---|
| `q.cmd.email` | Email/push al estudiante (aprobación, sala lista, devolución) | `q.cmd.email.dlq` | `email.send` | `email.*` |
| `q.cmd.prep` | Ticket de preparación de sala/equipo al técnico | `q.cmd.prep.dlq` | `prep.ticket` | `prep.#` |
| `q.cmd.voucher` | Generación de PDF (vale de retiro o acta de devolución) | `q.cmd.voucher.dlq` | `voucher.gen` | `voucher.*` |

**Buenas prácticas obligatorias:**
- Envelope común en cada mensaje: `type`, `eventId`, `timestamp`, `traceId`, `correlationId`
- ACK/NACK explícitos
- Idempotencia en los consumidores
- Métricas de tasa de DLQ

## 9. Topología Kafka

| Tópico | Particiones | Réplicas | Política | Retención | Propósito |
|---|---|---|---|---|---|
| `bookings.events` | 3 | 3 | delete | 3–7 días | Fuente de verdad de eventos de la reserva. Alimenta reportería y auditoría |
| `audit.timeline` | 3 | 3 | compact,delete | 14–30 días | Historial quién/qué/cuándo/desde dónde |
| `*.DLT` (por consumidor) | 3 | 3 | delete | 7–14 días | Mensajes que fallaron tras N reintentos, con metadatos de error |

Infraestructura: Zookeeper (3 nodos) + Kafka (3 brokers) + Kafka UI.

## 10. Stack tecnológico consolidado

| Capa | Tecnología |
|---|---|
| Frontend | Angular + MSAL |
| Backend | Spring Boot (Java 21) |
| Base de datos relacional | **PostgreSQL** (en lugar de Oracle) |
| Identidad | Azure AD (IDaaS) |
| API Gateway | AWS API Gateway (HTTP API, JWT Authorizer) |
| Mensajería async (comandos/tareas) | RabbitMQ |
| Streaming (eventos/analítica) | Kafka + Zookeeper |
| Contenedores | Docker / Docker Compose |
| Infraestructura | AWS EC2 |
| Control de versiones | Git + GitHub, estrategia GitFlow |

## 11. Decisiones de arquitectura tomadas por el equipo

- **Multi-repo**: un repositorio por microservicio (no monolito), alineado con la sugerencia del caso y con el patrón estándar de microservicios.
- **PostgreSQL** reemplaza a Oracle como motor relacional en todos los servicios con persistencia.
- **GitFlow** como estrategia de ramas en todos los repositorios del proyecto.

## 12. Notas para el agente de IA al generar código

- Todo endpoint expuesto a través del BFF o del API Gateway debe validar JWT (issuer, audience, firma, expiración) antes de procesar la solicitud.
- Los cambios de estado de una reserva deben respetar la máquina de estados: `SOLICITADA → APROBADA → EN_PREPARACIÓN → EN_USO → DEVUELTA / CANCELADA`. No se permite saltar a `EN_USO` sin pasar por `APROBADA`.
- Los mensajes publicados a RabbitMQ deben incluir el envelope común (`type`, `eventId`, `timestamp`, `traceId`, `correlationId`) y los consumidores deben ser idempotentes.
- Los eventos publicados a Kafka en `bookings.events` son la fuente de verdad para `ms-campuslab-report` y `ms-campuslab-audit` — estos dos servicios no acceden directamente a la base de datos de `bookings`.
- Los repositorios JPA deben apuntar a PostgreSQL (driver `org.postgresql`, dialecto `PostgreSQLDialect` o el autodetectado por Hibernate).
- Seguir Conventional Commits y la estructura de carpetas por dominio (`controller/`, `service/`, `repository/`, `entity/`, `dto/`, `messaging/`, `security/`) dentro de cada microservicio.
