namespace TsgbHeater.Ble;

public static class BleConstants
{
    // Primary GATT service UUID exposed by the HeatGenie-derived heater.
    // We pick characteristics by property bits rather than UUID since the
    // vendor's char UUIDs vary across firmware revisions.
    public static readonly Guid HeaterService = new("00003a00-0000-1000-8000-00805f9b34fb");

    // Standard Client Characteristic Configuration Descriptor (CCCD).
    public static readonly Guid Cccd = new("00002902-0000-1000-8000-00805f9b34fb");
}
