using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Ui.ViewModels;

namespace TsgbHeater.Ui.Pages;

public partial class SwitchesPage : UserControl
{
    public SwitchesPage() => InitializeComponent();
    private SwitchesViewModel Vm => (SwitchesViewModel)DataContext;

    private void OnKeepAliveClick(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox cb) Vm.ToggleKeepAlive(cb.IsChecked == true);
    }
    private void OnAutoMasterClick(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox cb) Vm.ToggleAutoStartStopMaster(cb.IsChecked == true);
    }
}
