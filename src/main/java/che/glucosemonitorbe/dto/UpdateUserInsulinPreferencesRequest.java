package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateUserInsulinPreferencesRequest {

    @NotBlank
    private String rapidInsulinCode;

    @NotBlank
    private String longActingInsulinCode;

    /**
     * Optional daily long-acting injection time as "HH:mm". When null the existing value is left
     * unchanged; an empty string clears it. Used by the client to gate the logging action.
     */
    private String longActingInjectionTime;
}
