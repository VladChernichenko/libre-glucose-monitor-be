package che.glucosemonitorbe.dto;

/** Test helper: one insulin-to-carb ratio in all four meal windows, as a single ratio used to give. */
public final class TestWindowRatios {

    private TestWindowRatios() {
    }

    /** Sets {@code gramsPerUnit} (null = no ratio) as the ratio of every meal window; returns {@code s}. */
    public static UserSettingsDTO setAll(UserSettingsDTO s, Double gramsPerUnit) {
        s.setGramsPerUnitBreakfast(gramsPerUnit);
        s.setGramsPerUnitLunch(gramsPerUnit);
        s.setGramsPerUnitDinner(gramsPerUnit);
        s.setGramsPerUnitNight(gramsPerUnit);
        return s;
    }
}
