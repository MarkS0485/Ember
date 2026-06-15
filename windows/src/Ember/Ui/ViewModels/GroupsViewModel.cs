using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using Ember.Data.Groups;
using Ember.Services;

namespace Ember.Ui.ViewModels;

public sealed class GroupRow
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string MembersLabel { get; init; } = "";
}

public sealed partial class GroupsViewModel : ObservableObject
{
    private readonly GroupStore _store = ServiceLocator.Groups;
    public ObservableCollection<GroupRow> Groups { get; } = new();

    public GroupsViewModel()
    {
        _store.Changed += () => RunOnUi(Rebuild);
        Rebuild();
    }

    private void Rebuild()
    {
        Groups.Clear();
        foreach (var g in _store.All)
        {
            Groups.Add(new GroupRow
            {
                Id = g.Id,
                Name = g.Name,
                MembersLabel = g.MemberMacs.Count == 1
                    ? "1 heater"
                    : $"{g.MemberMacs.Count} heaters",
            });
        }
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
