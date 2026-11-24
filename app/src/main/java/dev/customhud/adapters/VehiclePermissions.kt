package dev.customhud.adapters

/**
 * Car permissions declared in the manifest. Vehicle data now comes from ECARX AdaptAPI (the
 * android.car properties are static placeholders on this head unit); the permissions stay declared
 * because the vendor services may check them, as in CarLight, and the capability page lists them.
 */
object VehiclePermissions {
    val PERMISSIONS = listOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_ENERGY_PORTS",
        "android.car.permission.CAR_EXTERIOR_LIGHTS",
        "android.car.permission.CAR_TIRES",
        "android.car.permission.CONTROL_CAR_DOORS",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
    )
}
