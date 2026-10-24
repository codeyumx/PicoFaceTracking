using System.Net;
using System.Net.Sockets;
using PicoFacialDataModule.PicoFacialModule;
using Xunit;

namespace PicoFacialDataModule.Tests
{
    /// <summary>
    /// Pairing by code replaces the connector while it still searches for a headset: disposing it must end that search
    /// and release its port, or the module keeps using the old protocol until VRCFaceTracking restarts.
    /// </summary>
    public class ConnectorTests
    {
        /// <summary>TEST-NET-1: nothing answers there.</summary>
        private const string Nobody = "192.0.2.1";

        [Fact]
        public async Task OriginalConnectorDisposedBeforeAHeadsetAnsweredStopsSearchingAndFreesItsPort()
        {
            var port = FreePort();
            var connector = new PicoFacialModuleConnector(port, Nobody);
            var search = connector.EstablishAsync(CancellationToken.None);
            await Task.Delay(300);

            connector.Dispose();

            var ended = await Record.ExceptionAsync(() => search.WaitAsync(TimeSpan.FromSeconds(5)));
            Assert.NotNull(ended);
            Assert.IsNotType<TimeoutException>(ended);
            using var reused = new UdpClient(port);
        }

        /// <summary>
        /// A headset that stays connected but sends no frames (taken off, tracking paused) only pings. Each ping must be
        /// answered and end the receive, so the module keeps the connection and can show a neutral face, instead of
        /// dropping it after 2 seconds and searching while the headset still streams to the old connection.
        /// </summary>
        // The connector binds 0.0.0.0:port and the fake headset 127.0.0.2:port. Windows allows that; Linux (and macOS)
        // refuse the second bind with "Address already in use", so the test only runs on Windows (as in CI).
        [WindowsFact]
        public async Task OriginalConnectorAnswersPingsWithoutFramesAndStaysConnected()
        {
            var port = FreePort();
            using var connector = new PicoFacialModuleConnector(port, "127.0.0.2");
            // The headset: bound to its own loopback address, so it receives the DISCOVER the connector sends there.
            using var headset = new UdpClient(new IPEndPoint(IPAddress.Parse("127.0.0.2"), port));

            var establish = connector.EstablishAsync(CancellationToken.None);
            var discover = await headset.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(5));
            var ping = new byte[] { (byte)'M', (byte)'A', (byte)'R', (byte)'C', (byte)'O', 0 };
            await headset.SendAsync(ping, ping.Length, discover.RemoteEndPoint);
            await establish.WaitAsync(TimeSpan.FromSeconds(5));

            // Longer than the 2 second timeout in all, one ping a second.
            for (var i = 0; i < 4; i++)
            {
                if (i > 0)
                {
                    await Task.Delay(1000);
                    await headset.SendAsync(ping, ping.Length, discover.RemoteEndPoint);
                }
                var result = new TrackingResult();
                await connector.ReceiveAsync(result).WaitAsync(TimeSpan.FromSeconds(5));
                Assert.False(result.FaceData.HasValue);

                // The connector sent one DISCOVER per network card; skip the rest of them.
                string reply;
                do
                    reply = System.Text.Encoding.UTF8.GetString((await headset.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(5))).Buffer);
                while (reply == "DISCOVER_DAEMON");
                Assert.Equal("POLO", reply);
            }
        }

        private static int FreePort()
        {
            using var probe = new UdpClient(0);
            return ((IPEndPoint)probe.Client.LocalEndPoint!).Port;
        }
    }

    /// <summary>A <see cref="FactAttribute"/> that is skipped outside Windows.</summary>
    public sealed class WindowsFactAttribute : FactAttribute
    {
        public WindowsFactAttribute()
        {
            if (!OperatingSystem.IsWindows())
                Skip = "Binds one port on 0.0.0.0 and 127.0.0.2, which only Windows allows.";
        }
    }
}
