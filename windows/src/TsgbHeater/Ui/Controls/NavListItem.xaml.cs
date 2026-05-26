using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace TsgbHeater.Ui.Controls;

public partial class NavListItem : UserControl
{
    public NavListItem() => InitializeComponent();

    public static readonly DependencyProperty TitleProperty =
        DependencyProperty.Register(nameof(Title), typeof(string), typeof(NavListItem),
            new PropertyMetadata(""));
    public static readonly DependencyProperty SubtitleProperty =
        DependencyProperty.Register(nameof(Subtitle), typeof(string), typeof(NavListItem),
            new PropertyMetadata(""));
    public static readonly DependencyProperty IconProperty =
        DependencyProperty.Register(nameof(Icon), typeof(string), typeof(NavListItem),
            new PropertyMetadata(""));    // Settings icon
    public static readonly DependencyProperty AccentProperty =
        DependencyProperty.Register(nameof(Accent), typeof(Brush), typeof(NavListItem),
            new PropertyMetadata(Brushes.Gray));

    public string Title    { get => (string)GetValue(TitleProperty);    set => SetValue(TitleProperty, value); }
    public string Subtitle { get => (string)GetValue(SubtitleProperty); set => SetValue(SubtitleProperty, value); }
    public string Icon     { get => (string)GetValue(IconProperty);     set => SetValue(IconProperty, value); }
    public Brush  Accent   { get => (Brush)GetValue(AccentProperty);    set => SetValue(AccentProperty, value); }

    public event EventHandler? Clicked;
    private void OnClick(object sender, RoutedEventArgs e) => Clicked?.Invoke(this, EventArgs.Empty);
}
