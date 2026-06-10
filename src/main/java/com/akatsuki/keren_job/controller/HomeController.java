package com.akatsuki.keren_job.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class HomeController {

    @GetMapping("/")
    String index() {
        return "index";
    }
}
