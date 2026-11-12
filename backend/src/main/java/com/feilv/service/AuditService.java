package com.feilv.service;

import com.feilv.common.UserContext;
import com.feilv.domain.OpLog;
import com.feilv.repository.OpLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final OpLogRepository repository;

    public void record(String action, String detail) {
        OpLog log = new OpLog();
        var current = UserContext.get();
        log.setOperatorId(current == null ? null : current.id());
        log.setAction(action);
        log.setDetail(detail == null ? "" : detail);
        repository.save(log);
    }
}
