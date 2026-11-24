package dev.veqzo.aimtune.core.accel

/**
 * A curve-shape field of a Raw Accel `accel_args` block: the only fields "Refine my Raw Accel curve" may change. [key] is the
 * property name in `settings.json` (`wrapper/wrapper.cpp`), [subKey] the member of the `Cap / Jump` object. Everything else of a
 * profile is the base (game sensitivity, output and device DPI, ratios, rotation, snap, speed cap, stretches, speed and smoothing
 * arguments, device config, the multiplier at very low speed) and stays as imported, in every round and in the export.
 */
enum class ShapeField(val key: String, val subKey: String? = null) {
    ACCELERATION("acceleration"),
    INPUT_OFFSET("inputOffset"),
    DECAY_RATE("decayRate"),
    LIMIT("limit"),
    CAP_X("Cap / Jump", "x"),
    CAP_Y("Cap / Jump", "y"),
    SYNC_SPEED("syncSpeed"),
    DATA("data"),
    ;

    /** The name under which a change of this field is shown. */
    val label: String get() = if (subKey == null) key else "$key $subKey"

    fun read(args: RawAccelArgs): Any = when (this) {
        ACCELERATION -> args.acceleration
        INPUT_OFFSET -> args.inputOffset
        DECAY_RATE -> args.decayRate
        LIMIT -> args.limit
        CAP_X -> args.capX
        CAP_Y -> args.capY
        SYNC_SPEED -> args.syncSpeed
        DATA -> args.lut
    }
}

/** The curve-shape whitelist, in one place: what each stage may change per mode. Used by `adjusted` and by the export patch. */
object RefineShape {
    /** The fields [parameter] may change in an `accel_args` block of [mode]; empty when the mode has no such stage. */
    fun fields(mode: RawAccelMode, parameter: RefineParameter): Set<ShapeField> = when (mode) {
        RawAccelMode.CLASSIC -> when (parameter) {
            RefineParameter.HIGH_GAIN -> setOf(ShapeField.CAP_Y, ShapeField.ACCELERATION)
            RefineParameter.TRANSITION -> setOf(ShapeField.INPUT_OFFSET, ShapeField.CAP_X)
        }
        RawAccelMode.NATURAL -> when (parameter) {
            RefineParameter.HIGH_GAIN -> setOf(ShapeField.LIMIT, ShapeField.DECAY_RATE)
            RefineParameter.TRANSITION -> setOf(ShapeField.DECAY_RATE)
        }
        RawAccelMode.LUT -> setOf(ShapeField.DATA)
        RawAccelMode.JUMP -> when (parameter) {
            RefineParameter.HIGH_GAIN -> setOf(ShapeField.CAP_Y)
            RefineParameter.TRANSITION -> setOf(ShapeField.CAP_X)
        }
        RawAccelMode.SYNCHRONOUS -> when (parameter) {
            RefineParameter.HIGH_GAIN -> emptySet()
            RefineParameter.TRANSITION -> setOf(ShapeField.SYNC_SPEED)
        }
        RawAccelMode.POWER, RawAccelMode.NOACCEL -> emptySet()
    }

    /** Every field any stage may change in [mode]. */
    fun fields(mode: RawAccelMode): Set<ShapeField> = RefineParameter.entries.flatMapTo(LinkedHashSet()) { fields(mode, it) }

    /** [args] with the [fields] set to neutral placeholders (one `copy`, so the cross-field checks of `RawAccelArgs` see the final values). */
    fun blanked(args: RawAccelArgs, fields: Set<ShapeField>): RawAccelArgs = args.copy(
        acceleration = if (ShapeField.ACCELERATION in fields) 1.0 else args.acceleration,
        inputOffset = if (ShapeField.INPUT_OFFSET in fields) 0.0 else args.inputOffset,
        decayRate = if (ShapeField.DECAY_RATE in fields) 1.0 else args.decayRate,
        limit = if (ShapeField.LIMIT in fields) 1.0 else args.limit,
        capX = if (ShapeField.CAP_X in fields) 1.0 else args.capX,
        capY = if (ShapeField.CAP_Y in fields) 1.0 else args.capY,
        syncSpeed = if (ShapeField.SYNC_SPEED in fields) 1.0 else args.syncSpeed,
        lut = if (ShapeField.DATA in fields) List(args.lut.size) { 0f } else args.lut,
    )

    /**
     * [profile] with every field a stage may change blanked: the base. Two profiles with equal bases differ in curve shape only.
     * The vertical block is part of the base unless the profile is by component (the driver ignores it otherwise).
     */
    fun base(profile: RawAccelProfile): RawAccelProfile = profile.copy(
        args = blanked(profile.args, fields(profile.args.mode)),
        accelY = if (profile.speed.whole) profile.accelY else blanked(profile.accelY, fields(profile.accelY.mode)),
    )
}
