using System.Windows;
using System.Windows.Controls;
using Ember.Services;

namespace Ember.Ui.Pages;

public partial class DevicePage : UserControl
{
    public DevicePage() => InitializeComponent();

    private void OnTestMode(object sender, RoutedEventArgs e) =>
        Navigation.Push(new TestModePage());
}
