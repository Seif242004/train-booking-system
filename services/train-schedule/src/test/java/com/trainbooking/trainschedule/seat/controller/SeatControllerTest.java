package com.trainbooking.trainschedule.seat.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.ADMIN_SUB;
import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.assertj.core.api.Assertions.assertThat;
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

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.coach.model.CoachNotFoundException;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import com.trainbooking.trainschedule.seat.model.Seat;
import com.trainbooking.trainschedule.seat.model.SeatConflictException;
import com.trainbooking.trainschedule.seat.model.SeatRequest;
import com.trainbooking.trainschedule.seat.service.SeatService;
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

@WebMvcTest(SeatController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class SeatControllerTest {

    private static final String LIST = "[{\"seatNumber\":\"1A\",\"row\":1,\"column\":\"A\"},{\"seatNumber\":\"1B\",\"row\":1,\"column\":\"B\"}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeatService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void asPassenger_returns403_forEveryOperation() throws Exception {
        mvc.perform(get("/admin/coaches/1/seats").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/coaches/1/seats").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/seats/1").with(passenger()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumber\":\"1A\",\"row\":1,\"column\":\"A\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/seats/1").with(passenger())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listUsesTheSrsFieldNames() throws Exception {
        given(service.listByCoach(5)).willReturn(List.of(new Seat(1, 5, "1A", 1, "A", CoachClass.SECOND)));

        mvc.perform(get("/admin/coaches/5/seats").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seatNumber").value("1A"))
                .andExpect(jsonPath("$[0].row").value(1))
                .andExpect(jsonPath("$[0].column").value("A"))
                .andExpect(jsonPath("$[0].class").value("SECOND"))
                .andExpect(jsonPath("$[0].seat_row").doesNotExist());
    }

    @Test
    void asAdmin_bulkCreateTakesAJsonList_andReturns201WithTheCreatedSeats() throws Exception {
        given(service.addSeats(eq(5L), any(), eq(ADMIN_SUB))).willReturn(List.of(
                new Seat(1, 5, "1A", 1, "A", CoachClass.FIRST), new Seat(2, 5, "1B", 1, "B", CoachClass.FIRST)));

        mvc.perform(post("/admin/coaches/5/seats").with(admin()).contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].seatNumber").value("1B"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SeatRequest>> sent = ArgumentCaptor.forClass(List.class);
        verify(service).addSeats(eq(5L), sent.capture(), eq(ADMIN_SUB));
        assertThat(sent.getValue()).hasSize(2);
        assertThat(sent.getValue().get(0).seatNumber()).isEqualTo("1A");
    }

    @Test
    void asAdmin_aClassSentByTheClientIsNotPartOfTheRequest() throws Exception {
        given(service.addSeats(eq(5L), any(), any())).willReturn(List.of(new Seat(1, 5, "1A", 1, "A", CoachClass.THIRD)));

        mvc.perform(post("/admin/coaches/5/seats").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"seatNumber\":\"1A\",\"row\":1,\"column\":\"A\",\"class\":\"FIRST\"}]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].class").value("THIRD")); // the service decides, from the coach
    }

    @Test
    void asAdmin_bulkWithAnInvalidOrRepeatedSeat_returns400_withTheListPosition() throws Exception {
        given(service.addSeats(eq(5L), any(), any())).willThrow(new RequestValidationException(
                List.of(new ApiError.Detail("[1].seatNumber", "seat number 1A is repeated (also at position 0 in the list)"))));

        mvc.perform(post("/admin/coaches/5/seats").with(admin()).contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("[1].seatNumber"));
    }

    @Test
    void asAdmin_bulkThatClashesWithExistingSeats_returns409_withDetails() throws Exception {
        given(service.addSeats(eq(5L), any(), any())).willThrow(new SeatConflictException(
                List.of(new ApiError.Detail("[0].seatNumber", "seat 1A already exists in this coach"))));

        mvc.perform(post("/admin/coaches/5/seats").with(admin()).contentType(MediaType.APPLICATION_JSON).content(LIST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("seat_conflict"))
                .andExpect(jsonPath("$.error.details[0].field").value("[0].seatNumber"));
    }

    @Test
    void asAdmin_bulkBodyThatIsNotAList_returns400() throws Exception {
        mvc.perform(post("/admin/coaches/5/seats").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatNumber\":\"1A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_unknownCoach_returns404() throws Exception {
        given(service.listByCoach(99)).willThrow(new CoachNotFoundException(99));

        mvc.perform(get("/admin/coaches/99/seats").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("coach_not_found"));
    }

    @Test
    void asAdmin_updateValidatesTheBody_andDeleteReturns204() throws Exception {
        mvc.perform(put("/admin/seats/1").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatNumber\":\"\",\"row\":0,\"column\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[?(@.field=='seatNumber')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='row')]").exists())
                .andExpect(jsonPath("$.error.details[?(@.field=='column')]").exists());

        mvc.perform(delete("/admin/seats/1").with(admin())).andExpect(status().isNoContent());
        verify(service).delete(1, ADMIN_SUB);
    }
}
