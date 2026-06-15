using CommunityToolkit.Mvvm.ComponentModel;

namespace Ember.Ui.ViewModels;

public enum AppTab { Scan, Device, Me }

public partial class MainViewModel : ObservableObject
{
    [ObservableProperty] private AppTab _currentTab = AppTab.Scan;
}
