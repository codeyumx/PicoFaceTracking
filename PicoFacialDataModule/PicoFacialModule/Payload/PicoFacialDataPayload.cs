using PicoFacialDataModule.PicoFacialModule.Models;
using System.Runtime.InteropServices;

namespace PicoFacialDataModule.PicoFacialModule.Payload
{
    enum PicoFacialDataPayloadV1
    {
        FT_INFO_START,
        PXR_EYE_POSE_START = 384,
        PXR_EYE_POSE_END = PXR_EYE_POSE_START + 152
    }

    enum PicoFacialDataPayloadV2
    {
        FT_INFO_START,
        PXR_EYE_POSE_START = 384,
        PXR_EYE_POSE_END = PXR_EYE_POSE_START + 200
    }
    public class PicoFacialDataPayload : IPicoFacialDataPayload
    {
        public bool IsPayloadValid(byte[] buffer)
        {
            return buffer.Length == (int)PicoFacialDataPayloadV1.PXR_EYE_POSE_END;
        }

        public bool GetFaceTrackingData(byte[] buffer, out PicoFTInfo picoFT)
        {
            return MemoryMarshal.TryRead(buffer.AsSpan()[(int)PicoFacialDataPayloadV1.FT_INFO_START..(int)PicoFacialDataPayloadV1.PXR_EYE_POSE_START], out picoFT);
        }

        public bool GetEyeTrackingData(byte[] buffer, out PxrEyePoseDataV2 eyePoseDataV2)
        {
            return MemoryMarshal.TryRead(buffer.AsSpan()[(int)PicoFacialDataPayloadV1.PXR_EYE_POSE_START..], out eyePoseDataV2);
        }
    }
}
