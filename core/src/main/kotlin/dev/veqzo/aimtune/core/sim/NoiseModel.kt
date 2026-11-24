package dev.veqzo.aimtune.core.sim

/**
 * How the endpoint noise of a simulated reach relates to its hand speed. The default couples them, as an impulse that is
 * too strong is both faster and longer; [DECOUPLED] is the other extreme, so a harness can check that an analysis does not
 * depend on which of the two a real hand is closer to (the removed docs/OPTIMIZER-REVIEW.md (git history), E1).
 */
enum class NoiseModel {
    /** The noise is added to the planned distance in the planned time: a reach that goes further is also faster. */
    PLANNED,

    /** The duration grows with the noisy distance, so the peak speed stays the planned one. */
    DECOUPLED,
}
