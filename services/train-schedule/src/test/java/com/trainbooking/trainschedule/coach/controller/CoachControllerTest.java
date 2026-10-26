package com.trainbooking.trainschedule.coach.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.coach.model.Coach;
import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.model.CoachCodeTakenException;
import com.trainbooking.trainschedule.coach.model.CoachNotFoundException;
import com.trainbooking.trainschedule.coach.model.CoachPositionTakenException;
import com.trainbooking.trainschedule.coach.model.CoachRequest;
import com.trainbooking.trainschedule.coach.service.CoachService;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.train.model.TrainNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(CoachController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class CoachControllerTest {

    private static final String BODY = "{\"code\":\"A1\",\"class\":\"FIRST\",\"position\":1,\"standingCapacity\":20}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CoachService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/trains/1/coaches").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/trains/1/coaches").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/coaches/1").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/coaches/1").with(passenger())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listReturnsCoachesWithTheSrsFieldNames() throws Exception {
        given(service.listByTrain(4)).willReturn(List.of(new Coach(9, 4, "A1", CoachClass.FIRST, 1, 20)));

        mvc.perform(get("/admin/trains/4/coaches").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].class").value("FIRST"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[0].standingCapacity").value(20))
                .andExpect(jsonPath("$[0].trainId").value(4));
    }

    @Test
    void asAdmin_createReadsTheJsonFieldNamedClass() throws Exception {
        given(service.create(eq(4L), any(), eq(ADMIN_SUB))).willReturn(new Coach(9, 4, "A1", CoachClass.FIRST, 1, 20));

        mvc.perform(post("/admin/trains/4/coaches").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9));

        ArgumentCaptor<CoachRequest> request = ArgumentCaptor.forClass(CoachRequest.class);
        verify(service).create(eq(4L), request.capture(), eq(ADMIN_SUB));
        assertThat(request.getValue().coachClass()).isEqualTo("FIRST");
        assertThat(request.getValue().standingCapacity()).isEqualTo(20);
    }

    @Test
    void asAdmin_invalidBody_namesTheFieldClass_notCoachClass() throws Exception {
        mvc.perform(post("/admin/trains/4/coaches").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"A1\",\"class\":\"FOURTH\",\"position\":0,\"standingCapacity\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[?(@.field=='class')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='position')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='standingCapacity')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='coachClass')]").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_missingStandingCapacityOrPosition_returns400() throws Exception {
        mvc.perform(post("/admin/trains/4/coaches").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"A1\",\"class\":\"FIRST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[?(@.field=='position')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='standingCapacity')]").exists());
    }

    @Test
    void asAdmin_duplicatePositionAndCode_return409WithDifferentCodes() throws Exception {
        given(service.create(eq(4L), any(), any())).willThrow(new CoachPositionTakenException(1));
        mvc.perform(post("/admin/trains/4/coaches").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("coach_position_taken"));

        given(service.update(eq(9L), any(), any())).willThrow(new CoachCodeTakenException("A1"));
        mvc.perform(put("/admin/coaches/9").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("coach_code_taken"));
    }

    @Test
    void asAdmin_unknownTrainOrCoach_return404() throws Exception {
        given(service.listByTrain(99)).willThrow(new TrainNotFoundException(99));
        mvc.perform(get("/admin/trains/99/coaches").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("train_not_found"));

        org.mockito.Mockito.doThrow(new CoachNotFoundException(99)).when(service).delete(99, ADMIN_SUB);
        mvc.perform(delete("/admin/coaches/99").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("coach_not_found"));
    }
}
