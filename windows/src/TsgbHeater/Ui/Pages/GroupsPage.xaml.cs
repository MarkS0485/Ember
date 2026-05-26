using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Services;
using TsgbHeater.Ui.ViewModels;

namespace TsgbHeater.Ui.Pages;

public partial class GroupsPage : UserControl
{
    public GroupsPage() => InitializeComponent();

    private void OnNew(object sender, RoutedEventArgs e) =>
        Navigation.Push(new GroupCreatePage());

    private void OnOpenGroup(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement f && f.Tag is string id)
            Navigation.Push(new GroupDetailPage(id));
    }
}
