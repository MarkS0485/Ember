using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using TsgbHeater.Ble;
using TsgbHeater.Data;
using TsgbHeater.Data.Groups;
using TsgbHeater.Data.Schedule;
using TsgbHeater.Services;

namespace TsgbHeater.Api;

// All v1 endpoints. Thin wrappers — every method on HeaterClient /
// ScheduleStore / GroupStore / AppSettings gets a route. Where an action
// requires an active BLE link we ensure it before firing (idempotent
// connect with a short wait), and report back what happened.
public static class ApiEndpoints
{
    public static void Map(WebApplication app)
    {
        // Open ping for pairing-test (no HMAC). Returns the cert
        // thumbprint so a client can sanity-check it matches the QR.
        app.MapGet("/api/ping", () => Results.Json(new
        {
            ok = true,
            cert = ServiceLocator.Api.CertSha256,
        }));

        // --- Status -------------------------------------------------

        app.MapGet("/api/v1/status", () =>
        {
            var s = ServiceLocator.Ble.State;
            var t = ServiceLocator.Ble.Telemetry;
            var mac = ServiceLocator.BoundDevices.CurrentMac;
            // Fuel snapshot tucked into the same status payload so a
            // single poll gets everything the remote UI needs to render.
            var fuel = mac == null ? null : FuelDto(ServiceLocator.FuelCtl.Snapshot(mac));
            return Results.Json(new
            {
                state = s.ToString(),
                isReady = s == ConnectionState.Ready,
                lastError = ServiceLocator.Ble.LastError,
                currentMac = mac,
                telemetry = t == null ? null : new
                {
                    runningMode  = t.RunningMode.ToString(),
                    runningLabel = t.RunningMode.Label(),
                    ambientC     = t.AmbientTempC,
                    housingC     = t.HousingTempC,
                    intakeC      = t.IntakeTempC,
                    outletC      = t.OutletTempC,
                    batteryV     = t.BatteryV,
                    altitudeM    = t.AltitudeM,
                    fanRpm       = t.FanRpm,
                    pumpHz       = t.FuelPumpHz,
                    ignitionW    = t.IgnitionWatts,
                    targetC      = t.TargetTempC,
                    aimGear      = t.AimGear,
                    tempUnitF    = t.TempUnitFahrenheit,
                    faultBits    = t.FaultBits,
                    updatedAtMs  = t.UpdatedAtMs,
                },
                fuel,
            });
        });

        // --- Fuel ---------------------------------------------------
        //
        // All fuel endpoints operate on the CURRENT bound device unless
        // ?mac=XX is given. Refill/level/config are idempotent under
        // repeated calls — useful from a thumb-fat remote.

        app.MapGet("/api/v1/fuel", (string? mac) =>
        {
            var m = mac ?? ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current device" });
            return Results.Json(FuelDto(ServiceLocator.FuelCtl.Snapshot(m)) ?? (object)new { error = "not bound" });
        });

        app.MapPost("/api/v1/fuel/refill", (FuelLitresBody body) =>
        {
            var m = body.Mac ?? ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current device" });
            ServiceLocator.FuelCtl.Refill(m, body.Litres);
            return Results.Json(FuelDto(ServiceLocator.FuelCtl.Snapshot(m)));
        });

        app.MapPost("/api/v1/fuel/level", (FuelLitresBody body) =>
        {
            var m = body.Mac ?? ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current device" });
            ServiceLocator.FuelCtl.SetLevel(m, body.Litres);
            return Results.Json(FuelDto(ServiceLocator.FuelCtl.Snapshot(m)));
        });

        app.MapPut("/api/v1/fuel/config", (FuelConfigBody body) =>
        {
            var m = body.Mac ?? ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current device" });
            ServiceLocator.BoundDevices.UpdateFuelConfig(m, body.Tank, body.Low, body.High);
            return Results.Json(FuelDto(ServiceLocator.FuelCtl.Snapshot(m)));
        });

        // --- Connection management ----------------------------------

        app.MapPost("/api/v1/connect", async (ConnectBody? body) =>
        {
            var mac = body?.Mac ?? ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(mac)) return Results.BadRequest(new { error = "no MAC and no current device" });
            ServiceLocator.BoundDevices.SetCurrent(mac);
            _ = ServiceLocator.Ble.ConnectAsync(mac);
            return await WaitReady(timeoutMs: 12_000);
        });

