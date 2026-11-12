package com.feilv.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feilv.common.BizException;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatSessionClientTest {
    @SuppressWarnings("unchecked")
    private WechatSessionClient client(String body, int status) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertEquals("https", request.uri().getScheme());
            assertEquals("api.weixin.qq.com", request.uri().getHost());
            assertEquals("/sns/jscode2session", request.uri().getPath());
            assertTrue(request.uri().getQuery().contains("grant_type=authorization_code"));
            return response;
        });
        return new WechatSessionClient("test-app", "server-only-secret", new ObjectMapper(), http);
    }

    @Test
    void verifiedResponseDoesNotExposeSessionKey() throws Exception {
        var session = client("{\"openid\":\"verified\",\"unionid\":\"union\",\"session_key\":\"private\"}", 200).exchange("code");
        assertEquals("verified", session.openid());
        assertEquals("union", session.unionid());
        assertFalse(session.toString().contains("private"));
    }

    @Test
    void invalidUsedCodeAndMalformedResponseAreRejected() throws Exception {
        assertEquals(401, assertThrows(BizException.class, () -> client("{\"errcode\":40163}", 200).exchange("code")).getCode());
        assertThrows(BizException.class, () -> client("{}", 200).exchange("code"));
        assertThrows(BizException.class, () -> client("null", 200).exchange("code"));
        assertThrows(BizException.class, () -> client("", 200).exchange("code"));
        assertThrows(BizException.class, () -> client("not json", 200).exchange("code"));
        assertThrows(BizException.class, () -> client("", 503).exchange("code"));
    }
}
