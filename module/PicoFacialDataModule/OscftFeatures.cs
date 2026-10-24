using System.Runtime.Loader;
using FaceBridge.Sdk;
using Microsoft.Extensions.Logging;

namespace PicoFacialDataModule
{
    /// <summary>
    /// OSCFT for VRC (FaceBridge) only: the module's Module settings panel (read-only Headset and Pairing text) and
    /// notifications, through FaceBridge.Sdk. Separate from PicoFacialDataModule.json (see SettingsManager), which both
    /// apps read. In VRCFaceTracking the panel is not shown and notifications become log entries, so everything shown
    /// here is also logged (see PairingListener and OnHeadsetModel).
    /// Construct only after <see cref="FaceBridgeSdkLoader.Register"/>.
    /// </summary>
    internal sealed class OscftFeatures
    {
        private const string PairingField = "pairing";
        private const string HeadsetField = "headset";
        private const string PortNotification = "port";
        private const string ReceivingNotification = "receiving";

        /// <summary>FaceBridge.Sdk's settings (OSCFT panel), not the module's own JSON <see cref="PicoFacialDataModule.ModuleSettings"/>.</summary>
        private readonly FaceBridge.Sdk.ModuleSettings _settings;
        private readonly ILogger _logger;

        public OscftFeatures(ILogger logger, bool paired)
        {
            _logger = logger;
            _settings = new FaceBridge.Sdk.ModuleSettings(typeof(OscftFeatures).Assembly)
                .AddText(HeadsetField, "Headset", paired
                    ? "Not reported yet. If this stays after tracking starts, update Pico Face Tracking on the headset."
                    : "Unknown: the headset only reports its model once paired by code.")
                .AddText(PairingField, "Pairing", paired
                    ? "Paired. To pair again, press \"Pair by code\" in Pico Face Tracking on the headset."
                    : "Not paired. To pair, press \"Pair by code\" in Pico Face Tracking on the headset.");
            _settings.Declare();
        }

        /// <summary>Replaces the pairing text in the settings panel, for example with the code to type on the headset.</summary>
        public void ShowPairingStatus(string text) => _settings.SetText(PairingField, text);

        /// <summary>The model the headset reported, for example "PICO 4 Pro".</summary>
        public void ShowHeadsetModel(string model) => _settings.SetText(HeadsetField, model);

        public void ShowPortInUse(int port) => UserNotifications.Show(_logger, LogLevel.Error,
            $"UDP port {port} is used by another program, so the module cannot receive tracking from the headset. " +
            "Close that program (for example another Pico module) and restart the modules.",
            PortNotification);

        /// <summary>The port is open now: removes a notification left from an earlier start.</summary>
        public void ClearPortInUse() => UserNotifications.Clear(PortNotification);

        /// <summary>The first frame of a connection arrived; keyed, so a reconnect replaces it.</summary>
        public void ShowReceiving() => UserNotifications.Show(_logger, LogLevel.Information,
            "Receiving data from Pico 4 headset", ReceivingNotification);
    }

    /// <summary>
    /// VRCFaceTracking loads a module into its own AssemblyLoadContext without looking in the module's folder for its
    /// dependencies, so it would not find FaceBridge.Sdk.dll there. OSCFT for VRC hands the module its own copy
    /// before this handler is asked.
    /// </summary>
    internal static class FaceBridgeSdkLoader
    {
        private const string SdkName = "FaceBridge.Sdk";

        public static void Register()
        {
            var assembly = typeof(FaceBridgeSdkLoader).Assembly;
            var context = AssemblyLoadContext.GetLoadContext(assembly);
            if (context == null || string.IsNullOrEmpty(assembly.Location))
                return;

            var path = Path.Combine(Path.GetDirectoryName(assembly.Location)!, SdkName + ".dll");
            context.Resolving += (ctx, name) => name.Name == SdkName && File.Exists(path) ? ctx.LoadFromAssemblyPath(path) : null;
        }
    }
}
