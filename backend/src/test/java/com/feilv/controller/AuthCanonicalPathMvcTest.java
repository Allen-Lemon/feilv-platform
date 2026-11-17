package com.feilv.controller;

import com.feilv.common.GlobalExceptionHandler;
import com.feilv.common.UserContext;
import com.feilv.config.WebConfig;
import com.feilv.domain.Rider;
import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.RiderRepository;
import com.feilv.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC path matching + production WebConfig/interceptor, no Spring Boot, database or network. */
class AuthCanonicalPathMvcTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ProbeController probe;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("auth-canonical-path-probe");
        context.register(MvcConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).dispatchOptions(true).build();
        probe = context.getBean(ProbeController.class);
    }

    @AfterEach void close() {
        UserContext.clear();
        if (context != null) context.close();
    }

    @Test void anonymousSemicolonApiPrefixCannotReachAdminHandler() throws Exception {
        mvc.perform(raw(HttpMethod.GET, "/api;x=1/v1/admin/stats", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, probe.invocations.get());
    }

    @Test void consumerSourceRiderCannotReachRiderHandlersThroughMatrixParameters() throws Exception {
        String token = "Bearer " + TestAuth.userToken(42L, "RIDER");
        for (String path : new String[]{"/api/v1/rider;x=1/online", "/api/v1/grab;x=1/123"}) {
            mvc.perform(raw(HttpMethod.POST, path, token))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertEquals(0, probe.invocations.get());
    }

    @Test void encodedPrefixesAndEncodedRiderSegmentsAreNotAcceptedAsCanonicalPaths() throws Exception {
        mvc.perform(raw(HttpMethod.GET, "/%61pi/v1/admin/stats", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(raw(HttpMethod.POST, "/api/v1/%72ider/online", "Bearer " + TestAuth.userToken(42L, "RIDER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, probe.invocations.get());
    }

    @Test void malformedOptionsAndPublicPathsAreRejectedBeforeAnyBypass() throws Exception {
        for (String path : new String[]{"/api;x=1/v1/admin/stats", "/%61pi/v1/admin/stats", "/api/v1/rider;x=1/online"}) {
            mvc.perform(raw(HttpMethod.OPTIONS, path, null))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(raw(HttpMethod.GET, "/api/v1/auth/miniapps/USER/options;x=1", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(raw(HttpMethod.POST, "/api/v1/auth/wechat-login;x=1", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertEquals(0, probe.invocations.get());
    }

    @Test void canonicalPathsKeepTheExistingAudienceMatrix() throws Exception {
        String user = "Bearer " + TestAuth.userToken(42L, "RIDER");
        String rider = "Bearer " + TestAuth.riderToken(42L, "RIDER");
        String admin = "Bearer " + TestAuth.adminToken(1L);
        mvc.perform(raw(HttpMethod.GET, "/api/v1/admin/stats", null)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(raw(HttpMethod.GET, "/api/v1/admin/stats", admin)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.POST, "/api/v1/rider/online", user)).andExpect(status().isForbidden());
        mvc.perform(raw(HttpMethod.POST, "/api/v1/grab/123", user)).andExpect(status().isForbidden());
        mvc.perform(raw(HttpMethod.POST, "/api/v1/rider/online", rider)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.POST, "/api/v1/grab/123", rider)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.GET, "/api/v1/orders/123", user)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.GET, "/api/v1/orders/123", rider)).andExpect(status().isForbidden());
        mvc.perform(raw(HttpMethod.GET, "/api/v1/wallet", user)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.GET, "/api/v1/wallet", rider)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.GET, "/api/v1/auth/miniapps/USER/options", null)).andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.OPTIONS, "/api/v1/rider/online", null)).andExpect(status().isOk());
        assertNull(UserContext.get());
    }

    @Test void contextPathIsRemovedWithoutChangingCanonicalAuthorization() throws Exception {
        String rider = "Bearer " + TestAuth.riderToken(42L, "RIDER");
        mvc.perform(raw(HttpMethod.POST, "/platform/api/v1/rider/online", rider).contextPath("/platform"))
                .andExpect(status().isOk());
        mvc.perform(raw(HttpMethod.POST, "/platform/api/v1/rider/online", "Bearer " + TestAuth.userToken(42L, "RIDER"))
                        .contextPath("/platform"))
                .andExpect(status().isForbidden());
        mvc.perform(raw(HttpMethod.GET, "/platform/api/v1/admin/stats", null).contextPath("/platform"))
                .andExpect(status().isUnauthorized());
        mvc.perform(raw(HttpMethod.GET, "/platform/api;x=1/v1/admin/stats", null).contextPath("/platform"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        assertNull(UserContext.get());
    }

    private static MockHttpServletRequestBuilder raw(HttpMethod method, String path, String authorization) {
        // URI overload retains raw escapes rather than having the template overload double-encode them.
        var builder = request(method, URI.create(path));
        if (authorization != null) builder.header("Authorization", authorization);
        return builder;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(WebConfig.class)
    static class MvcConfiguration {
        @Bean AuthInterceptor authInterceptor() {
            var users = mock(UserRepository.class);
            User riderUser = new User(); riderUser.setId(42L); riderUser.setRole("RIDER");
            User admin = new User(); admin.setId(1L); admin.setRole("ADMIN");
            when(users.findById(42L)).thenReturn(Optional.of(riderUser));
            when(users.findById(1L)).thenReturn(Optional.of(admin));
            var riders = mock(RiderRepository.class);
            Rider rider = new Rider(); rider.setId(7L); rider.setUserId(42L); rider.setName("path test");
            when(riders.findByUserId(42L)).thenReturn(Optional.of(rider));
            return new AuthInterceptor(TestAuth.tokens(), users, riders);
        }
        @Bean ProbeController probeController() { return new ProbeController(); }
        @Bean GlobalExceptionHandler exceptionHandler() { return new GlobalExceptionHandler(); }
    }

    /** Route probes use MVC's real matcher; authentication is exclusively the production interceptor. */
    @RestController
    @Profile("auth-canonical-path-probe")
    static class ProbeController {
        final AtomicInteger invocations = new AtomicInteger();
        @GetMapping("/api/v1/admin/stats") String stats() { return invoke(); }
        @PostMapping("/api/v1/rider/online") String online() { return invoke(); }
        @PostMapping("/api/v1/grab/{id}") String grab(@PathVariable String id) { return invoke(); }
        @GetMapping("/api/v1/orders/{id}") String order(@PathVariable String id) { return invoke(); }
        @GetMapping("/api/v1/wallet") String wallet() { return invoke(); }
        @GetMapping("/api/v1/auth/miniapps/USER/options") String options() { return invoke(); }
        @PostMapping("/api/v1/auth/wechat-login") String login() { return invoke(); }
        private String invoke() { invocations.incrementAndGet(); return "handler reached"; }
    }
}
