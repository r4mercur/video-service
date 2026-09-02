package com.bjarne.videoservice;

import com.bjarne.videoservice.config.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, S3Properties.class, UploadProperties.class,
        TranscodeProperties.class, DeliveryProperties.class, RateLimitProperties.class, ViewCountProperties.class,
        ApiProperties.class, ThumbnailProperties.class, ActuatorSecurityProperties.class})
public class VideoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoServiceApplication.class, args);
    }

}
