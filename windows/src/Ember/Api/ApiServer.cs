using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Ember.Ble;
using Ember.Data;
using Ember.Data.Groups;
using Ember.Data.Schedule;
using Ember.Services;

namespace Ember.Api;

// Embedded HTTPS API server. Self-contained: own its lifecycle, owns the
// HMAC middleware wiring, owns the route table. Uses ServiceLocator to
// reach the heater client / stores so it doesn't need its own DI.
public sealed class ApiServer
{
    public PairedClientStore PairedClients { get; }
    public int Port { get; private set; } = 8800;
    public string CertSha256 { get; private set; } = "";

    public bool Running => _host != null;
    public event Action? RunningChanged;

    private WebApplication? _host;
    private readonly object _lock = new();

    public ApiServer()
    {
        PairedClients = new PairedClientStore();
        // Compute the cert thumbprint EAGERLY. The pairing QR must carry it
        // even when the server isn't running yet — users have a habit of
        // clicking Generate before clicking Start, and an empty `t=` in the
        // URI gives Android a "cert pin mismatch (expected: <blank>)" that
        // looks like a TLS problem when really it's a UX-ordering one.
        try
        {
            var cert = ServerCert.LoadOrCreate();
            CertSha256 = ServerCert.Sha256Thumbprint(cert);
        }
        catch (Exception ex) { Log.W("api", $"cert preload failed: {ex.Message}"); }
    }

    public async Task StartAsync(int port)
    {
        lock (_lock)
        {
            if (_host != null) return;
            Port = port;
        }

        var cert = ServerCert.LoadOrCreate();
        CertSha256 = ServerCert.Sha256Thumbprint(cert);

        var builder = WebApplication.CreateBuilder();
        builder.WebHost.ConfigureKestrel(o =>
        {
            o.ListenAnyIP(port, lo =>
            {
                lo.UseHttps(cert);
                lo.Protocols = HttpProtocols.Http1AndHttp2;
            });
        });
        builder.Logging.ClearProviders();

        var app = builder.Build();
        app.Use(async (ctx, next) =>
        {
            // /api/ping is open so clients can probe pairing without auth.
            if (ctx.Request.Path.StartsWithSegments("/api/ping"))
            {
                await next();
                return;
            }
            if (!await HmacAuth.Validate(ctx, PairedClients)) return;
            await next();
        });

        ApiEndpoints.Map(app);

        _host = app;
        await app.StartAsync().ConfigureAwait(false);
        Log.I("api", $"server up on https://0.0.0.0:{port} thumbprint={CertSha256}");
        RunningChanged?.Invoke();
    }

    public async Task StopAsync()
    {
        WebApplication? h;
        lock (_lock) { h = _host; _host = null; }
        if (h == null) return;
        try { await h.StopAsync().ConfigureAwait(false); }
        catch (Exception ex) { Log.W("api", $"stop threw: {ex.Message}"); }
        await h.DisposeAsync().ConfigureAwait(false);
        Log.I("api", "server stopped");
        RunningChanged?.Invoke();
    }

    // --- Pairing URI -------------------------------------------------

    public string BuildPairingUri(PairedClient c, string host)
    {
        // The Android side parses this. Keys are short to keep the QR
        // dense: u=url, k=keyId, s=secret(base64-url), t=cert thumbprint.
        var secretUrl = c.SecretBase64
            .Replace('+', '-').Replace('/', '_').TrimEnd('=');
        return $"ember://pair?u=https%3A%2F%2F{host}%3A{Port}" +
               $"&k={c.KeyId}&s={secretUrl}&t={CertSha256}";
    }

    public static IEnumerable<string> EnumerateLanAddresses()
    {
        try
        {
            foreach (var ip in Dns.GetHostAddresses(Dns.GetHostName()))
                if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    yield return ip.ToString();
        }
        finally { }
    }
}
