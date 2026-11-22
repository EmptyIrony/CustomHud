package dev.customhud.adapters;

import dev.customhud.data.Gear;

/**
 * ECARX gear sensor id and value mapping, same as CarLight's {@code EcarxCompat} (interface draft v1.1).
 * CarLight's gear sounds use this subscription on the target car. Unmatched values are null (unknown)
 * and are never downgraded to PARK.
 */
public final class EcarxGear {
    /** {@code getSensorEvent(int)} / {@code onSensorEventChanged} → Integer. */
    public static final int SENSOR_GEAR = 0x200200;

    public static final int GEAR_PARK = 0x200230;
    public static final int GEAR_REVERSE = 0x200240;
    public static final int GEAR_NEUTRAL = 0x200210;
    public static final int GEAR_DRIVE = 0x200220;
    /** Inclusive range treated as DRIVE; individual mechanical meaning not claimed. */
    public static final int GEAR_DRIVE_GROUP_FIRST = 0x200201;
    public static final int GEAR_DRIVE_GROUP_LAST = 0x20020A;

    private EcarxGear() {
    }

    public static Gear gearOf(Integer raw) {
        if (raw == null) {
            return null;
        }
        switch (raw) {
            case GEAR_PARK: return Gear.PARK;
            case GEAR_REVERSE: return Gear.REVERSE;
            case GEAR_NEUTRAL: return Gear.NEUTRAL;
            case GEAR_DRIVE: return Gear.DRIVE;
            default:
                return raw >= GEAR_DRIVE_GROUP_FIRST && raw <= GEAR_DRIVE_GROUP_LAST ? Gear.DRIVE : null;
        }
    }
}
