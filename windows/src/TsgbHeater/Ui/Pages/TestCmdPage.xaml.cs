using System.Windows;
using System.Windows.Controls;
using TsgbHeater.Ble;
using TsgbHeater.Services;

namespace TsgbHeater.Ui.Pages;

public partial class TestCmdPage : UserControl
{
    public TestCmdPage() => InitializeComponent();

    private async void OnSend(object sender, RoutedEventArgs e)
    {
        try
        {
            var bytes = FrameCodec.FromHex(HexBox.Text);
            var ok = await ServiceLocator.Ble.WriteAsync(bytes);
            ResultLine.Text = ok ? $"Sent {bytes.Length} B" : "Send failed — not connected?";
        }
        catch (Exception ex)
        {
            ResultLine.Text = $"Bad hex: {ex.Message}";
        }
    }

    private void OnComputeCrc(object sender, RoutedEventArgs e)
    {
        try
        {
            var bytes = FrameCodec.FromHex(HexBox.Text);
            if (bytes.Length < 6)
            {
                ResultLine.Text = "Need at least 6 bytes (AA len op d0 d1 d2)";
                return;
            }
            int crc = Crc16Xmodem(bytes, 0, 6);
            byte hi = (byte)((crc >> 8) & 0xFF);
            byte lo = (byte)(crc & 0xFF);
            var fixedBytes = bytes.Take(6).Concat(new[] { hi, lo }).ToArray();
            HexBox.Text = FrameCodec.Hex(fixedBytes);
            ResultLine.Text = "CRC updated";
        }
        catch (Exception ex)
        {
            ResultLine.Text = $"Bad hex: {ex.Message}";
        }
    }

    private static int Crc16Xmodem(byte[] buf, int offset, int len)
    {
        int[] table = { 0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
                        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF };
        int crc = 0;
        for (int i = 0; i < len; i++)
        {
            int b  = buf[offset + i] & 0xFF;
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            crc = ((crc << 4) & 0xFFFF) ^ table[((crc >> 12) & 0x0F) ^ hi];
            crc = ((crc << 4) & 0xFFFF) ^ table[((crc >> 12) & 0x0F) ^ lo];
        }
        return crc & 0xFFFF;
    }
}
