using System.Windows.Controls;

namespace Ember.Services;

// Tiny page-stack navigator. The MainWindow listens to Pushed/Popped and
// overlays the active sub-page on top of the bottom-tab UI. Mirrors what
// Compose Navigation gives us on the Android side.
public static class Navigation
{
    private static readonly Stack<UserControl> Stack = new();

    public static UserControl? Current => Stack.Count > 0 ? Stack.Peek() : null;

    public static event Action<UserControl?>? Changed;

    public static void Push(UserControl page)
    {
        Stack.Push(page);
        Changed?.Invoke(Current);
    }

    public static void Pop()
    {
        if (Stack.Count == 0) return;
        Stack.Pop();
        Changed?.Invoke(Current);
    }

    public static void PopToRoot()
    {
        Stack.Clear();
        Changed?.Invoke(null);
    }
}