        app.MapPost("/api/v1/disconnect", async () =>
        {
            await ServiceLocator.Ble.DisconnectAsync();
            return Ok();
        });

        // --- Heater control ----------------------------------------

        app.MapPost("/api/v1/heater/start", () => Fire(ble => ble.SendStart()));
        app.MapPost("/api/v1/heater/stop",  () => Fire(ble => ble.SendStop()));
        app.MapPost("/api/v1/heater/vent",  () => Fire(ble => ble.BlowOn()));

        app.MapPost("/api/v1/heater/target", (TargetBody body) =>
            Fire(ble => ble.SetTargetTemp(body.C)));

        app.MapPost("/api/v1/heater/gear", (GearBody body) =>
            Fire(ble => ble.SetGear(body.G)));

        app.MapPost("/api/v1/heater/runmode", (ModeBody body) =>
        {
            var m = body.Mode switch
            {
                "auto"   => FrameCodec.RunMode.Auto,
                "manual" => FrameCodec.RunMode.Manual,
                "ss"     => FrameCodec.RunMode.StartStop,
                _        => FrameCodec.RunMode.Auto,
            };
            return Fire(ble => ble.SetRunMode(m));
        });

        app.MapPost("/api/v1/heater/hysteresis", (TargetBody body) =>
            Fire(ble => ble.SetTempHysteresis(body.C)));

        app.MapPost("/api/v1/heater/temp-unit", (UnitBody body) =>
            Fire(ble => body.Unit == "f"
                ? ble.WriteAsync(FrameCodec.BuildSwitchToFahrenheit())
                : ble.WriteAsync(FrameCodec.BuildSwitchToCelsius())));

        // --- Test mode ---------------------------------------------

        app.MapPost("/api/v1/test/pump",       (PumpBody body)  => Fire(ble => ble.OilPumpOn(body.Seconds)));
        app.MapPost("/api/v1/test/pump-off",   () => Fire(ble => ble.OilPumpOff()));
        app.MapPost("/api/v1/test/blow",       () => Fire(ble => ble.BlowOn()));
        app.MapPost("/api/v1/test/button-up",  () => Fire(ble => ble.WriteAsync(FrameCodec.BuildButtonUp())));
        app.MapPost("/api/v1/test/button-down",() => Fire(ble => ble.WriteAsync(FrameCodec.BuildButtonDown())));
        app.MapPost("/api/v1/test/read-reg",   () => Fire(ble => ble.ReadRegInfo()));

        // --- Schedule ----------------------------------------------

        app.MapGet("/api/v1/schedule", () =>
        {
            var s = ServiceLocator.ScheduleStore.Get();
            return Results.Json(s);
        });

        app.MapPut("/api/v1/schedule", (Schedule body) =>
        {
            ServiceLocator.ScheduleStore.Set(body ?? new Schedule());
            return Ok();
        });

        app.MapGet("/api/v1/schedule/mode", () =>
            Results.Json(new { enabled = ServiceLocator.Settings.ScheduleModeEnabled }));

        app.MapPut("/api/v1/schedule/mode", (EnableBody body) =>
        {
            ServiceLocator.Settings.SetScheduleMode(body.Enabled);
            return Ok();
        });

        app.MapPost("/api/v1/schedule/clear-heater", async () =>
        {
            var ok = await ServiceLocator.Scheduler.ClearHeaterAsync();
            return Results.Json(new { ok });
        });

        app.MapPost("/api/v1/schedule/read-heater-slots", () =>
            Fire(ble => ble.ReadTimerInfo()));

        // --- Auto rules --------------------------------------------

        app.MapGet("/api/v1/auto", () => Results.Json(ServiceLocator.Settings.AutoRules));

