using PicoFacialDataModule.PicoFacialModule.Models;

namespace PicoFacialDataModule.PicoFacialModule.Payload
{
    public interface IPicoFacialDataPayload
    {
        /// <summary>
        /// To check if the payload is valid.
        /// </summary>
        /// <param name="buffer"></param>
        /// <returns></returns>
        bool IsPayloadValid(byte[] buffer);

        /// <summary>
        /// Extracts face tracking data from received buffer.
        /// </summary>
        /// <returns>If reading the data succeeded.</returns>
        bool GetFaceTrackingData(byte[] buffer, out PicoFTInfo picoFT);

        /// <summary>
        /// Extracts eye tracking data from received buffer.
        /// </summary>
        /// <returns>If reading the data succeeded.</returns>
        bool GetEyeTrackingData(byte[] buffer, out PxrEyePoseDataV2 eyePoseDataV2);
    }
}