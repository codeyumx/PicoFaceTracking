using Microsoft.Extensions.Logging;
using PicoFacialDataModule.PicoFacialModule;
using PicoFacialDataModule.PicoFacialModule.Exceptions;
using VRCFaceTracking;
using VRCFaceTracking.Core.Library;
using VRCFaceTracking.Core.Params.Expressions;

namespace PicoFacialDataModule
{
    public class PicoFacialDataModule : ExtTrackingModule
    {
        private const int PORT = 9030;
        /// <summary>
        /// Upper bound for the HeadsetWaitSeconds setting. Initialize blocks VRCFaceTracking's module process, which
        /// kills itself after 60 seconds without a packet from VRCFaceTracking.
        /// </summary>
        private const int MaxHeadsetWaitSeconds = 50;
        /// <summary>One discovery round of Update; Update returns after it so VRCFaceTracking can stop the module.</summary>
        private static readonly TimeSpan DiscoveryRound = TimeSpan.FromSeconds(2);
        private static readonly TimeSpan IdlePoll = TimeSpan.FromMilliseconds(500);

        private readonly object _connectorLock = new();
        /// <summary>
        /// Null while switched off on VRCFaceTracking's home page and after Teardown. Replaced when a headset pairs by
        /// code; Update then continues with the new one.
        /// </summary>
        private volatile IPicoFacialModuleConnector? _picoFacialModuleConnector;
        /// <summary>The connector that found a headset and receives from it; any other one has to discover first.</summary>
        private IPicoFacialModuleConnector? _establishedConnector;
        private PairingListener? _pairingListener;
        private bool _eyeClaimed;
        private bool _expressionClaimed;
        private bool _searching;
        private volatile bool _tornDown;
        /// <summary>Set by <see cref="Goodnight"/>: the module stops working until VRCFaceTracking reloads it.</summary>
        private volatile bool _failed;

#pragma warning disable CS8618 // Because we didn't initialize in the constructor it is WHINING!
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
                ModuleInformation.Name = "Pico 4 Face Tracking";
                ModuleInformation.Active = true;

                var stream = GetType().Assembly.GetManifestResourceStream("PicoFacialDataModule.Assets.icon.png");

                ModuleInformation.StaticImages = stream != null ? new List<Stream> { stream } : ModuleInformation.StaticImages;

                _moduleSettings = SettingsManager.GetOrCreate();

                _faceTrackingParser = new FaceTrackingParser();
                _eyeTrackingParser = new EyeTrackingParser(_moduleSettings);

                // Only claim what no other module has claimed already.
                var eye = eyeAvailable && !_moduleSettings.DisableEyeTracking;
                var expression = expressionAvailable && !_moduleSettings.DisableFaceTracking;
                if (!eye && !expression)
                    return (false, false);

                _picoFacialModuleConnector = CreateConnector(Pairing.Load());
                _pairingListener = new PairingListener(PORT, _moduleSettings.IP, Logger, OnPaired);

                // VRCFaceTracking hands eye and face tracking to the first module that claims them and never moves them
                // to another module later, so claim them only for a headset that is there.
                if (!_moduleSettings.ClaimWithoutHeadset && !FindHeadset())
                {
                    if (_tornDown)
                        return (false, false);
                    Logger.LogInformation($"No PICO headset answered within {HeadsetWait.TotalSeconds:0} seconds, so eye and face tracking are left to other modules. " +
                        "To use the PICO: turn tracking on in Pico Face Tracking on the headset, then restart VRCFaceTracking.");
                    Teardown();
                    return (false, false);
                }

                _eyeClaimed = eye;
                _expressionClaimed = expression;
                return (eye, expression);
            } catch (Exception e)
            {
                Logger.LogCritical($"Initialization failed with the following message: {e.Message}\n Stacktrace:\n{e.StackTrace}");
                Teardown();
                return (false, false);
            }
        }

        /// <summary>
        /// Handles one discovery round or one frame, so it returns within about 2 seconds: VRCFaceTracking stops a
        /// module by waiting for Update to return.
        /// </summary>
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

            // Declined in Initialize, failed, or torn down: nothing to do until VRCFaceTracking stops calling.
            if (_tornDown || _failed || (!_eyeClaimed && !_expressionClaimed))
            {
                Thread.Sleep(IdlePoll);
                return;
            }

            // Switched off on VRCFaceTracking's home page: stop the headset, so it releases its cameras.
            if (Status == ModuleState.Idle)
            {
                StopStreaming();
                Thread.Sleep(IdlePoll);
                return;
            }

            IPicoFacialModuleConnector? connector;
            lock (_connectorLock)
            {
                if (_tornDown)
                    return;
                connector = _picoFacialModuleConnector ??= CreateConnector(Pairing.Load());
            }

            if (connector != _establishedConnector)
            {
                Establish(connector);
                return;
            }

            try
            {
                var trackingResult = new TrackingResult();
                connector.ReceiveAsync(trackingResult).GetAwaiter().GetResult();

                if (trackingResult.FaceData.HasValue)
                    _faceTrackingParser.Parse(trackingResult.FaceData.Value);

                if (trackingResult.EyeData.HasValue && trackingResult.FaceData.HasValue)
                    _eyeTrackingParser.Parse(trackingResult.EyeData.Value, trackingResult.FaceData.Value);
            }
#if !DEBUG
            catch (ClientIncorrectDataException)
            {
                //Noop
            }
