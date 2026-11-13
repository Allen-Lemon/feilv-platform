package com.feilv.controller;

import com.feilv.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** 容器就绪检查：只检查数据库连接，不发送验证码，不暴露连接信息。 */
@RestController
@RequiredArgsConstructor
public class HealthController {
    private final DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<Result<Map<String, String>>> health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(Result.ok(Map.of("status", "UP")));
            }
        } catch (SQLException e) {
            // 健康检查失败通过 HTTP 状态体现，不在公开响应中暴露数据库地址、凭据或异常。
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(503, "服务暂未就绪"));
    }
}
