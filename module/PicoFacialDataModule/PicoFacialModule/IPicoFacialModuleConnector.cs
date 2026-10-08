using System.Net;

namespace PicoFacialDataModule.PicoFacialModule
{
    public interface IPicoFacialModuleConnector : IDisposable
    {
        Task<IPEndPoint> EstablishAsync();
        Task ReceiveAsync(TrackingResult trackingResult);
    }
}