using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using QRCoder;
using Ember.Api;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed partial class ApiServerViewModel : ObservableObject
{
    private readonly ApiServer _api = ServiceLocator.Api;
    private readonly UpnpForwarder _upnp = ServiceLocator.Upnp;

    [ObservableProperty] private bool   _running;
    [ObservableProperty] private int    _port = 8800;
    [ObservableProperty] private string _certThumbprint = "";
    [ObservableProperty] private string _lanAddresses = "";
    [ObservableProperty] private string _upnpStatus = "Idle";
    [ObservableProperty] private string? _publicAddress;
    [ObservableProperty] private string _newClientLabel = "Phone";
    [ObservableProperty] private ImageSource? _pairingQr;
    [ObservableProperty] private string _pairingUriPreview = "";
    [ObservableProperty] private string _selectedHost = "";

    public ObservableCollection<string> HostChoices { get; } = new();
    public ObservableCollection<PairedClient> Clients { get; } = new();

    public ApiServerViewModel()
    {
        _api.RunningChanged += () => RunOnUi(SyncFromServer);
        _api.PairedClients.Changed += () => RunOnUi(RebuildClients);
        _upnp.StatusChanged += () => RunOnUi(() =>
        {
            UpnpStatus = _upnp.Status;
            PublicAddress = _upnp.PublicAddress;
            RebuildHosts();
        });
        SyncFromServer();
        RebuildHosts();
        RebuildClients();
    }

    private void SyncFromServer()
    {
        Running = _api.Running;
        Port = _api.Port;
        CertThumbprint = _api.CertSha256;
    }

    private void RebuildHosts()
    {
        var current = SelectedHost;
        HostChoices.Clear();
        foreach (var ip in ApiServer.EnumerateLanAddresses())
            HostChoices.Add(ip);
        if (!string.IsNullOrEmpty(PublicAddress) && !HostChoices.Contains(PublicAddress))
            HostChoices.Add(PublicAddress);
        if (HostChoices.Count == 0) HostChoices.Add("localhost");
        if (string.IsNullOrEmpty(current) || !HostChoices.Contains(current))
            SelectedHost = HostChoices[0];
        else
            SelectedHost = current;
    }

    private void RebuildClients()
    {
        Clients.Clear();
        foreach (var c in _api.PairedClients.All) Clients.Add(c);
    }

    [RelayCommand]
    private async Task ToggleServer()
    {
        if (Running)
        {
            await _api.StopAsync();
            _upnp.Stop();
        }
        else
        {
            await _api.StartAsync(Port);
            _upnp.Start(Port);
        }
    }

    [RelayCommand]
    private void Pair()
    {
        var c = _api.PairedClients.CreatePending(NewClientLabel);
        var host = string.IsNullOrEmpty(SelectedHost) ? "localhost" : SelectedHost;
        var uri = _api.BuildPairingUri(c, host);
        PairingUriPreview = uri;
        PairingQr = MakeQr(uri);
    }

    [RelayCommand]
    private void Revoke(PairedClient c) => _api.PairedClients.Revoke(c.KeyId);

    [RelayCommand]
    private void Delete(PairedClient c) => _api.PairedClients.Delete(c.KeyId);

    private static BitmapSource MakeQr(string payload)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        var img = new BitmapImage();
        using var ms = new System.IO.MemoryStream(png);
        img.BeginInit();
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.StreamSource = ms;
        img.EndInit();
        img.Freeze();
        return img;
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
