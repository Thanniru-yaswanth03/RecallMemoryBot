package com.recallbot.admin.controller;

import com.recallbot.admin.activity.AdminActivityBuffer;
import com.recallbot.admin.activity.BotActivityEvent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/activity")
public class AdminActivityController {

    private final AdminActivityBuffer activityBuffer;

    public AdminActivityController(AdminActivityBuffer activityBuffer) {
        this.activityBuffer = activityBuffer;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BotActivityEvent>> getRecentActivity(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long groupId
    ) {
        return ResponseEntity.ok(activityBuffer.getRecentEvents(limit, type, groupId));
    }
}
