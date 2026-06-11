package che.glucosemonitorbe.storage;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

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
    void disabled_downloadReturnsNull() {
        NotePhotoStorageService service = new NotePhotoStorageService(new S3StorageProperties());

        assertNull(service.download("notes/some/key.jpg"));
    }

    @Test
    void disabled_deleteIsNoOp() {
        NotePhotoStorageService service = new NotePhotoStorageService(new S3StorageProperties());

        service.delete("notes/some/key.jpg");
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
    void enabled_download_returnsNullForBlankKey() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());

        assertTrue(service.isEnabled());
        assertNull(service.download(null));
        assertNull(service.download(""));
    }

    @Test
    void enabled_delete_blankKeyIsNoOp() {
        NotePhotoStorageService service = new NotePhotoStorageService(enabledProperties());

        service.delete(null);
        service.delete("");
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
