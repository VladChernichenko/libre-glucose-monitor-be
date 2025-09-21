package che.glucosemonitorbe.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogoutResponse {
    
    private boolean success;
    private String message;
    private long timestamp;
    
    public static LogoutResponse success(String message) {
        return LogoutResponse.builder()
                .success(true)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static LogoutResponse error(String message) {
        return LogoutResponse.builder()
                .success(false)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
