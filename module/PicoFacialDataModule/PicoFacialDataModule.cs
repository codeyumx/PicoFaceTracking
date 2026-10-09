using Microsoft.Extensions.Logging;
using PicoFacialDataModule.PicoFacialModule;
using PicoFacialDataModule.PicoFacialModule.Exceptions;
using VRCFaceTracking;

namespace PicoFacialDataModule
{
    public class PicoFacialDataModule : ExtTrackingModule
    {
        private const int PORT = 9030;
        /// <summary>How long Initialize waits for the headset before leaving eye and face tracking to other modules.</summary>
        private static readonly TimeSpan HeadsetWaitTime = TimeSpan.FromSeconds(180);

#pragma warning disable CS8618 // Because we didn't initialize in the constructor it is WHINING!
        /// <summary>Replaced when a headset pairs by code; Update then continues with the new one.</summary>
        private volatile IPicoFacialModuleConnector _picoFacialModuleConnector;
        private PairingListener? _pairingListener;
        /// <summary>The connector Initialize already established; the first Update receives from it right away.</summary>
        private IPicoFacialModuleConnector? _establishedConnector;

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

                // Only claim what no other module has claimed already.
                var claimEye = eyeAvailable && !_moduleSettings.DisableEyeTracking;
                var claimExpression = expressionAvailable && !_moduleSettings.DisableFaceTracking;
                if (!claimEye && !claimExpression)
                    return (false, false);

                _picoFacialModuleConnector = CreateConnector(Pairing.Load());
                _pairingListener = new PairingListener(PORT, _moduleSettings.IP, Logger, OnPaired);

                Logger.LogInformation($"Waiting up to {HeadsetWaitTime.TotalSeconds:0} seconds for the headset.");
                var headset = HeadsetWait.Establish(() => _picoFacialModuleConnector, HeadsetWaitTime);
                if (headset == null)
                {
                    Logger.LogWarning($"No headset answered within {HeadsetWaitTime.TotalSeconds:0} seconds, so eye and face tracking stay free for other modules. " +
                        "To use the PICO: turn on tracking in the Pico Face Tracking app, then restart VRCFaceTracking.");
                    Teardown();
                    return (false, false);
                }

                Logger.LogInformation($"Connection established to: {headset.Address}");
                _establishedConnector = _picoFacialModuleConnector;
                return (claimEye, claimExpression);
            } catch (Exception e)
            {
                Logger.LogCritical($"Initialization failed with the following message: {e.Message}\n Stacktrace:\n{e.StackTrace}");
                Teardown();
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

            var connector = _picoFacialModuleConnector;
            try
            {
                if (connector != _establishedConnector)
                {
                    Logger.LogInformation("Establishing");
                    var IP = connector.EstablishAsync().GetAwaiter().GetResult();

                    Logger.LogInformation($"Connection established to: {IP.Address}");
                }
                _establishedConnector = null;
            } catch (Exception e)
            {
                // A headset paired by code: the old connector was closed under us.
                if (connector != _picoFacialModuleConnector)
                    return;
                Goodnight(e);
            }

            try
            {
                while (true)
                {
                    var trackingResult = new TrackingResult();
                    connector.ReceiveAsync(trackingResult).GetAwaiter().GetResult();

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
                if (connector != _picoFacialModuleConnector)
                    return;
                Goodnight(e);
            }
        }

        public override void Teardown()
        {
            _pairingListener?.Dispose();
            _pairingListener = null;
            _picoFacialModuleConnector?.Dispose();
        }

        /// <summary>
        /// With a pairing key: protocol version 2, which finds the paired headset on any address and encrypts the data.
        /// Without one: the original protocol.
        /// </summary>
        private IPicoFacialModuleConnector CreateConnector(Pairing? pairing)
        {
            if (pairing == null)
                return new PicoFacialModuleConnector(PORT, _moduleSettings.IP);

            Logger.LogInformation($"Paired by key ({Pairing.KeyPath}), using protocol version 2.");
            return new PairedConnector(PORT, _moduleSettings.IP, pairing);
        }

        /// <summary>
        /// A headset paired by code: save the key and switch to it without a restart. Disposing the old connector
        /// ends its pending EstablishAsync or ReceiveAsync, so Update returns and continues with the new one.
        /// </summary>
        private void OnPaired(byte[] key)
        {
            var replaced = _picoFacialModuleConnector;
            _picoFacialModuleConnector = CreateConnector(Pairing.Save(key));
            try
            {
                replaced.Dispose();
            }
            catch (Exception e)
            {
                // The socket is closed anyway; only the STOP to the headset failed.
                Logger.LogDebug($"Closing the previous connection: {e.Message}");
            }
        }

        private void Goodnight(Exception e)
        {
            Logger.LogCritical($"The module failed with the following exception: {e.Message}\n Stacktrace:\n{e.StackTrace}");

            // Good night!
            Thread.Sleep(Timeout.Infinite);
        }
    }
}
