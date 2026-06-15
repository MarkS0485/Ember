using System.Windows;
using System.Windows.Controls;
using Ember.Services;

namespace Ember.Ui.Pages;

public partial class ScanPage : UserControl
{
    public ScanPage() => InitializeComponent();

    private void OnManage(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is string mac)
            Navigation.Push(new BindPage(mac));
    }
}
