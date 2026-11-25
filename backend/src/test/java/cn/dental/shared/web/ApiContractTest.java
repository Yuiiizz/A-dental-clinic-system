package cn.dental.shared.web;
import cn.dental.shared.config.JsonConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    private MockMvc mvc; private JsonMapper mapper;
    @BeforeEach void setup(){
        var builder=JsonMapper.builder();new JsonConfig().strictJson().customize(builder);mapper=builder.build();
        mvc=MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
            .addFilters(new RequestTraceFilter()).build();
    }
    record Input(@NotBlank String name) {}
    record View(ApiId patient_id, OffsetDateTime starts_at) {}
    @RestController static class ProbeController {
        @PostMapping("/probe") ApiResponse<View> probe(@Valid @RequestBody Input input,HttpServletRequest request){
            return new ApiResponse<>(new View(new ApiId(Long.MAX_VALUE),OffsetDateTime.parse("2030-01-01T09:00:00+08:00")),RequestTraceFilter.requestId(request));
        }
        @GetMapping("/failure") Object failure(){throw new IllegalStateException("SELECT password_hash SECRET_CANARY");}
        @GetMapping("/conflict") Object conflict(){throw new ApiException(ApiErrorCode.VERSION_CONFLICT);}
        @GetMapping("/page") PageResult<String> page(@RequestParam(required=false) Integer limit){PageResult.validateLimit(limit);return new PageResult<>(List.of(),null);}
    }
    @Test void preservesStringIdsOffsetAndServerGeneratedTrace() throws Exception {
        var result=mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id","injected-secret").content("{\"name\":\"synthetic\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.patient_id").value("9223372036854775807")).andReturn();
        var tree=mapper.readTree(result.getResponse().getContentAsString());
        String id=tree.get("request_id").asText();assertDoesNotThrow(()->UUID.fromString(id));
        assertEquals(id,result.getResponse().getHeader("X-Request-Id"));
        assertEquals(OffsetDateTime.parse("2030-01-01T09:00:00+08:00").toInstant(),OffsetDateTime.parse(tree.get("data").get("starts_at").asText()).toInstant());
    }
    @Test void rejectsUnknownFieldsWithoutEchoingThem() throws Exception {
        var result=mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"synthetic\",\"SECRET_CANARY\":\"patient-private\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("SECRET_CANARY"));
    }
    @Test void rejectsMalformedAndInvalidInput() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    @Test void returnsSafeInternalErrorAndRestoresMdc() throws Exception {
        MDC.put("request_id","prior-context");
        try {
            var result=mvc.perform(get("/failure")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("SECRET_CANARY"));
            assertEquals("prior-context",MDC.get("request_id"));assertNull(MDC.get("http_status"));
        } finally {MDC.clear();}
    }
    @Test void businessConflictIsTypedAndUnchecked() throws Exception {
        mvc.perform(get("/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertInstanceOf(RuntimeException.class,new ApiException(ApiErrorCode.VERSION_CONFLICT));
    }
    @Test void enforcesPagingBounds() throws Exception {
        assertEquals(20,PageResult.validateLimit(null));
        mvc.perform(get("/page").param("limit","101")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/page").param("limit","0")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/page")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }
}
