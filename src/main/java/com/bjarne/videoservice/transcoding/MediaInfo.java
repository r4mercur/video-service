package com.bjarne.videoservice.transcoding;

public record MediaInfo(
        boolean hasVideoStream,
        boolean hasAudioStream,
        double durationSeconds,
        int width,
        int height,
        String videoCodec,
        String videoProfile,
        String audioCodec) {

    public boolean isAlreadyH264HighAndAac() {
        return "h264".equalsIgnoreCase(videoCodec) && "high".equalsIgnoreCase(videoProfile)
                && "aac".equalsIgnoreCase(audioCodec);
    }
}
