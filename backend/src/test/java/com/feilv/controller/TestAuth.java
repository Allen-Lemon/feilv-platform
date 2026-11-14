package com.feilv.controller;

import com.feilv.domain.User;
import com.feilv.interceptor.AuthInterceptor;
import com.feilv.repository.UserRepository;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** standalone MVC 测试的显式账户夹具；生产拦截器始终检查数据库状态。 */
final class TestAuth {
    static AuthInterceptor interceptor() {
        var users = mock(UserRepository.class);
        when(users.findById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            User user = new User(); user.setId(id); user.setRole(id == 1 ? "ADMIN" : "USER");
            return Optional.of(user);
        });
        return new AuthInterceptor(users);
    }
}
