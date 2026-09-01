package com.bjarne.videoservice.transcoding;

import java.util.UUID;

public record ClaimedJob(Long jobId, UUID videoId, JobType type) {
}