#endif
            catch (ClientDiedException)
            {
                // Also when the connector was closed under us: replaced by pairing, switched off or torn down.
                _establishedConnector = null;
                if (connector == _picoFacialModuleConnector)
                    Logger.LogInformation("The headset stopped sending, the face and eyes are neutral until it is back.");
                ResetToNeutral();
            } catch (Exception e)
            {
                if (connector != _picoFacialModuleConnector)
                    return;
                Goodnight(e);
            }
        }

        public override void Teardown()
        {
            _tornDown = true;
            _pairingListener?.Dispose();
            _pairingListener = null;
            CloseConnector();
        }

        private TimeSpan HeadsetWait => TimeSpan.FromSeconds(Math.Clamp(_moduleSettings.HeadsetWaitSeconds, 0, MaxHeadsetWaitSeconds));

        /// <summary>
        /// Waits up to <see cref="HeadsetWait"/> for a headset to answer the discovery or to offer to pair. Other modules
        /// run in their own processes, so a headset they find claims tracking meanwhile without waiting for this.
        /// </summary>
        private bool FindHeadset()
        {
            var connector = _picoFacialModuleConnector!;
            var wait = HeadsetWait;
            Logger.LogInformation($"Waiting up to {wait.TotalSeconds:0} seconds for a PICO headset with tracking switched on.");
            using var probe = new CancellationTokenSource(wait);
            var establish = connector.EstablishAsync(probe.Token);
            // Teardown closes the connector, which ends the wait early.
            Task.WaitAny(new[] { establish, _pairingListener!.HeadsetOffered }, wait);
            if (_tornDown)
            {
                probe.Cancel();
                return false;
            }

            if (establish.IsCompletedSuccessfully)
            {
                _establishedConnector = connector;
                Logger.LogInformation($"Connection established to: {establish.Result.Address}");
                return true;
            }

            // Update discovers again with this connector; it must not still be listening.
            probe.Cancel();
            try
            {
                establish.GetAwaiter().GetResult();
            }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException)
            {
                // Cancelled above, or closed because the headset paired by code meanwhile.
            }
            catch (Exception e)
            {
                Logger.LogDebug($"Looking for the headset: {e.Message}");
            }

            // A headset on its pairing screen does not answer the discovery, and one that paired replaced the connector.
            return _pairingListener.HeadsetOffered.IsCompleted || connector != _picoFacialModuleConnector;
        }

        private void Establish(IPicoFacialModuleConnector connector)
        {
            if (!_searching)
            {
                Logger.LogInformation("Establishing");
                _searching = true;
            }

            try
            {
                using var round = new CancellationTokenSource(DiscoveryRound);
                var IP = connector.EstablishAsync(round.Token).GetAwaiter().GetResult();

                _establishedConnector = connector;
                _searching = false;
                Logger.LogInformation($"Connection established to: {IP.Address}");
            } catch (OperationCanceledException)
            {
                // No headset this round; the next Update asks again.
            } catch (Exception e)
            {
                // Closed under us: replaced by pairing, switched off or torn down.
                if (connector != _picoFacialModuleConnector)
                    return;
                Goodnight(e);
            }
        }

        /// <summary>Sends STOP to the headset and closes the connection; Update opens a new one once switched on again.</summary>
        private void StopStreaming()
        {
            if (CloseConnector())
            {
                Logger.LogInformation("Switched off, the headset stopped tracking.");
                ResetToNeutral();
            }
        }

        private bool CloseConnector()
        {
            IPicoFacialModuleConnector? closed;
            lock (_connectorLock)
            {
                closed = _picoFacialModuleConnector;
                _picoFacialModuleConnector = null;
            }
            if (closed == null)
                return false;

            _establishedConnector = null;
            _searching = false;
            DisposeQuietly(closed);
            return true;
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
            var pairing = Pairing.Save(key);
            IPicoFacialModuleConnector? replaced;
            lock (_connectorLock)
            {
                replaced = _picoFacialModuleConnector;
                // Switched off or torn down: the saved key is used once Update opens a connection again.
                if (replaced == null)
                    return;
                _picoFacialModuleConnector = CreateConnector(pairing);
            }
            DisposeQuietly(replaced);
        }

        private void DisposeQuietly(IPicoFacialModuleConnector connector)
        {
            try
            {
                connector.Dispose();
            }
            catch (Exception e)
            {
                // The socket is closed anyway; only the STOP to the headset failed.
                Logger.LogDebug($"Closing the previous connection: {e.Message}");
            }
        }

        /// <summary>
        /// Relaxed face and open eyes, looking ahead, instead of the last frame frozen while no headset sends. Only the
        /// parts this module claimed, as VRCFaceTracking's eye and face split them.
        /// </summary>
        private void ResetToNeutral()
        {
            var data = UnifiedTracking.Data;
            if (_eyeClaimed)
            {
                data.Eye.Left.Gaze.x = data.Eye.Left.Gaze.y = 0f;
                data.Eye.Right.Gaze.x = data.Eye.Right.Gaze.y = 0f;
                data.Eye.Left.Openness = data.Eye.Right.Openness = 1f;
                for (var i = 0; i <= (int)UnifiedExpressions.BrowOuterUpLeft; i++)
                    data.Shapes[i].Weight = 0f;
            }
            if (_expressionClaimed)
            {
                for (var i = (int)UnifiedExpressions.BrowOuterUpLeft + 1; i < data.Shapes.Length; i++)
                    data.Shapes[i].Weight = 0f;
            }
        }

        private void Goodnight(Exception e)
        {
            Logger.LogCritical($"The module failed with the following exception: {e.Message}\n Stacktrace:\n{e.StackTrace}");

            // Good night! Update idles from now on, but still returns, so VRCFaceTracking can stop the module.
            _failed = true;
        }
    }
}
