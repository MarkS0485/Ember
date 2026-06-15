using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using Ember.Data.Groups;
using Ember.Services;

namespace Ember.Ui.Pages;

public partial class GroupCreatePage : UserControl
{
    public sealed class MemberRow : INotifyPropertyChanged
    {
        public string Mac { get; init; } = "";
        public string Name { get; init; } = "";
        private bool _selected;
        public bool Selected
        {
            get => _selected;
            set { _selected = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Selected))); }
        }
        public event PropertyChangedEventHandler? PropertyChanged;
    }

    public GroupCreatePage()
    {
        InitializeComponent();
        var rows = ServiceLocator.BoundDevices.All
            .Select(d => new MemberRow { Mac = d.Mac, Name = d.Name }).ToList();
        MemberList.ItemsSource = rows;
    }

    private void OnCreate(object sender, RoutedEventArgs e)
    {
        var rows = (IEnumerable<MemberRow>)MemberList.ItemsSource;
        var picked = rows.Where(r => r.Selected).Select(r => r.Mac).ToArray();
        if (picked.Length == 0)
        {
            MessageBox.Show(Window.GetWindow(this), "Pick at least one heater.", "No members",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        var name = string.IsNullOrWhiteSpace(NameBox.Text) ? "Group" : NameBox.Text.Trim();
        ServiceLocator.Groups.Add(new HeaterGroup(name, picked));
        Navigation.Pop();
    }
}
