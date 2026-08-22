package com.bjarne.videoservice.catalog;

import com.bjarne.videoservice.identity.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityPolicyTest {

    @Test
    void publicReadyVideoIsVisibleToAnyone() {
        Video video = video(VideoStatus.READY, Visibility.PUBLIC, UUID.randomUUID());

        assertThat(VisibilityPolicy.isVisibleTo(video, null)).isTrue();
        assertThat(VisibilityPolicy.isVisibleTo(video, UUID.randomUUID())).isTrue();
    }

    @Test
    void privateVideoIsOnlyVisibleToOwner() {
        UUID ownerId = UUID.randomUUID();
        Video video = video(VideoStatus.READY, Visibility.PRIVATE, ownerId);

        assertThat(VisibilityPolicy.isVisibleTo(video, ownerId)).isTrue();
        assertThat(VisibilityPolicy.isVisibleTo(video, UUID.randomUUID())).isFalse();
        assertThat(VisibilityPolicy.isVisibleTo(video, null)).isFalse();
    }

    @Test
    void nonReadyPublicVideoIsOnlyVisibleToOwner() {
        UUID ownerId = UUID.randomUUID();
        Video video = video(VideoStatus.PROCESSING, Visibility.PUBLIC, ownerId);

        assertThat(VisibilityPolicy.isVisibleTo(video, ownerId)).isTrue();
        assertThat(VisibilityPolicy.isVisibleTo(video, UUID.randomUUID())).isFalse();
    }

    private Video video(VideoStatus status, Visibility visibility, UUID ownerId) {
        User user = new User("owner@example.com", "owner", "hash");
        setId(user, ownerId);
        Video video = new Video(user, null, "Title", "title-slug", visibility);
        video.setStatus(status);
        return video;
    }

    private void setId(User user, UUID id) {
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
