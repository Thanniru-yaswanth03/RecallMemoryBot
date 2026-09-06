package com.recallbot.admin.controller;

import com.recallbot.admin.dto.AdminGroupDetailDto;
import com.recallbot.admin.dto.AdminGroupDto;
import com.recallbot.admin.dto.AdminMemberDto;
import com.recallbot.admin.dto.PageResponse;
import com.recallbot.admin.service.AdminGroupService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/admin/groups")
public class AdminGroupController {

    private final AdminGroupService groupService;

    public AdminGroupController(AdminGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageResponse<AdminGroupDto>> getGroups(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active
    ) {
        return ResponseEntity.ok(groupService.getGroups(page, size, search, active));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getGroupDetails(@PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            return ResponseEntity.badRequest().body("Invalid group ID");
        }

        Optional<AdminGroupDetailDto> detailsOpt = groupService.getGroupDetails(id);
        return detailsOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageResponse<AdminMemberDto>> getGroupMembers(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (id == null || id <= 0) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(groupService.getGroupMembers(id, page, size));
    }
}
