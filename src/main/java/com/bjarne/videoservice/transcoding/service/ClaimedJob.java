package com.bjarne.videoservice.transcoding.service;

import com.bjarne.videoservice.transcoding.entity.JobType;

import java.util.UUID;

public record ClaimedJob(Long jobId, UUID videoId, JobType type) {
}
