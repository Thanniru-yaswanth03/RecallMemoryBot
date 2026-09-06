package com.recallbot.admin.controller;

import com.recallbot.admin.dto.AdminMemoryDto;
import com.recallbot.admin.dto.PageResponse;
import com.recallbot.admin.service.AdminMemoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/memories")
public class AdminMemoryController {

    private final AdminMemoryService memoryService;

    public AdminMemoryController(AdminMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageResponse<AdminMemoryDto>> getMemories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(memoryService.getMemories(page, size, groupId, type, search));
    }
}
