package com.trainbooking.trainschedule.train.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.ADMIN_SUB;
import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.train.model.Train;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import com.trainbooking.trainschedule.train.model.TrainNumberTakenException;
import com.trainbooking.trainschedule.train.model.TrainStatus;
import com.trainbooking.trainschedule.train.service.TrainService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrainController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class TrainControllerTest {

    private static final String BODY = "{\"number\":\"E901\",\"name\":\"Cairo Express\",\"type\":\"Express\",\"status\":\"ACTIVE\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TrainService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void withoutToken_returns401() throws Exception {
        mvc.perform(get("/admin/trains")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/trains").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/trains/1").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/trains").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/trains/1").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/trains/1").with(passenger())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listPassesTheFiltersToTheService_andUsesTheApiFieldNames() throws Exception {
        given(service.list("E9", "cairo", "ACTIVE")).willReturn(List.of(new Train(3, "E901", "Cairo Express", "Express", TrainStatus.ACTIVE)));

        mvc.perform(get("/admin/trains").param("number", "E9").param("name", "cairo").param("status", "ACTIVE").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value("E901"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].train_number").doesNotExist());
    }

    @Test
    void asAdmin_invalidStatusFilter_returns400() throws Exception {
        given(service.list(any(), any(), eq("SOMETIMES")))
                .willThrow(new RequestValidationException(List.of(new ApiError.Detail("status", "must be ACTIVE or INACTIVE"))));

        mvc.perform(get("/admin/trains").param("status", "SOMETIMES").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
    }

    @Test
    void asAdmin_createReturns201_withRelativeLocation_andPassesTheAdminId() throws Exception {
        given(service.create(any(), eq(ADMIN_SUB))).willReturn(new Train(7, "E901", "Cairo Express", "Express", TrainStatus.ACTIVE));

        mvc.perform(post("/admin/trains").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/trains/7"))
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void asAdmin_invalidBody_returns400_withFieldDetails() throws Exception {
        mvc.perform(post("/admin/trains").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\":\"\",\"name\":\"X\",\"type\":\"Express\",\"status\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='number')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='status')]").exists());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_duplicateNumber_returns409() throws Exception {
        given(service.create(any(), any())).willThrow(new TrainNumberTakenException("E901"));

        mvc.perform(post("/admin/trains").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("train_number_taken"));
    }

    @Test
    void asAdmin_unknownTrain_returns404() throws Exception {
        given(service.get(99)).willThrow(new TrainNotFoundException(99));

        mvc.perform(get("/admin/trains/99").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("train_not_found"));
    }

    @Test
    void asAdmin_updateAndDelete() throws Exception {
        given(service.update(eq(7L), any(), eq(ADMIN_SUB))).willReturn(new Train(7, "E901", "Cairo Express", "Express", TrainStatus.INACTIVE));

        mvc.perform(put("/admin/trains/7").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(delete("/admin/trains/7").with(admin())).andExpect(status().isNoContent());
        verify(service).delete(7, ADMIN_SUB);
    }
}
