using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Data.Schedule;
using TsgbHeater.Ui.Dialogs;
using TsgbHeater.Ui.ViewModels;

namespace TsgbHeater.Ui.Pages;

public partial class SchedulePage : UserControl
{
    public SchedulePage() => InitializeComponent();

    private ScheduleViewModel Vm => (ScheduleViewModel)DataContext;

    private void OnEnabledClick(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox cb) Vm.SetEnabled(cb.IsChecked == true);
    }

    private void OnAddDay(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is int day)
        {
            var dlg = new TimeRangeDialog { Owner = Window.GetWindow(this) };
            if (dlg.ShowDialog() == true)
                Vm.AddEvent(day, dlg.OnMinute, dlg.OffMinute);
        }
    }

    private void OnToggleEventClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is ScheduleEvent ev)
            Vm.ToggleEventCommand.Execute(ev);
    }

    private void OnDeleteEventClick(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is ScheduleEvent ev)
            Vm.RemoveCommand.Execute(ev);
    }
}
