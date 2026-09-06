package com.recallbot.admin.controller;

import com.recallbot.admin.dto.AdminSystemHealthDto;
import com.recallbot.admin.service.AdminSystemService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/system")
public class AdminSystemController {

    private final AdminSystemService systemService;

    public AdminSystemController(AdminSystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminSystemHealthDto> getSystemHealth() {
        return ResponseEntity.ok(systemService.getSystemDiagnostics());
    }
}
