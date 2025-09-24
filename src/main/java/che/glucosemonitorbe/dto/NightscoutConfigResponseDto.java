package che.glucosemonitorbe.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NightscoutConfigResponseDto {
    
    private UUID id;
    private String nightscoutUrl;
    private String apiSecret; // Masked for security
    private String apiToken; // Masked for security
    private Boolean isActive;
    private LocalDateTime lastUsed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Helper method to mask sensitive data
    public static NightscoutConfigResponseDto maskSensitiveData(NightscoutConfigResponseDto dto) {
        if (dto == null) return null;
        
        return NightscoutConfigResponseDto.builder()
                .id(dto.getId())
                .nightscoutUrl(dto.getNightscoutUrl())
                .apiSecret(maskString(dto.getApiSecret()))
                .apiToken(maskString(dto.getApiToken()))
                .isActive(dto.getIsActive())
                .lastUsed(dto.getLastUsed())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
    
    private static String maskString(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }
}

