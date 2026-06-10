package com.akatsuki.keren_job.dto;

public record JobResult(
    String title,
    String company,
    String location,
    String descriptionSnippet,
    String link
) {}
