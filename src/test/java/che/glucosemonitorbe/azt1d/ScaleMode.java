package che.glucosemonitorbe.azt1d;

/** Which twin scales a scenario forecast runs with. */
enum ScaleMode {
    /** The raw model: every scale 1.0. */
    NEUTRAL,
    /** The twin fitted on data before the episode's day, applied only when its own check accepts it. */
    TWIN
}
