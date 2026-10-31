package che.glucosemonitorbe.assistant.common;

import java.time.LocalDateTime;

/** A single CGM reading in mmol/L; {@code timestamp} is UTC wall time. */
public record CgmReadingDTO(double mmol, LocalDateTime timestamp, Integer trend, String direction) {}
