using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class BindPage : UserControl
{
    private readonly string _mac;

    public BindPage(string mac)
    {
        InitializeComponent();
        _mac = mac;
        MacLine.Text = mac;
        var d = ServiceLocator.BoundDevices.All.FirstOrDefault(x =>
            string.Equals(x.Mac, mac, StringComparison.OrdinalIgnoreCase));
        if (d != null) NameBox.Text = d.Name;
    }

    private void OnRename(object sender, RoutedEventArgs e)
    {
        var name = NameBox.Text.Trim();
        if (string.IsNullOrEmpty(name)) return;
        ServiceLocator.BoundDevices.Rename(_mac, name);
    }

    private async void OnUnbind(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(Window.GetWindow(this),
                "Unbind this heater? Schedule and group memberships referencing it will lose this MAC.",
                "Confirm", MessageBoxButton.OKCancel, MessageBoxImage.Question)
            != MessageBoxResult.OK) return;

        ServiceLocator.Groups.DropMacEverywhere(_mac);
        ServiceLocator.BoundDevices.Remove(_mac);
        if (string.Equals(ServiceLocator.BoundDevices.CurrentMac, _mac, StringComparison.OrdinalIgnoreCase))
        {
            await ServiceLocator.Ble.DisconnectAsync();
            ServiceLocator.BoundDevices.SetCurrent(null);
        }
        Navigation.Pop();
    }
}
