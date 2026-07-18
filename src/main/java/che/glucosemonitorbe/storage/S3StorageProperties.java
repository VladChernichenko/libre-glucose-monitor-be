package che.glucosemonitorbe.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the S3-compatible (MinIO) object store used for note meal photos.
 *
 * <p>Bound from {@code app.storage.s3.*}, sourced from the {@code ENDPOINT}, {@code REGION},
 * {@code BUCKET}, {@code ACCESS_KEY_ID} and {@code SECRET_ACCESS_KEY} environment variables
 * (the names Railway injects for a referenced MinIO bucket). Photo storage is considered
 * {@link #isEnabled() enabled} only when endpoint, bucket and credentials are all present -
 * otherwise upload is refused and notes simply have no {@code photoUrl}.
 */
@Component
@ConfigurationProperties(prefix = "app.storage.s3")
public class S3StorageProperties {

    private String endpoint;
    private String region = "us-east-1";
    private String bucket;
    private String accessKeyId;
    private String secretAccessKey;

    public boolean isEnabled() {
        return notBlank(endpoint) && notBlank(bucket) && notBlank(accessKeyId) && notBlank(secretAccessKey);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }
}
