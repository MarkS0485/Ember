using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class GroupDetailPage : UserControl
{
    private readonly string _id;

    public sealed record MemberRow(string Mac, string Name);

    public GroupDetailPage(string id)
    {
        InitializeComponent();
        _id = id;
        var g = ServiceLocator.Groups.Get(id);
        if (g != null)
        {
            TopBar.Title = g.Name;
            TopBar.Subtitle = g.MemberMacs.Count == 1
                ? "1 heater"
                : $"{g.MemberMacs.Count} heaters";
            var bound = ServiceLocator.BoundDevices.All.ToDictionary(x => x.Mac, x => x.Name,
                StringComparer.OrdinalIgnoreCase);
            MemberList.ItemsSource = g.MemberMacs
                .Select(m => new MemberRow(m, bound.TryGetValue(m, out var n) ? n : m))
                .ToList();
        }
    }

    private async void OnStartAll(object sender, RoutedEventArgs e)
    {
        var n = await ServiceLocator.GroupCtl.ApplyAsync(_id, ble => ble.SendStart());
        ResultLine.Text = $"Started on {n} heater(s)";
    }
    private async void OnStopAll(object sender, RoutedEventArgs e)
    {
        var n = await ServiceLocator.GroupCtl.ApplyAsync(_id, ble => ble.SendStop());
        ResultLine.Text = $"Stopped on {n} heater(s)";
    }
    private async void OnVentAll(object sender, RoutedEventArgs e)
    {
        var n = await ServiceLocator.GroupCtl.ApplyAsync(_id, ble => ble.BlowOn());
        ResultLine.Text = $"Vent started on {n} heater(s)";
    }
    private async void OnTarget20(object sender, RoutedEventArgs e)
    {
        var n = await ServiceLocator.GroupCtl.ApplyAsync(_id, ble => ble.SetTargetTemp(20));
        ResultLine.Text = $"Target 20 °C set on {n} heater(s)";
    }
    private void OnDelete(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(Window.GetWindow(this), "Delete this group?", "Confirm",
                MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK)
            return;
        ServiceLocator.Groups.Remove(_id);
        Navigation.Pop();
    }
}
