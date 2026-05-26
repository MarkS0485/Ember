using System.Windows.Controls;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class MePage : UserControl
{
    public MePage() => InitializeComponent();

    private void OnGroups(object? sender, EventArgs e)          => Navigation.Push(new GroupsPage());
    private void OnAutoStartStop(object? sender, EventArgs e)   => Navigation.Push(new AutoStartStopPage());
    private void OnSchedule(object? sender, EventArgs e)        => Navigation.Push(new SchedulePage());
    private void OnHeaterTimer(object? sender, EventArgs e)     => Navigation.Push(new HeaterTimerPage());
    private void OnSwitches(object? sender, EventArgs e)        => Navigation.Push(new SwitchesPage());
    private void OnAdvance(object? sender, EventArgs e)         => Navigation.Push(new AdvancePage());
    private void OnTestMode(object? sender, EventArgs e)        => Navigation.Push(new TestModePage());
    private void OnFlags(object? sender, EventArgs e)           => Navigation.Push(new FlagsPage());
    private void OnDebugBox(object? sender, EventArgs e)        => Navigation.Push(new DebugBoxPage());
    private void OnTestCmd(object? sender, EventArgs e)         => Navigation.Push(new TestCmdPage());
    private void OnAltitudeProbe(object? sender, EventArgs e)   => Navigation.Push(new AltitudeProbePage());
    private void OnMenuRef(object? sender, EventArgs e)         => Navigation.Push(new MenuRefPage());
    private void OnAbout(object? sender, EventArgs e)           => Navigation.Push(new AboutPage());
}
