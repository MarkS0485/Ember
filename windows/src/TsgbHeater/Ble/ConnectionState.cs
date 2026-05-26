namespace TsgbHeater.Ble;

public enum ConnectionState
{
    Idle,
    Scanning,
    Connecting,
    DiscoveringServices,
    Ready,
    Reconnecting,
    Failed,
}
