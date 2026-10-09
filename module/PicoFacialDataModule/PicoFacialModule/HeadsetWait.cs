using System.Diagnostics;
using System.Net;

namespace PicoFacialDataModule.PicoFacialModule
{
    /// <summary>
    /// VRCFaceTracking gives eye and face tracking to the first module whose Initialize claims them, and keeps them
    /// there even when that module is switched off on its page. So the module claims them only once a headset answered,
    /// and another headset's module (for example a Steam Frame's) gets them while the PICO is off.
    /// </summary>
    public static class HeadsetWait
    {
        /// <summary>
        /// Establishes with the current connector until <paramref name="timeout"/> ends. A connector replaced meanwhile
        /// (pairing by code) ends its search with an exception; the wait then continues with the new one.
        /// </summary>
        /// <returns>The headset, or null when none answered in time. Its search still runs then: dispose the connector.</returns>
        public static IPEndPoint? Establish(Func<IPicoFacialModuleConnector> current, TimeSpan timeout)
        {
            var clock = Stopwatch.StartNew();
            while (true)
            {
                var connector = current();
                var left = timeout - clock.Elapsed;
                if (left <= TimeSpan.Zero)
                    return null;

                try
                {
                    return connector.EstablishAsync().WaitAsync(left).GetAwaiter().GetResult();
                }
                catch (Exception) when (connector != current())
                {
                    // Replaced by a pairing by code: search with the new connector.
                }
                catch (TimeoutException)
                {
                    return null;
                }
            }
        }
    }
}
