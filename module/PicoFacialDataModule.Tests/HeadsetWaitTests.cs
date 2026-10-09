using System.Diagnostics;
using System.Net;
using PicoFacialDataModule.PicoFacialModule;
using Xunit;

namespace PicoFacialDataModule.Tests
{
    /// <summary>
    /// The module claims eye and face tracking only when a headset answered in time; otherwise VRCFaceTracking would
    /// keep them for a switched-off PICO and ignore another headset's module.
    /// </summary>
    public class HeadsetWaitTests
    {
        [Fact]
        public void NoHeadsetAnsweringGivesUpAfterTheTimeout()
        {
            var connector = new FakeConnector();
            var clock = Stopwatch.StartNew();

            var headset = HeadsetWait.Establish(() => connector, TimeSpan.FromMilliseconds(300));

            Assert.Null(headset);
            Assert.InRange(clock.ElapsedMilliseconds, 250, 5000);
        }

        [Fact]
        public void PairingByCodeDuringTheWaitContinuesWithTheNewConnector()
        {
            var old = new FakeConnector();
            var current = old;
            var paired = new FakeConnector();
            var headsetAddress = new IPEndPoint(IPAddress.Parse("192.0.2.7"), 9030);

            _ = Task.Run(async () =>
            {
                await Task.Delay(200);
                current = paired;
                old.Dispose();
                await Task.Delay(200);
                paired.Answer(headsetAddress);
            });

            var headset = HeadsetWait.Establish(() => current, TimeSpan.FromSeconds(10));

            Assert.Equal(headsetAddress, headset);
        }

        [Fact]
        public void AFailingSearchIsReportedNotTakenForNoHeadset()
        {
            var connector = new FakeConnector();
            _ = Task.Run(async () =>
            {
                await Task.Delay(100);
                connector.Fail(new InvalidOperationException("network down"));
            });

            var error = Record.Exception(() => HeadsetWait.Establish(() => connector, TimeSpan.FromSeconds(10)));

            Assert.IsType<InvalidOperationException>(error);
        }

        /// <summary>Searches until answered, failed or disposed, like the real connectors.</summary>
        private sealed class FakeConnector : IPicoFacialModuleConnector
        {
            private readonly TaskCompletionSource<IPEndPoint> _search = new(TaskCreationOptions.RunContinuationsAsynchronously);

            public void Answer(IPEndPoint headset) => _search.TrySetResult(headset);
            public void Fail(Exception error) => _search.TrySetException(error);

            public Task<IPEndPoint> EstablishAsync() => _search.Task;
            public Task ReceiveAsync(TrackingResult trackingResult) => throw new NotSupportedException();
            public void Dispose() => _search.TrySetException(new ObjectDisposedException(nameof(FakeConnector)));
        }
    }
}
