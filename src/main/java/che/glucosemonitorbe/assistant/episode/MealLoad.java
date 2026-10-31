package che.glucosemonitorbe.assistant.episode;

/** Insulin and carbs on board at the meal, excluding the meal's own notes. */
public record MealLoad(double iob, double cob) {}
