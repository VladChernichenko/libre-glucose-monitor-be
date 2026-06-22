package che.glucosemonitorbe.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Stores meal-photo uploads in an S3-compatible object store (MinIO) and generates
 * time-limited GET URLs for displaying them.
 *
 * <p>Object keys are always generated server-side as {@code notes/{userId}/{noteId}/{uuid}.{ext}} —
 * user-supplied filenames are never used in the key, preventing path traversal.
 */
@Slf4j
@Service
public class NotePhotoStorageService {

    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024; // 10 MB

    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/heic", "heic",
            "image/webp", "webp"
    );

    /** A downloaded photo's bytes and the content type to serve them with. */
    public record PhotoObject(byte[] data, String contentType) {}

    private final S3StorageProperties properties;
    private final S3Client s3Client;

    public NotePhotoStorageService(S3StorageProperties properties) {
        this.properties = properties;
        if (properties.isEnabled()) {
            StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey()));
            S3Configuration pathStyleConfig = S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build();
            URI endpoint = URI.create(properties.getEndpoint());
            Region region = Region.of(properties.getRegion());

            this.s3Client = S3Client.builder()
                    .endpointOverride(endpoint)
                    .region(region)
                    .credentialsProvider(credentialsProvider)
                    .serviceConfiguration(pathStyleConfig)
                    .build();
        } else {
            this.s3Client = null;
        }
    }

    /** True when MinIO/S3 endpoint, bucket and credentials are all configured. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Validates and uploads {@code file}, returning the object key to persist on the note.
     *
     * @throws ResponseStatusException 503 if storage is not configured, 400/413 if the
     *                                  photo is missing, oversized, or an unsupported type
     */
    public String upload(UUID userId, UUID noteId, MultipartFile file) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured");
        }
        String extension = validateAndExtensionFor(file);
        String key = "notes/%s/%s/%s.%s".formatted(userId, noteId, UUID.randomUUID(), extension);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read photo upload", e);
        }

        log.info("Uploaded note photo userId={} noteId={} key={} size={}", userId, noteId, key, file.getSize());
        return key;
    }

    /**
     * Downloads the photo stored at {@code key}, or returns {@code null} if storage is
     * disabled, the note has no photo, or the object no longer exists in the bucket.
     */
    public PhotoObject download(String key) {
        if (!isEnabled() || key == null || key.isBlank()) {
            return null;
        }
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                GetObjectRequest.builder().bucket(properties.getBucket()).key(key).build())) {
            return new PhotoObject(response.readAllBytes(), response.response().contentType());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read photo from storage", e);
        }
    }

    /**
     * Deletes the photo stored at {@code key}, if any. Failures are logged and swallowed —
     * photo cleanup must never prevent a note from being deleted.
     */
    public void delete(String key) {
        if (!isEnabled() || key == null || key.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
            log.info("Deleted note photo key={}", key);
        } catch (Exception e) {
            log.warn("Failed to delete note photo key={}", key, e);
        }
    }

    private String validateAndExtensionFor(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photo is empty");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo exceeds maximum size of 10MB");
        }
        String extension = ALLOWED_CONTENT_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported photo content type: " + file.getContentType());
        }
        return extension;
    }
}
