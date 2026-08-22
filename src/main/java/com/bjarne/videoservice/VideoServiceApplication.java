package com.bjarne.videoservice;

import com.bjarne.videoservice.config.S3Properties;
import com.bjarne.videoservice.config.TranscodeProperties;
import com.bjarne.videoservice.config.UploadProperties;
import com.bjarne.videoservice.identity.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, S3Properties.class, UploadProperties.class,
        TranscodeProperties.class})
public class VideoServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoServiceApplication.class, args);
    }

}
