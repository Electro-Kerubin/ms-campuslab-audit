# 🧪 CampusLab

Plataforma unificada para la reserva de laboratorios y equipos académicos en una red de 20 laboratorios de instituciones de educación superior.

## 📋 Contexto

Actualmente los laboratorios se reservan por correo y el stock de insumos se lleva en planillas, generando choques de horario, equipos no devueltos y nula visibilidad de ocupación. CampusLab centraliza:

- Reserva de laboratorios y equipos vía web
- Administración de stock de insumos
- Notificación a estudiantes (email/push) y técnicos (ticket de preparación)
- Panel de operaciones en tiempo real (reservas por hora, tiempo de ciclo, equipos ocupados)
- Auditoría de eventos académicos (quién solicitó, aprobó, entregó o recibió de vuelta un equipo)

## 🏗️ Arquitectura

Sistema de **microservicios** con frontend Angular, backend Spring Boot, autenticación federada vía Azure AD, mensajería asíncrona con RabbitMQ y streaming de eventos con Kafka.

```
                    ┌─────────────┐
                    │   Angular   │
                    │  (MSAL)     │
                    └──────┬──────┘
                           │ JWT (Bearer)
                           ▼
                  ┌────────────────┐
                  │ AWS API Gateway│
                  │ (JWT Authorizer)│
                  └────────┬───────┘
                           ▼
                  ┌────────────────┐
                  │ ms-campuslab-bff│
                  │ (Spring Security)│
                  └────────┬───────┘
                           ▼
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ ms-bookings    │  │ ms-catalog     │  │ ms-notify      │
│ (PostgreSQL)   │  │ (PostgreSQL)   │  │ (sin DB)       │
└───────┬───────┘  └───────────────┘  └───────┬───────┘
        │ eventos                              │ consume
        ▼                                       ▼
┌───────────────┐                      ┌───────────────┐
│ Kafka          │                      │ RabbitMQ       │
│ (bookings.events)│                    │ (colas cmd.*)  │
└───────┬───────┘                      └───────────────┘
        ▼
┌───────────────┐  ┌───────────────┐
│ ms-audit       │  │ ms-report      │
│ (PostgreSQL)   │  │ (PostgreSQL)   │
└───────────────┘  └───────────────┘
```

### Flujo de seguridad

```
JWT (Azure AD) → API Gateway → ms-campuslab-bff → microservicio de dominio
```

- **App Registration** "CampusLab" en Azure AD: `clientId`, `redirectUri`, `authority = https://login.microsoftonline.com/<TENANT_ID>/`
- **MSAL Angular**: protege rutas y adjunta `Bearer <access_token>` a cada request
- **AWS API Gateway** (HTTP API) con JWT Authorizer: `issuer = https://login.microsoftonline.com/<TENANT_ID>/v2.0`, `audience = api://<API_CLIENT_ID>`
- **Spring Security** en cada servicio: valida el JWT vía `security.oauth2.resourceserver.jwt.issuer-uri` y autoriza según rol

### Roles

| Rol | Responsabilidad |
|---|---|
| Admin | Administra el catálogo de labs/equipos y ve KPIs de ocupación |
| Técnico (Operador) | Aprueba reservas, prepara la sala y registra devoluciones |
| Estudiante (Cliente) | Solicita y sigue sus reservas |
| Auditor | Consulta el timeline. Solo lectura |

## 📦 Repositorios del proyecto

| Repositorio | Descripción |
|---|---|
| `frontend-campuslab` | Angular + MSAL |
| `ms-campuslab-bff` | Backend For Frontend (Spring Boot + Spring Security) |
| `ms-campuslab-bookings` | Microservicio de reservas (Spring Boot + PostgreSQL) |
| `ms-campuslab-catalog` | Microservicio de catálogo de labs/equipos/insumos (Spring Boot + PostgreSQL) |
| `ms-campuslab-notify` | Consumidor RabbitMQ para notificaciones (Spring Boot) |
| `ms-campuslab-report` | Consumidor Kafka para reportería/KPIs (Spring Boot + PostgreSQL) |
| `ms-campuslab-audit` | Consumidor Kafka para auditoría/timeline (Spring Boot + PostgreSQL) |
| `infra` | `apps/compose.yml` · `mq/compose.yml` · `kafka/compose.yml` |
| `docs` | Documentación del proyecto |

> Se optó por **multi-repo** (un repositorio por microservicio) en línea con la arquitectura de microservicios del caso, permitiendo pipelines y despliegues independientes por servicio.

## 🗄️ Base de Datos

Se utiliza **PostgreSQL** como motor relacional para los microservicios `bookings`, `catalog`, `audit` y `report`. `notify` no requiere base de datos (solo consume mensajes).

## 🚀 Quick Start (por repositorio de backend)

