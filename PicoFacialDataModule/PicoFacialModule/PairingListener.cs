using Microsoft.Extensions.Logging;
using System.Net;
using System.Net.Sockets;

namespace PicoFacialDataModule.PicoFacialModule
{
    /// <summary>
    /// Lets a headset pair by code (docs/protocol-v2.md, "Pairing"): asks the network every 2 seconds whether a headset
    /// wants to pair, shows the code for it in the log and saves the new key when the user typed the code on the headset.
    /// Runs for as long as the module, next to the tracking connector.
    /// </summary>
    public sealed class PairingListener : IDisposable
    {
        private const string MULTICAST_ADDRESS = "239.255.255.250";
        /// <summary>Windows: do not fail the next receive because an earlier datagram was answered with ICMP port unreachable.</summary>
        private const int SIO_UDP_CONNRESET = -1744830452;
        private const int MaxFailures = 5;
        private static readonly TimeSpan Round = TimeSpan.FromSeconds(2);
        private static readonly TimeSpan AttemptLifetime = TimeSpan.FromMinutes(5);
        private static readonly TimeSpan PauseAfterWrongCode = TimeSpan.FromSeconds(10);

        private readonly UdpClient _udpClient;
        private readonly IPEndPoint _endpoint;
        private readonly ILogger _logger;
        private readonly Action<byte[]> _onPaired;
        private readonly CancellationTokenSource _stop = new();
        private readonly Task _task;

        private PairingExchange? _attempt;
        private IPEndPoint? _attemptHeadset;
        private string _attemptHeadsetName = "";
        private DateTime _attemptStarted;
        /// <summary>After pairing: the RESPONSE and the CONFIRM sent for it, repeated if the headset sends it again.</summary>
        private byte[]? _pairedResponse;
        private byte[]? _pairedReply;
        private int _failures;
        private DateTime _pausedUntil;

        public PairingListener(int port, string? ip, ILogger logger, Action<byte[]> onPaired)
        {
            _udpClient = new UdpClient(0)
            {
                EnableBroadcast = true,
                MulticastLoopback = false,
            };
            if (OperatingSystem.IsWindows())
                _udpClient.Client.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null);

            _endpoint = new IPEndPoint(string.IsNullOrEmpty(ip) ? IPAddress.Parse(MULTICAST_ADDRESS) : IPAddress.Parse(ip), port);
            _logger = logger;
            _onPaired = onPaired;
            _task = Task.Run(RunAsync);
        }

        private async Task RunAsync()
        {
            var discover = PairingExchange.CreateDiscover();
            while (!_stop.IsCancellationRequested)
            {
                try
                {
                    foreach (var networkIP in Dns.GetHostAddresses(Dns.GetHostName()).Where(ip => ip.AddressFamily == AddressFamily.InterNetwork))
                    {
                        _udpClient.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, networkIP.GetAddressBytes());
                        await _udpClient.SendAsync(discover, discover.Length, _endpoint);
                    }

                    using var round = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
                    round.CancelAfter(Round);
                    while (true)
                    {
                        var result = await _udpClient.ReceiveAsync(round.Token);
                        await HandleAsync(result.Buffer, result.RemoteEndPoint);
                    }
                }
                catch (OperationCanceledException)
                {
                    // Next round.
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                catch (SocketException e)
                {
                    // For example no network yet; try again next round.
                    _logger.LogDebug($"Pairing: {e.Message}");
                    try
                    {
                        await Task.Delay(Round, _stop.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        return;
                    }
                }
            }
        }

        private async Task HandleAsync(byte[] message, IPEndPoint from)
        {
            if (!IsLocal(from.Address))
                return;

            switch (Pairing.TypeOf(message))
            {
                case PairingExchange.Offer:
                    if (!PairingExchange.TryParseOffer(message, out var headsetNonce, out var headsetName))
                        return;
                    if (_attempt != null && from.Equals(_attemptHeadset) && headsetNonce.AsSpan().SequenceEqual(_attempt.HeadsetNonce))
                    {
                        // The headset still waits for the code; it may have missed the START.
                        if (_pairedReply == null)
                            await _udpClient.SendAsync(_attempt.StartMessage, _attempt.StartMessage.Length, from);
                        return;
                    }
                    // One headset at a time; the same headset may start over.
                    if (_attempt != null && _pairedReply == null && !from.Address.Equals(_attemptHeadset!.Address)
                        && DateTime.UtcNow - _attemptStarted < AttemptLifetime)
                        return;
                    if (_failures >= MaxFailures || DateTime.UtcNow < _pausedUntil)
                        return;

                    _attempt = new PairingExchange(headsetNonce, Environment.MachineName);
                    _attemptHeadset = from;
                    _attemptHeadsetName = headsetName;
                    _attemptStarted = DateTime.UtcNow;
                    _pairedResponse = null;
                    _pairedReply = null;
                    _logger.LogInformation($"Pairing code for {headsetName} ({from.Address}): {_attempt.Code[..3]} {_attempt.Code[3..]}. Type it in Pico Face Tracking on the headset.");
                    await _udpClient.SendAsync(_attempt.StartMessage, _attempt.StartMessage.Length, from);
                    return;

                case PairingExchange.Response:
                    if (_attempt == null || !from.Equals(_attemptHeadset))
                        return;
                    if (_pairedResponse != null)
                    {
                        // The headset did not get the CONFIRM.
                        if (message.AsSpan().SequenceEqual(_pairedResponse))
                            await _udpClient.SendAsync(_pairedReply!, _pairedReply!.Length, from);
                        return;
                    }

                    var outcome = _attempt.Finish(message, out var key, out var reply);
                    if (outcome == PairingExchange.Outcome.NotForThis)
                        return;
                    await _udpClient.SendAsync(reply, reply.Length, from);

                    if (outcome == PairingExchange.Outcome.WrongCode)
                    {
                        _attempt = null;
                        _failures++;
                        _pausedUntil = DateTime.UtcNow + PauseAfterWrongCode;
                        _logger.LogWarning(_failures < MaxFailures
                            ? $"A wrong pairing code was typed on {_attemptHeadsetName} ({from.Address}). A new code follows in a few seconds."
                            : $"A wrong pairing code was typed {MaxFailures} times. Pairing is off until VRCFaceTracking restarts.");
                        return;
                    }

                    _pairedResponse = message;
                    _pairedReply = reply;
                    _logger.LogInformation($"Paired with {_attemptHeadsetName} ({from.Address}). The pairing key is saved in {Pairing.KeyPath}.");
                    _onPaired(key);
                    return;
            }
        }

        /// <summary>Private and link-local IPv4 addresses: pairing stays within the local network.</summary>
        private static bool IsLocal(IPAddress address)
        {
            if (address.IsIPv4MappedToIPv6)
                address = address.MapToIPv4();
            if (address.AddressFamily != AddressFamily.InterNetwork)
                return false;
            var b = address.GetAddressBytes();
            return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
        }

        public void Dispose()
        {
            _stop.Cancel();
            _udpClient.Dispose();
            try
            {
                _task.Wait(TimeSpan.FromSeconds(2));
            }
            catch (AggregateException)
            {
                // Already logged or irrelevant while shutting down.
            }
            _stop.Dispose();
        }
    }
}
