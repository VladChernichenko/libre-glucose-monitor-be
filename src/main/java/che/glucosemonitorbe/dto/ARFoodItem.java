package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ARFoodItem {
    @NotBlank
    private String label;

    @Min(0)
    private double massG;

    private double volumeCm3;

    /** segmentedPointRatio from ARKit - fraction of scene points assigned to this food [0,1] */
    private double segmentationConfidence;
}
