package che.glucosemonitorbe.exception;

import java.time.LocalDateTime;

public class CustomErrorResponse {
    private int status;
    private String error;
    private String message;
    private String path;
    private LocalDateTime timestamp = LocalDateTime.now();

    public CustomErrorResponse(int status, String error, String message, String path) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    // геттеры/сеттеры
}