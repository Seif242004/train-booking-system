package com.trainbooking.trainschedule.fare.controller;

import static com.trainbooking.trainschedule.testsupport.TestJwts.ADMIN_SUB;
import static com.trainbooking.trainschedule.testsupport.TestJwts.admin;
import static com.trainbooking.trainschedule.testsupport.TestJwts.passenger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.trainschedule.coach.model.CoachClass;
import com.trainbooking.trainschedule.common.ApiError;
import com.trainbooking.trainschedule.common.GlobalExceptionHandler;
import com.trainbooking.trainschedule.common.RequestValidationException;
import com.trainbooking.trainschedule.fare.model.Fare;
import com.trainbooking.trainschedule.fare.model.FareRequest;
import com.trainbooking.trainschedule.fare.service.FareService;
import com.trainbooking.trainschedule.route.model.RouteNotFoundException;
import com.trainbooking.trainschedule.security.JsonErrorHandlers;
import com.trainbooking.trainschedule.security.SecurityConfig;
import java.math.BigDecimal;
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

@WebMvcTest(FareController.class)
@Import({SecurityConfig.class, JsonErrorHandlers.class, GlobalExceptionHandler.class})
class FareControllerTest {

    private static final String BODY = "[{\"segmentIndex\":0,\"class\":\"FIRST\",\"amount\":100.50},{\"segmentIndex\":0,\"class\":\"SECOND\",\"amount\":70}]";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FareService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void asPassenger_returns403() throws Exception {
        mvc.perform(get("/admin/routes/4/fares").with(passenger())).andExpect(status().isForbidden());
        mvc.perform(put("/admin/routes/4/fares").with(passenger()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_listUsesTheApiFieldNames_andEgpAmounts() throws Exception {
        given(service.list(4)).willReturn(List.of(new Fare(0, CoachClass.FIRST, new BigDecimal("100.50"), "EGP")));

        mvc.perform(get("/admin/routes/4/fares").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].segmentIndex").value(0))
                .andExpect(jsonPath("$[0].class").value("FIRST"))
                .andExpect(jsonPath("$[0].amount").value(100.50))
                .andExpect(jsonPath("$[0].currency").value("EGP"));
    }

    @Test
    void asAdmin_putTakesAJsonList_readingClassAndTheExactAmount() throws Exception {
        given(service.replaceAll(eq(4L), any(), eq(ADMIN_SUB))).willReturn(List.of(new Fare(0, CoachClass.FIRST, new BigDecimal("100.50"), "EGP")));

        mvc.perform(put("/admin/routes/4/fares").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FareRequest>> sent = ArgumentCaptor.forClass(List.class);
        verify(service).replaceAll(eq(4L), sent.capture(), eq(ADMIN_SUB));
        assertThat(sent.getValue()).hasSize(2);
        assertThat(sent.getValue().get(0).coachClass()).isEqualTo("FIRST");
        assertThat(sent.getValue().get(0).amount()).isEqualByComparingTo("100.50");
    }

    @Test
    void asAdmin_invalidFares_return400_withTheListPosition() throws Exception {
        given(service.replaceAll(eq(4L), any(), any())).willThrow(new RequestValidationException(
                List.of(new ApiError.Detail("[1].amount", "must be more than 0"))));

        mvc.perform(put("/admin/routes/4/fares").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details[0].field").value("[1].amount"));
    }

    @Test
    void asAdmin_bodyThatIsNotAList_returns400() throws Exception {
        mvc.perform(put("/admin/routes/4/fares").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
        verifyNoInteractions(service);
    }

    @Test
    void asAdmin_unknownRoute_returns404() throws Exception {
        given(service.list(99)).willThrow(new RouteNotFoundException(99));

        mvc.perform(get("/admin/routes/99/fares").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("route_not_found"));
    }
}
