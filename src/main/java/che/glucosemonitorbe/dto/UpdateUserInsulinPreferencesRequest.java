package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateUserInsulinPreferencesRequest {

    @NotBlank
    private String rapidInsulinCode;

    @NotBlank
    private String longActingInsulinCode;
}
