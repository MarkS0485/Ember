using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Http;
using Ember.Services;

namespace Ember.Api;

// HMAC-SHA256 request signer. Wire format:
//
//   Authorization: EMBER1 keyId=<id>,ts=<unix-ms>,nonce=<hex>,sig=<base64>
//
// Canonical string:
//   METHOD + "\n" + PATH + "\n" + QUERY + "\n" + ts + "\n" + nonce +
//   "\n" + lowercase-hex-sha256(body)
//
// Server rejects:
//   - missing / malformed header
//   - unknown keyId (or revoked)
//   - ts skew > ±60 s from server clock
//   - nonce seen within the last 5 minutes
//   - signature mismatch (constant-time compared)
//
// Body is always read fully and the request body is rewound so the
// downstream handler can re-read it.
public static class HmacAuth
{
    private const string Scheme = "EMBER1";
    private const long   MaxSkewMs = 60_000;
    private static readonly ConcurrentDictionary<string, long> RecentNonces = new();

    public static async Task<bool> Validate(HttpContext ctx, PairedClientStore store)
    {
        // Authorization header
        if (!ctx.Request.Headers.TryGetValue("Authorization", out var raw) || raw.Count == 0)
            return Deny(ctx, "missing Authorization");
        var header = raw[0]!.Trim();
        if (!header.StartsWith(Scheme + " ", StringComparison.Ordinal))
            return Deny(ctx, "wrong scheme");

        // Parse "k=v,k=v,..."
        var parts = header[(Scheme.Length + 1)..]
            .Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        string? keyId = null, ts = null, nonce = null, sig = null;
        foreach (var p in parts)
        {
            var eq = p.IndexOf('=');
            if (eq <= 0) continue;
            var k = p[..eq]; var v = p[(eq + 1)..];
            switch (k)
            {
                case "keyId": keyId = v; break;
                case "ts":    ts    = v; break;
                case "nonce": nonce = v; break;
                case "sig":   sig   = v; break;
            }
        }
        if (keyId == null || ts == null || nonce == null || sig == null)
            return Deny(ctx, "missing header field");

        var client = store.FindByKeyId(keyId);
        if (client == null) return Deny(ctx, "unknown keyId");

        if (!long.TryParse(ts, out var tsMs))
            return Deny(ctx, "bad ts");
        var skew = Math.Abs(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - tsMs);
        if (skew > MaxSkewMs) return Deny(ctx, $"skew {skew}ms");

        // Replay cache — purge entries older than 5 minutes opportunistically.
        long cutoff = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 5 * 60_000;
        foreach (var kv in RecentNonces)
            if (kv.Value < cutoff) RecentNonces.TryRemove(kv.Key, out _);
        var nonceKey = $"{keyId}:{nonce}";
        if (!RecentNonces.TryAdd(nonceKey, tsMs))
            return Deny(ctx, "replay");

        // Read body, sha256 it, then rewind so the handler can re-read.
        ctx.Request.EnableBuffering();
        string bodyHex;
        using (var sha = SHA256.Create())
        using (var ms = new MemoryStream())
        {
            await ctx.Request.Body.CopyToAsync(ms).ConfigureAwait(false);
            ctx.Request.Body.Position = 0;
            bodyHex = Convert.ToHexString(sha.ComputeHash(ms.ToArray())).ToLowerInvariant();
        }

        var canonical =
            ctx.Request.Method      + "\n" +
            ctx.Request.Path.Value  + "\n" +
            (ctx.Request.QueryString.HasValue ? ctx.Request.QueryString.Value!.TrimStart('?') : "") + "\n" +
            ts                      + "\n" +
            nonce                   + "\n" +
            bodyHex;

        byte[] secret;
        try { secret = Convert.FromBase64String(client.SecretBase64); }
        catch { return Deny(ctx, "bad secret"); }
        using var hmac = new HMACSHA256(secret);
        var expected = hmac.ComputeHash(Encoding.UTF8.GetBytes(canonical));
        byte[] received;
        try { received = Convert.FromBase64String(sig); }
        catch { return Deny(ctx, "bad sig"); }
        if (!CryptographicOperations.FixedTimeEquals(expected, received))
            return Deny(ctx, "sig mismatch");

        store.TouchLastSeen(keyId);
        ctx.Items["EmberClient"] = client;
        return true;
    }

    private static bool Deny(HttpContext ctx, string why)
    {
        Log.W("api", $"auth deny: {why} from {ctx.Connection.RemoteIpAddress}");
        ctx.Response.StatusCode = StatusCodes.Status401Unauthorized;
        return false;
    }
}
