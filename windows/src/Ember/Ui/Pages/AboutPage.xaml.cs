using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using Ember.Services;

namespace Ember.Ui.Pages;

public partial class AboutPage : UserControl
{
    public AboutPage()
    {
        InitializeComponent();
        LogPathLine.Text = Log.LogPath;
    }

    private void OnOpenLog(object sender, RoutedEventArgs e)
    {
        var dir = Path.GetDirectoryName(Log.LogPath);
        if (dir != null) Process.Start(new ProcessStartInfo("explorer.exe", dir) { UseShellExecute = true });
    }
}
