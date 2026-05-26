namespace TsgbHeater.Data.Groups;

public sealed record HeaterGroup(
    string Id,
    string Name,
    IReadOnlyList<string> MemberMacs)
{
    public HeaterGroup(string name, IEnumerable<string> macs)
        : this(Guid.NewGuid().ToString("N"), name, macs.ToArray()) { }
}
