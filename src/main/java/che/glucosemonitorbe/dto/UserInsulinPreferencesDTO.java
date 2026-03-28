package che.glucosemonitorbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInsulinPreferencesDTO {
    private String rapidInsulinCode;
    private String longActingInsulinCode;
    private InsulinCatalogDTO rapidInsulin;
    private InsulinCatalogDTO longActingInsulin;
}
