package org.campuslab.audit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.campuslab.audit.dto.AuditEventResponse;
import org.campuslab.audit.security.SecurityConfig;
import org.campuslab.audit.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** API de lectura: rutas, parámetros, seguridad por rol y formato de los errores. El servicio va mockeado. */
@WebMvcTest(AuditController.class)
@Import({SecurityConfig.class, FakeJwtDecoderConfig.class})
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditEventService service;

    private static RequestPostProcessor withRole(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static AuditEventResponse event() {
        return new AuditEventResponse(1L, "evt-1", "BOOKING_APPROVED", "Reserva aprobada por el técnico", 7L, 3L,
                "tec@duocuc.cl", "Rodrigo Muñoz", "TECNICO", "200.1.2.3",
                Instant.parse("2026-10-19T03:15:00Z"), "trace-1", "corr-1", Map.of("status", "APROBADA"));
    }

    @Test
    void elAdminYElAuditorPuedenConsultarElHistorial() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), eq(200))).thenReturn(List.of(event()));

        for (String role : List.of("ADMIN", "AUDITOR")) {
            mockMvc.perform(get("/api/audit/events").with(withRole(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].type").value("BOOKING_APPROVED"))
                    .andExpect(jsonPath("$[0].actorEmail").value("tec@duocuc.cl"))
                    .andExpect(jsonPath("$[0].actorRole").value("TECNICO"))
                    .andExpect(jsonPath("$[0].clientIp").value("200.1.2.3"))
                    .andExpect(jsonPath("$[0].occurredAt").exists())
                    .andExpect(jsonPath("$[0].details.status").value("APROBADA"));
        }
    }

    @Test
    void losFiltrosLlegranAlServicio() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), eq(50))).thenReturn(List.of());

        mockMvc.perform(get("/api/audit/events").with(withRole("AUDITOR"))
                        .param("type", "BOOKING_APPROVED").param("actor", "tec").param("bookingId", "7")
                        .param("from", "2026-10-19T00:00:00Z").param("to", "2026-10-20T00:00:00Z").param("limit", "50"))
                .andExpect(status().isOk());

        verify(service).search("BOOKING_APPROVED", "tec", 7L,
                Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"), 50);
    }

    @Test
    void sinFiltrosSeUsanLosValoresPorDefecto() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), eq(200))).thenReturn(List.of());

        mockMvc.perform(get("/api/audit/events").with(withRole("ADMIN"))).andExpect(status().isOk());

        verify(service).search(isNull(), isNull(), isNull(), isNull(), isNull(), eq(200));
    }

    @Test
    void elTimelineDeUnaReservaSeConsultaPorSuId() throws Exception {
        when(service.timeline(7L)).thenReturn(List.of(event()));

        mockMvc.perform(get("/api/audit/bookings/7/events").with(withRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(7));
    }

    @Test
    void unFiltroInvalidoDaUn400ConUnMensajeClaro() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), eq(0))).thenThrow(new IllegalArgumentException("limit debe estar entre 1 y 1000"));

        mockMvc.perform(get("/api/audit/events").with(withRole("ADMIN")).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("limit debe estar entre 1 y 1000"));
    }

    @Test
    void unaFechaMalEscritaDaUn400() throws Exception {
        mockMvc.perform(get("/api/audit/events").with(withRole("ADMIN")).param("from", "ayer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'from' tiene un formato inválido"));

        verifyNoInteractions(service);
    }

    @Test
    void unBookingIdNoNumericoDaUn400() throws Exception {
        mockMvc.perform(get("/api/audit/bookings/abc/events").with(withRole("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    // ---------- Seguridad ----------

    @Test
    void losDemasRolesNoPuedenVerLaAuditoria() throws Exception {
        for (String role : List.of("TECNICO", "ESTUDIANTE")) {
            mockMvc.perform(get("/api/audit/events").with(withRole(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void unTokenSinRolesTampocoPuede() throws Exception {
        mockMvc.perform(get("/api/audit/events").with(jwt())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mockMvc.perform(get("/api/audit/events")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void laAuditoriaEsDeSoloLecturaNingunVerboDeEscrituraExiste() throws Exception {
        for (var request : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/audit/events"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/audit/events"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/audit/events"))) {
            mockMvc.perform(request.with(withRole("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is4xxClientError());
        }
    }
}
