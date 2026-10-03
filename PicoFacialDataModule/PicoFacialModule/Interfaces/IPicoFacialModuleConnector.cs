using System.Net;

namespace PicoFacialDataModule.PicoFacialModule.Interfaces
{
    public interface IPicoFacialModuleConnector : IDisposable
    {
        Task<IPEndPoint> EstablishAsync();
        Task ReceiveAsync(TrackingResult trackingResult);
    }
}