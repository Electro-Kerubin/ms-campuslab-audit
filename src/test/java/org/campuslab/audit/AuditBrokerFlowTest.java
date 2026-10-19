package org.campuslab.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * El flujo completo contra Kafka y PostgreSQL REALES: un mensaje con el formato de ms-campuslab-bookings
 * se publica, el consumidor lo registra, y la API lo devuelve a un ADMIN/AUDITOR.
 *
 * Usa un tópico y un grupo únicos por ejecución (no toca bookings.events ni el servicio real) y borra lo que crea.
 * Se omite si no hay Kafka en localhost:9092 o PostgreSQL en localhost:5436
 * (docker compose up -d en este repo y en campuslab-infra/kafka con compose.dev.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditBrokerFlowTest {

    private static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    private static final int DB_PORT = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5436"));

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "bookings.events.it-" + SUFFIX;
    private static final String DLT = TOPIC + ".DLT";
    private static final String EVENT_PREFIX = "it-" + SUFFIX + "-";

    // bookingId únicos por test para no cruzarse con datos reales ni entre tests
    private static final AtomicLong NEXT_BOOKING = new AtomicLong(9_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000));

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("campuslab.audit.topic", () -> TOPIC);
        registry.add("spring.kafka.consumer.group-id", () -> "audit-it-" + SUFFIX);
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
        // Si un mensaje inválido se reintentara, este back-off de 10 s retrasaría al siguiente: el test lo detectaría
        registry.add("campuslab.audit.retry.initial-interval-ms", () -> 10_000);
        registry.add("campuslab.audit.retry.max-attempts", () -> 3);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireBrokers() {
        assumeThat(reachable("localhost", DB_PORT)).as("No hay PostgreSQL en localhost:%d", DB_PORT).isTrue();
        String[] kafkaAddress = BOOTSTRAP.split(",")[0].split(":");
        assumeThat(reachable(kafkaAddress[0], Integer.parseInt(kafkaAddress[1]))).as("No hay Kafka en %s", BOOTSTRAP).isTrue();
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM audit_events WHERE event_id LIKE ?", EVENT_PREFIX + "%");
    }

    @AfterAll
    static void dropTopics() {
        if (!reachable(BOOTSTRAP.split(",")[0].split(":")[0], Integer.parseInt(BOOTSTRAP.split(",")[0].split(":")[1]))) {
            return;   // la clase se omitió: no hay nada que borrar
        }
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", BOOTSTRAP))) {
            admin.deleteTopics(List.of(TOPIC, DLT)).all().get();
        } catch (Exception ignored) {
            // el tópico puede no existir (p. ej. la DLT si nunca se usó)
        }
    }

    private static RequestPostProcessor role(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    // ---------- Flujo completo ----------

    @Test
    void unEventoRealDeBookingsQuedaRegistradoYLoVeElAuditor() throws Exception {
        long booking = NEXT_BOOKING.incrementAndGet();
        Instant when = Instant.parse("2026-10-19T04:52:24.653502Z");
        kafka.send(TOPIC, String.valueOf(booking),
                wire("e1", booking, "APROBADA", "tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "200.1.2.3", when));

        awaitEvents(booking, 1);

        mockMvc.perform(get("/api/audit/events").with(role("AUDITOR")).param("bookingId", String.valueOf(booking)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(EVENT_PREFIX + "e1"))
                .andExpect(jsonPath("$[0].type").value("BOOKING_APPROVED"))
                .andExpect(jsonPath("$[0].typeDescription").exists())
                .andExpect(jsonPath("$[0].bookingId").value(booking))
                .andExpect(jsonPath("$[0].resourceId").value(3))
                .andExpect(jsonPath("$[0].actorEmail").value("tec@duocuc.cl"))
                .andExpect(jsonPath("$[0].actorName").value("Rodrigo Muñoz"))
                .andExpect(jsonPath("$[0].actorRole").value("TECNICO"))
                .andExpect(jsonPath("$[0].clientIp").value("200.1.2.3"))
                .andExpect(jsonPath("$[0].occurredAt").value(when.toString()))
                .andExpect(jsonPath("$[0].details.status").value("APROBADA"))
                .andExpect(jsonPath("$[0].details.studentEmail").value("alumno@duocuc.cl"));
    }

    @Test
    void unMensajeRepetidoSeGuardaUnaSolaVez() throws Exception {
        long booking = NEXT_BOOKING.incrementAndGet();
        Instant when = Instant.parse("2026-10-19T05:00:00Z");
        String key = String.valueOf(booking);
        kafka.send(TOPIC, key, wire("dup", booking, "APROBADA", "tec@duocuc.cl", "T", "TECNICO", "1.1.1.1", when));
        kafka.send(TOPIC, key, wire("dup", booking, "APROBADA", "tec@duocuc.cl", "T", "TECNICO", "1.1.1.1", when));
        // misma clave => misma partición => se procesa en orden: cuando el "testigo" aparece, el duplicado ya se vio
        kafka.send(TOPIC, key, wire("testigo", booking, "EN_PREPARACION", "tec@duocuc.cl", "T", "TECNICO", "1.1.1.1", when.plusSeconds(1)));

        awaitEvents(booking, 2);

        assertThat(count(booking)).isEqualTo(2);
    }

    @Test
    void elTimelineYLosFiltrosFuncionanSobreDatosReales() throws Exception {
        long booking = NEXT_BOOKING.incrementAndGet();
        String key = String.valueOf(booking);
        Instant t1 = Instant.parse("2026-10-19T06:00:00Z");
        Instant t2 = t1.plusSeconds(60);
        Instant t3 = t1.plusSeconds(120);
        kafka.send(TOPIC, key, wire("t1", booking, "SOLICITADA", "alumno@duocuc.cl", "Ana Torres", "ESTUDIANTE", "192.168.1.5", t1));
        kafka.send(TOPIC, key, wire("t2", booking, "APROBADA", "tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "10.0.0.8", t2));
        kafka.send(TOPIC, key, wire("t3", booking, "EN_USO", "tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "10.0.0.8", t3));
        awaitEvents(booking, 3);
        String id = String.valueOf(booking);

        // timeline: del hecho más antiguo al más reciente
        mockMvc.perform(get("/api/audit/bookings/{id}/events", booking).with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("BOOKING_CREATED"))
                .andExpect(jsonPath("$[1].type").value("BOOKING_APPROVED"))
                .andExpect(jsonPath("$[2].type").value("BOOKING_IN_USE"));

        // búsqueda: del más reciente al más antiguo
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id))
                .andExpect(jsonPath("$[0].type").value("BOOKING_IN_USE"))
                .andExpect(jsonPath("$[2].type").value("BOOKING_CREATED"));
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id).param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BOOKING_IN_USE"));

        // filtros combinables
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id).param("type", "BOOKING_APPROVED"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actorName").value("Rodrigo Muñoz"));
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id).param("actor", "RODRIGO"))   // sin distinguir mayúsculas
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id).param("actor", "alumno@"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actorRole").value("ESTUDIANTE"));
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id).param("actor", "100%"))   // % se busca literal
                .andExpect(jsonPath("$.length()").value(0));
        // rango de fechas: from inclusivo, to exclusivo
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("bookingId", id)
                        .param("from", t2.toString()).param("to", t3.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BOOKING_APPROVED"));

        // tipo desconocido y rango invertido
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("type", "BOOKING_INVENTADO"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/audit/events").with(role("ADMIN")).param("from", t3.toString()).param("to", t1.toString()))
                .andExpect(status().isBadRequest());

        // quien no es ADMIN ni AUDITOR no ve nada
        mockMvc.perform(get("/api/audit/events").with(role("ESTUDIANTE")).param("bookingId", id))
                .andExpect(status().isForbidden());
    }

    // ---------- Errores ----------

    @Test
    void unMensajeIlegibleVaALaDltSinReintentosYElConsumidorSigueVivo() throws Exception {
        long booking = NEXT_BOOKING.incrementAndGet();
        String key = String.valueOf(booking);
        long start = System.nanoTime();
        kafka.send(TOPIC, key, "{esto no es json");
        kafka.send(TOPIC, key, wire("despues", booking, "CANCELADA", "alumno@duocuc.cl", "Ana", "ESTUDIANTE", "1.2.3.4",
                Instant.parse("2026-10-19T07:00:00Z")));

        // el evento válido de DESPUÉS se registra: el mensaje malo no bloquea la partición.
        // Con reintentos (back-off de 10 s) esto tardaría más de 10 s.
        awaitEvents(booking, 1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(9));

        assertThat(dltValues()).contains("{esto no es json");
    }

    @Test
    void unEventoSinBookingIdVaALaDltYNoSeGuardaNada() throws Exception {
        long booking = NEXT_BOOKING.incrementAndGet();
        String incomplete = "{\"type\":\"BOOKING_APROBADA\",\"eventId\":\"" + EVENT_PREFIX + "sin-booking\","
                + "\"timestamp\":1792384344.0,\"payload\":{\"status\":\"APROBADA\"}}";
        kafka.send(TOPIC, String.valueOf(booking), incomplete);
        kafka.send(TOPIC, String.valueOf(booking), wire("ok", booking, "APROBADA", "tec@duocuc.cl", "T", "TECNICO", "1.1.1.1",
                Instant.parse("2026-10-19T08:00:00Z")));

        awaitEvents(booking, 1);

        assertThat(dltValues()).contains(incomplete);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_id = ?", Long.class,
                EVENT_PREFIX + "sin-booking")).isZero();
    }

    // ---------- helpers ----------

    /** Mensaje con el mismo formato que serializa ms-campuslab-bookings (timestamp como segundos decimales). */
    private static String wire(String id, long bookingId, String status, String email, String name, String role, String ip, Instant at) {
        String eventId = EVENT_PREFIX + id;
        String timestamp = at.getEpochSecond() + "." + String.format("%09d", at.getNano());
        return "{\"type\":\"BOOKING_" + status + "\",\"eventId\":\"" + eventId + "\",\"timestamp\":" + timestamp
                + ",\"traceId\":\"" + eventId + "\",\"correlationId\":\"" + eventId + "\",\"payload\":{"
                + "\"bookingId\":" + bookingId + ",\"resourceId\":3,\"studentEmail\":\"alumno@duocuc.cl\","
                + "\"status\":\"" + status + "\",\"startTime\":\"2026-10-20T10:00\",\"endTime\":\"2026-10-20T12:00\","
                + "\"actorEmail\":\"" + email + "\",\"actorName\":\"" + name + "\",\"actorRole\":\"" + role + "\","
                + "\"clientIp\":\"" + ip + "\"}}";
    }

    private void awaitEvents(long bookingId, int expected) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(count(bookingId)).isGreaterThanOrEqualTo(expected));
    }

    private long count(long bookingId) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE booking_id = ?", Long.class, bookingId);
        return total == null ? 0 : total;
    }

    /** Todo lo que hay en la DLT del tópico de este test. */
    private List<String> dltValues() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> values = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLT));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
                return !values.isEmpty();
            });
        }
        return values;
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