### Requisitos Previos
- Java 21 (JDK Temurin)
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL 16+
- Acceso a RabbitMQ y Kafka (levantados desde `infra/`)

### Instalación

```bash
git clone <url-del-repositorio>
cd <nombre-microservicio>

mvn clean compile
mvn test
mvn package
```

### Ejecutar Localmente

```bash
# Con Maven
mvn spring-boot:run

# Con Docker
docker build -t <nombre-microservicio>:latest .
docker run -p <puerto>:<puerto> <nombre-microservicio>:latest
```

---

## 📋 Estrategia de Ramificación: GitFlow

Cada repositorio del proyecto (BFF, cada microservicio, frontend) sigue la misma estrategia GitFlow.

### ¿Por qué GitFlow?

- **Control y estabilidad**: `main` contiene siempre código estable y desplegable; `develop` integra los avances antes de llegar a producción.
- **Trabajo en equipo sin bloqueos**: con múltiples microservicios y 2 integrantes, cada quien puede avanzar en `feature/*` de su servicio sin interferir con el resto.
- **Corrección de errores urgente**: `hotfix/*` permite reparar bugs críticos en producción sin interrumpir el desarrollo en curso en `develop`.
- **Releases controlados**: al ser un sistema usado por 20 laboratorios en producción, GitFlow permite preparar y validar versiones mediante `release/*` antes de liberarlas.
- **Trazabilidad para auditoría**: dado que el propio sistema audita eventos académicos, es coherente mantener también un historial de cambios de código claro y trazable.

### Estructura de Ramas

```
main (producción)
└─ release/x.y.z
    └─ hotfix/<nombre-descriptivo>

develop (integración)
└─ feature/<nombre-descriptivo>
└─ bugfix/<nombre-descriptivo>
```

| Rama | Propósito |
|---|---|
| `main` | Código en producción, siempre estable |
| `develop` | Integración de features antes de pasar a producción |
| `feature/<nombre>` | Desarrollo de nuevas funcionalidades |
| `bugfix/<nombre>` | Corrección de errores detectados en desarrollo |
| `release/<version>` | Preparación y estabilización de una nueva versión |
| `hotfix/<nombre>` | Corrección urgente directamente sobre producción |

### Workflow Típico

**Crear nueva funcionalidad:**
```bash
git checkout develop
git pull origin develop
git checkout -b feature/nombre-descriptivo
# ... cambios ...
git commit -m "feat: descripcion del cambio"
git push -u origin feature/nombre-descriptivo
# Pull Request -> develop
```

**Hotfix de emergencia:**
```bash
git checkout main
git pull origin main
git checkout -b hotfix/nombre-descriptivo
# ... corrección ...
git commit -m "fix: descripcion de la correccion"
git push -u origin hotfix/nombre-descriptivo
# Pull Request -> main Y develop
```

---

## 📐 Guía de Buenas Prácticas

### Naming de ramas
- `feature/<nombre-descriptivo>` — ej: `feature/booking-status-transition`
- `bugfix/<nombre-descriptivo>`
- `hotfix/<nombre-descriptivo>` — ej: `hotfix/fix-jwt-audience-validation`
- `release/<version>` — ej: `release/1.0.0`

### Convención de mensajes de commit (Conventional Commits)

| Prefijo | Uso |
|---|---|
| `feat:` | Nueva funcionalidad |
| `fix:` | Corrección de bug |
| `docs:` | Cambios en documentación |
| `refactor:` | Refactorización sin cambio de comportamiento |
| `test:` | Agregar o modificar tests |
| `chore:` | Tareas de mantenimiento (configs, dependencias) |

Ejemplo: `feat: agregar endpoint de creacion de reserva`

### Estructura de carpetas (por microservicio)

```
src/
├── main/
│   ├── java/org/campuslab/<dominio>/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   ├── dto/
│   │   ├── messaging/       # productores/consumidores RabbitMQ o Kafka
│   │   └── security/
│   └── resources/
│       └── application.yml
└── test/
    └── java/org/campuslab/<dominio>/
```

### Control de versiones (Semantic Versioning)

- **MAJOR** (1.x.x → 2.0.0): cambios incompatibles en la API
- **MINOR** (1.0.x → 1.1.0): nueva funcionalidad compatible
- **PATCH** (1.0.0 → 1.0.1): corrección de bugs

## 🔑 Pull Requests y Revisión de Código

- Todo cambio hacia `develop` o `main` pasa por **Pull Request**, nunca push directo.
- Cada PR describe: qué cambia, por qué y cómo probarlo.
- El pipeline CI/CD (build + tests) debe pasar en verde antes de mergear.

## 👥 Equipo

- *(completar con integrantes del equipo)*

---

**Última actualización:** *(actualizar en cada cambio relevante)*
# ms-campuslab-audit
