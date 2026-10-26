package com.trainbooking.trainschedule.station;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Security and validation of the station API, with the service mocked so no database is needed.
 * The ADMIN rule is checked here exactly as the filter chain applies it in production.
 */
@WebMvcTest(StationController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class StationControllerTest {

    private static final String VALID_BODY = "{\"code\":\"CAI\",\"name\":\"Cairo Ramses\",\"city\":\"Cairo\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StationService service;

    // Required by the resource-server configuration; the tests build the authentication themselves.
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-sub")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor passenger() {
        return jwt().jwt(token -> token.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void withoutToken_returns401() throws Exception {
        mvc.perform(get("/admin/stations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
        verifyNoInteractions(service);
    }

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/stations").with(passenger()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));
        mvc.perform(post("/admin/stations").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/stations/1").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/stations/1").with(passenger()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listsStations() throws Exception {
        given(service.list()).willReturn(List.of(new Station(1, "CAI", "Cairo Ramses", "Cairo")));

        mvc.perform(get("/admin/stations").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CAI"))
                .andExpect(jsonPath("$[0].city").value("Cairo"));
    }

    @Test
    void asAdmin_createReturns201_withRelativeLocation_andPassesTheAdminId() throws Exception {
        given(service.create(any(), eq("admin-sub"))).willReturn(new Station(7, "CAI", "Cairo Ramses", "Cairo"));

        mvc.perform(post("/admin/stations").with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/stations/7"))
                .andExpect(jsonPath("$.id").value(7));
        verify(service).create(any(), eq("admin-sub"));
    }

    @Test
    void asAdmin_invalidBody_returns400_withFieldDetails() throws Exception {
        mvc.perform(post("/admin/stations").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"C\",\"name\":\"\",\"city\":\"Cairo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='code')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='name')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_malformedJson_returns400() throws Exception {
        mvc.perform(post("/admin/stations").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
    }

    @Test
    void asAdmin_duplicateCode_returns409() throws Exception {
        given(service.create(any(), any())).willThrow(new StationCodeTakenException("CAI"));

        mvc.perform(post("/admin/stations").with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("station_code_taken"));
    }

    @Test
    void asAdmin_unknownStation_returns404() throws Exception {
        given(service.get(99)).willThrow(new StationNotFoundException(99));

        mvc.perform(get("/admin/stations/99").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("station_not_found"));
    }

    @Test
    void asAdmin_nonNumericId_returns400() throws Exception {
        mvc.perform(get("/admin/stations/abc").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
    }

    @Test
    void asAdmin_deleteReturns204() throws Exception {
        mvc.perform(delete("/admin/stations/7").with(admin()))
                .andExpect(status().isNoContent());
        verify(service).delete(7, "admin-sub");
    }
}