        app.MapPut("/api/v1/auto", (AutoRules body) =>
        {
            ServiceLocator.Settings.SetAutoRules(body);
            return Ok();
        });

        // --- App settings ------------------------------------------

        app.MapGet("/api/v1/settings", () => Results.Json(new
        {
            keepAlive             = ServiceLocator.Settings.KeepAlive,
            scheduleModeEnabled   = ServiceLocator.Settings.ScheduleModeEnabled,
            autoStartStopMaster   = ServiceLocator.Settings.AutoStartStopMaster,
            selectedRunModeWire   = ServiceLocator.Settings.SelectedRunModeWire,
        }));

        app.MapPut("/api/v1/settings", (SettingsBody body) =>
        {
            if (body.KeepAlive            is { } a) ServiceLocator.Settings.SetKeepAlive(a);
            if (body.ScheduleModeEnabled  is { } b) ServiceLocator.Settings.SetScheduleMode(b);
            if (body.AutoStartStopMaster  is { } c) ServiceLocator.Settings.SetAutoStartStopMaster(c);
            if (body.SelectedRunModeWire  is { } d) ServiceLocator.Settings.SetSelectedRunMode(d);
            return Ok();
        });

        // --- Bound devices -----------------------------------------

        app.MapGet("/api/v1/devices", () => Results.Json(new
        {
            currentMac = ServiceLocator.BoundDevices.CurrentMac,
            all = ServiceLocator.BoundDevices.All,
        }));

        app.MapDelete("/api/v1/devices/{mac}", (string mac) =>
        {
            ServiceLocator.Groups.DropMacEverywhere(mac);
            ServiceLocator.BoundDevices.Remove(mac);
            return Ok();
        });

        app.MapPost("/api/v1/devices/{mac}/current", (string mac) =>
        {
            ServiceLocator.BoundDevices.SetCurrent(mac);
            return Ok();
        });

        app.MapPost("/api/v1/devices/{mac}/rename", (string mac, RenameBody body) =>
        {
            if (!string.IsNullOrWhiteSpace(body.Name))
                ServiceLocator.BoundDevices.Rename(mac, body.Name.Trim());
            return Ok();
        });

        // --- Scan -------------------------------------------------

        app.MapPost("/api/v1/scan/start", () => { ServiceLocator.Ble.StartScan(); return Ok(); });
        app.MapPost("/api/v1/scan/stop",  () => { ServiceLocator.Ble.StopScan();  return Ok(); });
        app.MapGet ("/api/v1/scan/devices", () => Results.Json(ServiceLocator.Ble.Devices));

        // --- Groups ----------------------------------------------

        app.MapGet("/api/v1/groups", () => Results.Json(ServiceLocator.Groups.All));

        app.MapPost("/api/v1/groups", (GroupCreateBody body) =>
        {
            var g = new HeaterGroup(body.Name, body.Macs);
            ServiceLocator.Groups.Add(g);
            return Results.Json(g);
        });

        app.MapDelete("/api/v1/groups/{id}", (string id) =>
        {
            ServiceLocator.Groups.Remove(id);
            return Ok();
        });

        app.MapPost("/api/v1/groups/{id}/start", async (string id) =>
            Results.Json(new { fired = await ServiceLocator.GroupCtl.ApplyAsync(id, ble => ble.SendStart()) }));
        app.MapPost("/api/v1/groups/{id}/stop",  async (string id) =>
            Results.Json(new { fired = await ServiceLocator.GroupCtl.ApplyAsync(id, ble => ble.SendStop()) }));
        app.MapPost("/api/v1/groups/{id}/vent",  async (string id) =>
            Results.Json(new { fired = await ServiceLocator.GroupCtl.ApplyAsync(id, ble => ble.BlowOn()) }));

        // --- Probes / debug --------------------------------------

        app.MapPost("/api/v1/probe/short-para", (ProbeBody body) =>
            Fire(ble => ble.WriteAsync(FrameCodec.BuildShortParaProbe(body.Index, body.D1, body.D2))));

