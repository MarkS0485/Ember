using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class ScanPage : UserControl
{
    public ScanPage() => InitializeComponent();

    private void OnManage(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is string mac)
            Navigation.Push(new BindPage(mac));
    }
}
