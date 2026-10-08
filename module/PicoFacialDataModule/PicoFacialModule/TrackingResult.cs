using PicoFacialDataModule.PicoFacialModule.Models;

namespace PicoFacialDataModule.PicoFacialModule
{
    public class TrackingResult
    {
        public PicoFTInfo? FaceData;
        public PxrEyePoseDataV2? EyeData;
    }
}
