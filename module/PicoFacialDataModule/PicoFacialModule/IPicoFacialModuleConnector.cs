using System.Net;

namespace PicoFacialDataModule.PicoFacialModule
{
    public interface IPicoFacialModuleConnector : IDisposable
    {
        Task<IPEndPoint> EstablishAsync(CancellationToken cancellationToken);
        /// <summary>
        /// Waits for the next packet from the headset: a frame fills <paramref name="trackingResult"/>; a ping is answered
        /// and leaves it empty, so the caller notices a headset that stays connected but sends no frames.
        /// </summary>
        /// <exception cref="Exceptions.ClientDiedException">Nothing arrived for 2 seconds, or the connector was closed.</exception>
        Task ReceiveAsync(TrackingResult trackingResult);
    }
}