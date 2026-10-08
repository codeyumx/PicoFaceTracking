using Microsoft.Extensions.Logging;
using PicoFacialDataModule.PicoFacialModule;
using PicoFacialDataModule.PicoFacialModule.Exceptions;
using VRCFaceTracking;

namespace PicoFacialDataModule
{
    public class PicoFacialDataModule : ExtTrackingModule
    {
        private const int PORT = 9030;

#pragma warning disable CS8618 // Because we didn't initialize in the constructor it is WHINING!
        private IPicoFacialModuleConnector _picoFacialModuleConnector;

        private FaceTrackingParser _faceTrackingParser;
        private EyeTrackingParser _eyeTrackingParser;
        private ModuleSettings _moduleSettings;
#pragma warning restore CS8618

#if EYEDEBUG || FACEDEBUG
        private int _consolePixels;
#endif

        public override (bool SupportsEye, bool SupportsExpression) Supported => (true, true);

        public override (bool eyeSuccess, bool expressionSuccess) Initialize(bool eyeAvailable, bool expressionAvailable)
        {
            try
            {
                ModuleInformation.Name = "Pico 4 P/E Facial Tracking Daemon";
                ModuleInformation.Active = true;

                var stream = GetType().Assembly.GetManifestResourceStream("PicoFacialDataModule.Assets.icon.png");

                ModuleInformation.StaticImages = stream != null ? new List<Stream> { stream } : ModuleInformation.StaticImages;

                _moduleSettings = SettingsManager.GetOrCreate();

                _faceTrackingParser = new FaceTrackingParser();
                _eyeTrackingParser = new EyeTrackingParser(_moduleSettings);

                // With a pairing key: protocol version 2, which finds the paired headset on any address and encrypts the data.
                var pairing = Pairing.Load();
                if (pairing != null)
                {
                    Logger.LogInformation($"Paired by key ({Pairing.KeyPath}), using protocol version 2.");
                    _picoFacialModuleConnector = new PairedConnector(PORT, _moduleSettings.IP, pairing);
                }
                else
                {
                    _picoFacialModuleConnector = new PicoFacialModuleConnector(PORT, _moduleSettings.IP);
                }

                // Only claim what no other module has claimed already.
                return (eyeAvailable && !_moduleSettings.DisableEyeTracking, expressionAvailable && !_moduleSettings.DisableFaceTracking);
            } catch (Exception e)
            {
                Logger.LogCritical($"Initialization failed with the following message: {e.Message}\n Stacktrace:\n{e.StackTrace}");
                return (false, false);
            }
        }

        public override void Update()
        {
#if EYEDEBUG || FACEDEBUG
            var currentConsolePixels = Console.WindowWidth + Console.WindowHeight;
            if (_consolePixels != currentConsolePixels)
            {
                Console.Clear();
                _consolePixels = currentConsolePixels;
            }
#endif

            if (!ModuleInformation.Active)
            {
                Thread.Sleep(500);
                return;
            }

            try
            {
                Logger.LogInformation("Establishing");
                var IP = _picoFacialModuleConnector.EstablishAsync().GetAwaiter().GetResult();

                Logger.LogInformation($"Connection established to: {IP.Address}");
            } catch (Exception e)
            {
                Goodnight(e);
            }

            try
            {
                while (true)
                {
                    var trackingResult = new TrackingResult();
                    _picoFacialModuleConnector.ReceiveAsync(trackingResult).GetAwaiter().GetResult();

                    if (trackingResult.FaceData.HasValue)
                        _faceTrackingParser.Parse(trackingResult.FaceData.Value);

                    if (trackingResult.EyeData.HasValue && trackingResult.FaceData.HasValue)
                        _eyeTrackingParser.Parse(trackingResult.EyeData.Value, trackingResult.FaceData.Value);
                }
            }
#if !DEBUG
            catch (ClientIncorrectDataException)
            {
                //Noop
            }
#endif
            catch (ClientDiedException)
            {
                //Noop
            } catch (Exception e)
            {
                Goodnight(e);
            }
        }

        public override void Teardown()
        {
            _picoFacialModuleConnector.Dispose();
        }

        private void Goodnight(Exception e)
        {
            Logger.LogCritical($"The module failed with the following exception: {e.Message}\n Stacktrace:\n{e.StackTrace}");

            // Good night!
            Thread.Sleep(Timeout.Infinite);
        }
    }
}
