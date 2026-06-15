using Ember.Api;
using Ember.Ble;
using Ember.Data;
using Ember.Data.Groups;
using Ember.Data.Schedule;

namespace Ember.Services;

public static class ServiceLocator
{
    public static HeaterClient            Ble            { get; private set; } = null!;
    public static BoundDeviceStore        BoundDevices   { get; private set; } = null!;
    public static AppSettings             Settings       { get; private set; } = null!;
    public static ScheduleStore           ScheduleStore  { get; private set; } = null!;
    public static ScheduleController      Scheduler      { get; private set; } = null!;
    public static AutoStartStopController AutoController { get; private set; } = null!;
    public static GroupStore              Groups         { get; private set; } = null!;
    public static GroupController         GroupCtl       { get; private set; } = null!;
    public static ApiServer               Api            { get; private set; } = null!;
    public static UpnpForwarder           Upnp           { get; private set; } = null!;
    public static FuelStore               Fuel           { get; private set; } = null!;
    public static FuelTracker             FuelCtl        { get; private set; } = null!;

    private static bool _initialised;

    public static void Init()
    {
        if (_initialised) return;
        BoundDevices   = new BoundDeviceStore();
        Settings       = new AppSettings();
        Ble            = new HeaterClient();
        // HeaterClient needs the bound-device store to learn which protocol
        // to speak for a given MAC at connect time. Construction order
        // forbids passing it via constructor, so inject after.
        Ble.AttachBoundDevices(BoundDevices);
        ScheduleStore  = new ScheduleStore();
        Scheduler      = new ScheduleController(Ble, ScheduleStore, Settings);
        AutoController = new AutoStartStopController(Ble, Settings);
        Groups         = new GroupStore();
        GroupCtl       = new GroupController(Ble, Groups, BoundDevices);
        Api            = new ApiServer();
        Upnp           = new UpnpForwarder();
        Fuel           = new FuelStore();
        FuelCtl        = new FuelTracker(Ble, BoundDevices, Fuel);
        Scheduler.Start();
        AutoController.Start();
        FuelCtl.Start();
        _initialised = true;
    }

    public static void Dispose()
    {
        if (!_initialised) return;
        _ = Api.StopAsync();
        Upnp.Stop();
        _ = Scheduler.DisposeAsync().AsTask();
        _ = AutoController.DisposeAsync().AsTask();
        _ = FuelCtl.DisposeAsync().AsTask();
        _ = Ble.DisposeAsync().AsTask();
    }
}
