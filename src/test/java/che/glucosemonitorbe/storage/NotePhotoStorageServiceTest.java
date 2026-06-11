package che.glucosemonitorbe.storage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotePhotoStorageServiceTest {

    @Test
    void disabledByDefault_isEnabledIsFalse() {
        NotePhotoStorageService service = new NotePhotoStorageService(new S3StorageProperties());

        assertFalse(service.isEnabled());
    }

    @Test
    void disabled_uploadThrowsServiceUnavailable() {
        NotePhotoStorageService service = new NotePhotoStorageService(new S3StorageProperties());
        MultipartFile photo = new MockMultipartFile("photo", "meal.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), UUID.randomUUID(), photo))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void disabled_presignedUrlReturnsNull() {
        NotePhotoStorageService service = new NotePhotoStorageService(new S3StorageProperties());

        assertNull(service.presignedUrl("notes/some/key.jpg"));
    }

    @Test
    void enabled_emptyFile_throwsBadRequest() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());
        MultipartFile photo = new MockMultipartFile("photo", "meal.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), UUID.randomUUID(), photo))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void enabled_oversizedFile_throwsPayloadTooLarge() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());
        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        MultipartFile photo = new MockMultipartFile("photo", "meal.jpg", "image/jpeg", tooBig);

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), UUID.randomUUID(), photo))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void enabled_unsupportedContentType_throwsBadRequest() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());
        MultipartFile photo = new MockMultipartFile("photo", "meal.txt", "text/plain", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), UUID.randomUUID(), photo))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void enabled_presignedUrl_returnsUrlForKey() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());

        String url = service.presignedUrl("notes/user-1/note-1/photo.jpg");

        assertTrue(service.isEnabled());
        assertThat(url).isNotNull().contains("notes/user-1/note-1/photo.jpg");
    }

    @Test
    void enabled_presignedUrl_returnsNullForBlankKey() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());

        assertNull(service.presignedUrl(null));
        assertNull(service.presignedUrl(""));
    }

    private static S3StorageProperties enabledProperties() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setRegion("us-east-1");
        properties.setBucket("test-bucket");
        properties.setAccessKeyId("test-access-key");
        properties.setSecretAccessKey("test-secret-key");
        return properties;
    }
}
