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
            var search = connector.EstablishAsync();
            await Task.Delay(300);

            connector.Dispose();

            var ended = await Record.ExceptionAsync(() => search.WaitAsync(TimeSpan.FromSeconds(5)));
            Assert.NotNull(ended);
            Assert.IsNotType<TimeoutException>(ended);
            using var reused = new UdpClient(port);
        }

        private static int FreePort()
        {
            using var probe = new UdpClient(0);
            return ((IPEndPoint)probe.Client.LocalEndPoint!).Port;
        }
    }
}
