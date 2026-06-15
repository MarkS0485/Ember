using Ember.Ble;
using Ember.Services;

namespace Ember.Data.Groups;

// Fans a single command out across all members of a group. The Windows
// client owns one BLE link at a time, so we sequentially connect →
// command → wait → next. Slow for big groups; matches the Android
// GroupController's shape.
public sealed class GroupController
{
    private readonly HeaterClient     _ble;
    private readonly GroupStore       _store;
    private readonly BoundDeviceStore _devices;

    public GroupController(HeaterClient ble, GroupStore store, BoundDeviceStore devices)
    {
        _ble = ble;
        _store = store;
        _devices = devices;
    }

    public async Task<int> ApplyAsync(string groupId, Func<HeaterClient, Task<bool>> action)
    {
        var g = _store.Get(groupId);
        if (g == null) return 0;
        int successCount = 0;
        foreach (var mac in g.MemberMacs)
        {
            try
            {
                await _ble.ConnectAsync(mac).ConfigureAwait(false);
                // Wait briefly for Ready before firing.
                for (int i = 0; i < 30; i++)
                {
                    if (_ble.State == ConnectionState.Ready) break;
                    await Task.Delay(200).ConfigureAwait(false);
                }
                if (_ble.State != ConnectionState.Ready) continue;
                if (await action(_ble).ConfigureAwait(false)) successCount++;
            }
            catch (Exception ex)
            {
                Log.W("group", $"member {mac} failed: {ex.Message}");
            }
        }
        return successCount;
    }
}
