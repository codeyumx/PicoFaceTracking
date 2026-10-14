using System.Net;

namespace PicoFacialDataModule.PicoFacialModule
{
    public interface IPicoFacialModuleConnector : IDisposable
    {
        Task<IPEndPoint> EstablishAsync(CancellationToken cancellationToken);
        Task ReceiveAsync(TrackingResult trackingResult);
    }
}