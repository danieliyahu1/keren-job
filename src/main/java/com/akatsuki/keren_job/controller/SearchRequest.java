package com.akatsuki.keren_job.controller;

import jakarta.validation.constraints.NotBlank;

record SearchRequest(@NotBlank(message = "Query must not be blank") String query) {}