        app.MapPost("/api/v1/cmd/hex", async (HexBody body) =>
        {
            byte[] bytes;
            try { bytes = FrameCodec.FromHex(body.Hex); }
            catch (Exception ex) { return Results.BadRequest(new { error = ex.Message }); }
            return await Fire(ble => ble.WriteAsync(bytes));
        });

        app.MapGet("/api/v1/log/tail", () =>
        {
            try
            {
                var lines = System.IO.File.ReadAllLines(Log.LogPath);
                int take = Math.Min(200, lines.Length);
                return Results.Json(lines[^take..]);
            }
            catch (Exception ex) { return Results.Problem(ex.Message); }
        });
    }

    // --- Helpers --------------------------------------------------

    private static IResult Ok() => Results.Json(new { ok = true });

    // Wait up to ~12 s for the BLE link to reach Ready, then return the
    // status payload.
    private static async Task<IResult> WaitReady(int timeoutMs)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            if (ServiceLocator.Ble.State == ConnectionState.Ready)
                return Results.Json(new { ok = true, ready = true });
            if (ServiceLocator.Ble.State == ConnectionState.Failed)
                return Results.Json(new { ok = false, ready = false,
                                          error = ServiceLocator.Ble.LastError });
            await Task.Delay(200);
        }
        return Results.Json(new { ok = false, ready = false, error = "timeout" });
    }

    // Common pattern: ensure link is up, then fire the action.
    private static async Task<IResult> Fire(Func<HeaterClient, Task<bool>> action)
    {
        var ble = ServiceLocator.Ble;
        if (ble.State != ConnectionState.Ready)
        {
            var mac = ServiceLocator.BoundDevices.CurrentMac;
            if (string.IsNullOrEmpty(mac))
                return Results.BadRequest(new { error = "no current device" });
            _ = ble.ConnectAsync(mac);
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 12_000 && ble.State != ConnectionState.Ready)
                await Task.Delay(150);
            if (ble.State != ConnectionState.Ready)
                return Results.Json(new { ok = false, error = ble.LastError });
        }
        bool ok = await action(ble);
        return Results.Json(new { ok });
    }

    // --- DTO records --------------------------------------------

    public sealed record ConnectBody(string? Mac);
    public sealed record TargetBody(int C);
    public sealed record GearBody(int G);
    public sealed record ModeBody(string Mode);
    public sealed record UnitBody(string Unit);
    public sealed record PumpBody(int Seconds);
    public sealed record EnableBody(bool Enabled);
    public sealed record RenameBody(string Name);
    public sealed record SettingsBody(bool? KeepAlive, bool? ScheduleModeEnabled,
                                       bool? AutoStartStopMaster, int? SelectedRunModeWire);
    public sealed record GroupCreateBody(string Name, string[] Macs);
    public sealed record ProbeBody(int Index, int D1, int D2);
    public sealed record HexBody(string Hex);

    // --- Fuel DTOs ----------------------------------------------

    public sealed record FuelLitresBody(double Litres, string? Mac = null);
    public sealed record FuelConfigBody(double? Tank, double? Low, double? High, string? Mac = null);

    // Flatten a FuelSnapshot into a JSON-friendly shape, computing
    // the current consumption rate at the live gear so remote clients
    // don't have to know about the gear→L/h interpolation themselves.
    private static object? FuelDto(FuelTracker.FuelSnapshot? snap)
    {
        if (snap == null) return null;
        int gear = ServiceLocator.Ble.Telemetry?.AimGear ?? 5;
        double lph = FuelTracker.ConsumptionForGear(gear, snap.ConsumptionLowLph, snap.ConsumptionHighLph);
        return new
        {
            mac                 = snap.Mac,
            currentLitres       = snap.CurrentLitres,
            tankLitres          = snap.TankLitres,
            consumptionLowLph   = snap.ConsumptionLowLph,
            consumptionHighLph  = snap.ConsumptionHighLph,
            currentLph          = lph,
            hoursRemaining      = lph > 0 ? snap.CurrentLitres / lph : (double?)null,
            alert               = snap.Alert.ToString(),  // "None"|"Warning"|"Critical"|"Shutdown"
        };
    }
}
