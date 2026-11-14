package com.feilv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real JSON parsing and token lifecycle, with every outbound HTTP request mocked. */
class WechatPhoneClientTest {
    private static final String APP_ID = "wx-test-app";
    private static final String SECRET = "server-only-secret";
    private static final String CODE = "phone-code-\"&?#中文";
    private static final String TOKEN = "server-token+/=?&";
    private static final String FRESH_TOKEN = "refreshed-token";
    private static final String MOBILE = "13900001234";
    private static final String RAW_ERROR = "sensitive-wechat-detail";
    private static final String SUCCESS = """
            {"errcode":0,"errmsg":"ok","phone_info":{
              "phoneNumber":"+8613900001234","purePhoneNumber":"13900001234","countryCode":"86",
              "watermark":{"appid":"wx-test-app","timestamp":1730000000}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WechatConfigService.LoginCredentials credentials =
            new WechatConfigService.LoginCredentials(APP_ID, SECRET);
    private final List<HttpRequest> tokenRequests = new ArrayList<>();
    private final List<HttpRequest> phoneRequests = new ArrayList<>();
    private final Deque<Object> tokenReplies = new ArrayDeque<>();
    private final Deque<Object> phoneReplies = new ArrayDeque<>();
    private WechatPhoneClient client;

    @BeforeEach
    void setUp() throws Exception {
        var tokenHttp = mockHttp(tokenRequests, tokenReplies);
        var phoneHttp = mockHttp(phoneRequests, phoneReplies);
        client = new WechatPhoneClient(new WechatAccessTokenProvider(mapper, tokenHttp), mapper, phoneHttp);
        tokenReplies.add(response(200, tokenBody(TOKEN)));
    }

    @Test
    void postsOnlyCodeToFixedHttpsEndpointAndUsesVerifiedPureNumber() throws Exception {
        phoneReplies.add(response(200, SUCCESS));
        var phone = client.exchange(CODE, credentials);
        assertEquals(APP_ID, phone.appId());
        assertEquals(MOBILE, phone.mobile());
        assertEquals("Phone[redacted]", phone.toString());
        assertEquals(1, tokenRequests.size());
        assertTokenRequest(tokenRequests.get(0), false);
        assertEquals(1, phoneRequests.size());
        assertPhoneRequest(phoneRequests.get(0),
                "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=server-token%2B%2F%3D%3F%26");
    }

    @Test
    void reusesCachedTokenAcrossExchanges() {
        phoneReplies.add(response(200, SUCCESS));
        phoneReplies.add(response(200, SUCCESS));
        client.exchange(CODE, credentials);
        client.exchange(CODE, credentials);
        assertEquals(1, tokenRequests.size());
        assertEquals(2, phoneRequests.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {40029, 40163, 41008, 41024})
    void expiredOrUsedAuthorizationIsBusiness400WithoutRefreshingToken(int error) throws Exception {
        assertPhoneError(error, 400, "手机号授权凭证无效或已失效，请重新授权");
    }

    @Test
    void missingPermissionGivesAdministratorActionWithoutWechatDetails() throws Exception {
        assertPhoneError(48001, 503, "微信手机号服务权限未开通，请联系管理员");
    }

    @ParameterizedTest
    @ValueSource(ints = {45009, 45011})
    void quotaAndRateLimitsGiveRetryAdviceWithoutWechatDetails(int error) throws Exception {
        assertPhoneError(error, 429, "微信手机号服务调用额度已达上限或请求过于频繁，请稍后重试");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 123456, 40125})
    void unknownWechatErrorsRemainBusiness502(int error) throws Exception {
        assertPhoneError(error, 502, "微信手机号服务暂不可用，请重新授权或稍后重试");
    }

    @ParameterizedTest
    @ValueSource(ints = {40001, 40014, 42001})
    void rejectedTokenRefreshesOnceAndRetriesSameCodeWithNewToken(int error) throws Exception {
        phoneReplies.add(response(200, errorBody(error)));
        phoneReplies.add(response(200, SUCCESS));
        tokenReplies.add(response(200, tokenBody(FRESH_TOKEN)));
        assertEquals(MOBILE, client.exchange(CODE, credentials).mobile());
        assertEquals(2, tokenRequests.size());
        assertTokenRequest(tokenRequests.get(0), false);
        assertTokenRequest(tokenRequests.get(1), true);
        assertEquals(2, phoneRequests.size());
        assertPhoneRequest(phoneRequests.get(0),
                "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=server-token%2B%2F%3D%3F%26");
        assertPhoneRequest(phoneRequests.get(1),
                "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=refreshed-token");
    }

    @ParameterizedTest
    @ValueSource(ints = {40001, 40014, 42001})
    void secondTokenRejectionStopsRetryAndInvalidatesRejectedToken(int error) throws Exception {
        phoneReplies.add(response(200, errorBody(error)));
        phoneReplies.add(response(200, errorBody(error)));
        tokenReplies.add(response(200, tokenBody(FRESH_TOKEN)));
        assertSafeFailure(502);
        assertEquals(2, tokenRequests.size());
        assertEquals(2, phoneRequests.size());
        assertTokenRequest(tokenRequests.get(1), true);

        tokenReplies.add(response(200, tokenBody("next-token")));
        phoneReplies.add(response(200, SUCCESS));
        assertEquals(MOBILE, client.exchange(CODE, credentials).mobile());
        assertEquals(3, tokenRequests.size());
        assertEquals(3, phoneRequests.size());
        assertTokenRequest(tokenRequests.get(2), false);
        assertPhoneRequest(phoneRequests.get(2),
                "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=next-token");
    }

    @Test
    void refreshedAttemptStillMapsAuthorizationErrorsWithoutAnotherRefresh() throws Exception {
        phoneReplies.add(response(200, errorBody(42001)));
        phoneReplies.add(response(200, errorBody(40163)));
        tokenReplies.add(response(200, tokenBody(FRESH_TOKEN)));
        assertSafeFailure(400);
        assertEquals(2, tokenRequests.size());
        assertEquals(2, phoneRequests.size());
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsMalformedJsonAndUnverifiedPhoneDataWithoutDisclosure(String body) {
        phoneReplies.add(response(200, body));
        assertSafeFailure(502);
        assertEquals(1, tokenRequests.size());
        assertEquals(1, phoneRequests.size());
    }

    static Stream<String> invalidResponses() {
        return Stream.of(
                "", " ", "null", "[]", "not-json", "{", "{}", SUCCESS + " {}",
                SUCCESS.replace("\"errcode\":0", "\"errcode\":0,\"errcode\":48001"),
                SUCCESS.replace("\"errcode\":0", "\"errcode\":\"0\""),
                SUCCESS.replace("\"errcode\":0", "\"errcode\":0.0"),
                SUCCESS.replace("\"errcode\":0", "\"errcode\":2147483648"),
                SUCCESS.replace("\"errcode\":0,", ""),
                SUCCESS.replace("\"wx-test-app\"", "\"another-app\""),
                SUCCESS.replace("\"appid\":\"wx-test-app\"", "\"appid\":null"),
                SUCCESS.replace("\"watermark\"", "\"ignored_watermark\""),
                SUCCESS.replace("\"countryCode\":\"86\"", "\"countryCode\":\"1\""),
                SUCCESS.replace("\"countryCode\":\"86\"", "\"countryCode\":86"),
                SUCCESS.replace("\"countryCode\"", "\"ignored_countryCode\""),
                SUCCESS.replace("\"purePhoneNumber\":\"13900001234\"", "\"purePhoneNumber\":13900001234"),
                SUCCESS.replace("\"purePhoneNumber\":\"13900001234\"", "\"purePhoneNumber\":\"+8613900001234\""),
                SUCCESS.replace("\"purePhoneNumber\":\"13900001234\"", "\"purePhoneNumber\":\"12900001234\""),
                SUCCESS.replace("\"purePhoneNumber\":\"13900001234\"", "\"purePhoneNumber\":\"1390000123\""),
                SUCCESS.replace("\"purePhoneNumber\"", "\"ignored_purePhoneNumber\""),
                "{\"errcode\":0,\"phone_info\":null}", "{\"errcode\":0,\"phone_info\":[]}",
                " ".repeat(65_537), null);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 429, 500, 503})
    void non200HttpStatusDoesNotTrustResponseBodyOrFollowRedirects(int status) {
        phoneReplies.add(response(status, errorBody(48001)));
        assertSafeFailure(502);
        assertEquals(1, phoneRequests.size());
    }

    @Test
    void defaultTransportDisablesRedirectsAndBoundsConnectionTime() {
        var http = WechatAccessTokenProvider.defaultHttpClient();
        assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
        assertEquals(Duration.ofSeconds(5), http.connectTimeout().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "too-long"})
    void invalidCodeIsRejectedBeforeAnyTransportRequest(String value) {
        String code = "too-long".equals(value) ? "x".repeat(257) : value;
        assertEquals(400, assertThrows(BizException.class, () -> client.exchange(code, credentials)).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> client.exchange(null, credentials)).getCode());
        assertTrue(tokenRequests.isEmpty());
        assertTrue(phoneRequests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "io", "runtime"})
    void phoneTransportFailuresAreSanitizedAndNotRetried(String kind) {
        phoneReplies.add(transportFailure(kind));
        assertSafeFailure(502);
        assertEquals(1, tokenRequests.size());
        assertEquals(1, phoneRequests.size());
    }

    @Test
    void phoneInterruptionRestoresInterruptFlagWithoutLeakingCause() {
        assertFalse(Thread.currentThread().isInterrupted());
        phoneReplies.add(new InterruptedException(sensitiveDetails()));
        try {
            var failure = assertSafeFailure(502);
            assertEquals("微信手机号请求中断，请重试", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, phoneRequests.size());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "io", "runtime"})
    void initialTokenTransportFailurePreventsPhoneRequest(String kind) {
        tokenReplies.clear();
        tokenReplies.add(transportFailure(kind));
        assertSafeFailure(502);
        assertEquals(1, tokenRequests.size());
        assertTrue(phoneRequests.isEmpty());
    }

    @Test
    void tokenInterruptionAlsoPreservesInterruptFlag() {
        tokenReplies.clear();
        tokenReplies.add(new InterruptedException(sensitiveDetails()));
        try {
            assertSafeFailure(502);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(phoneRequests.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedTokenRefreshDoesNotRetryPhoneOrLeakTokenResponse() throws Exception {
        phoneReplies.add(response(200, errorBody(42001)));
        tokenReplies.add(response(200, errorBody(40013)));
        assertSafeFailure(502);
        assertEquals(2, tokenRequests.size());
        assertTokenRequest(tokenRequests.get(1), true);
        assertEquals(1, phoneRequests.size());
    }

    private void assertPhoneError(int error, int businessCode, String message) throws Exception {
        phoneReplies.add(response(200, errorBody(error)));
        assertEquals(message, assertSafeFailure(businessCode).getMessage());
        assertEquals(1, tokenRequests.size());
        assertEquals(1, phoneRequests.size());
        // Business errors must not evict an otherwise valid token.
        phoneReplies.add(response(200, SUCCESS));
        assertEquals(MOBILE, client.exchange(CODE, credentials).mobile());
        assertEquals(1, tokenRequests.size());
    }

    private BizException assertSafeFailure(int businessCode) {
        var error = assertThrows(BizException.class, () -> client.exchange(CODE, credentials));
        assertEquals(businessCode, error.getCode());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        var trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        for (String sensitive : List.of(APP_ID, SECRET, CODE, TOKEN, FRESH_TOKEN, MOBILE, RAW_ERROR)) {
            assertFalse(trace.toString().contains(sensitive), "Exception must not expose upstream data");
        }
        return error;
    }

    private void assertTokenRequest(HttpRequest request, boolean forceRefresh) throws Exception {
        assertEquals("https://api.weixin.qq.com/cgi-bin/stable_token", request.uri().toString());
        assertJsonPost(request);
        assertEquals(mapper.valueToTree(Map.of("grant_type", "client_credential", "appid", APP_ID,
                "secret", SECRET, "force_refresh", forceRefresh)), requestBody(request));
    }

    private void assertPhoneRequest(HttpRequest request, String expectedUri) throws Exception {
        assertEquals(expectedUri, request.uri().toString());
        assertJsonPost(request);
        assertEquals(mapper.valueToTree(Map.of("code", CODE)), requestBody(request));
        assertFalse(request.uri().toString().contains(CODE));
        assertFalse(request.uri().toString().contains(SECRET));
    }

    private void assertJsonPost(HttpRequest request) {
        assertEquals("POST", request.method());
        assertEquals(Duration.ofSeconds(10), request.timeout().orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
    }

    private JsonNode requestBody(HttpRequest request) throws Exception {
        var result = new CompletableFuture<byte[]>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return mapper.readTree(new String(result.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private HttpClient mockHttp(List<HttpRequest> requests, Deque<Object> replies) throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            assertFalse(replies.isEmpty(), "Unexpected outbound HTTP request");
            Object reply = replies.removeFirst();
            if (reply instanceof Exception failure) throw failure;
            return reply;
        });
        return http;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String tokenBody(String token) throws Exception {
        return mapper.writeValueAsString(Map.of("access_token", token, "expires_in", 7200));
    }

    private String errorBody(int error) {
        try {
            return mapper.writeValueAsString(Map.of("errcode", error, "errmsg", sensitiveDetails()));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Exception transportFailure(String kind) {
        return switch (kind) {
            case "timeout" -> new HttpTimeoutException(sensitiveDetails());
            case "io" -> new IOException(sensitiveDetails());
            default -> new IllegalArgumentException(sensitiveDetails());
        };
    }

    private String sensitiveDetails() {
        return String.join(" ", APP_ID, SECRET, CODE, TOKEN, FRESH_TOKEN, MOBILE, RAW_ERROR);
    }
}
