package com.recallbot.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminViewController {

    @GetMapping({"/admin", "/admin/"})
    public String adminIndex() {
        return "forward:/admin/index.html";
    }

    @GetMapping({"/admin/login", "/admin/login/"})
    public String adminLogin() {
        return "forward:/admin/login.html";
    }
}
