package com.akatsuki.keren_job.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    ModelAndView handleException(Exception e) {
        log.error("Unhandled exception", e);
        ModelAndView mav = new ModelAndView("fragments/error :: error");
        mav.addObject("message", "Something went wrong. Please try again.");
        return mav;
    }
}
