using PicoFacialDataModule.PicoFacialModule.Exceptions;
using PicoFacialDataModule.PicoFacialModule.Payload;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace PicoFacialDataModule.PicoFacialModule
{
    /// <summary>
    /// Protocol version 2 connector: finds the headset that holds the pairing key on any address, then receives
    /// encrypted frames. See docs/protocol-v2.md.
    /// </summary>
    public sealed class PairedConnector : IPicoFacialModuleConnector
    {
        private const string MULTICAST_ADDRESS = "239.255.255.250";
        /// <summary>Windows: do not fail the next receive because an earlier datagram was answered with ICMP port unreachable.</summary>
        private const int SIO_UDP_CONNRESET = -1744830452;

        private readonly UdpClient _udpClient;
        private readonly Pairing _pairing;
        private readonly int _port;
        private readonly string? _ip;
        private readonly IPicoFacialDataPayload _facialDataPayload = new PicoFacialDataPayload();
        /// <summary>One decrypted frame, the version 1 payload size the parsers accept.</summary>
        private readonly byte[] _frame = new byte[(int)PicoFacialDataPayloadV1.PXR_EYE_POSE_END];

        private IPEndPoint? _headset;
        private Pairing.Session? _session;

        public PairedConnector(int port, string? IP, Pairing pairing)
        {
            // Any local port: the headset answers the address and port the DISCOVER came from, so this module does not
            // compete for port 9030 with another Pico module.
            _udpClient = new UdpClient(0)
            {
                EnableBroadcast = true,
                MulticastLoopback = false,
            };
            // The headset closes its port while it pairs or restarts tracking; a STOP or PONG sent then must not end
            // the discovery with a connection reset.
            if (OperatingSystem.IsWindows())
                _udpClient.Client.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null);

            _port = port;
            _ip = IP;
            _pairing = pairing;
        }

        /// <summary>
        /// Sends a signed DISCOVER every 2 seconds until the headset answers with a valid HELLO, then confirms with START.
        /// </summary>
        public async Task<IPEndPoint> EstablishAsync()
        {
            _session?.Dispose();
            _session = null;
            _headset = null;

            IPEndPoint endpoint = new IPEndPoint(
                string.IsNullOrEmpty(_ip) ? IPAddress.Parse(MULTICAST_ADDRESS) : IPAddress.Parse(_ip),
                _port
            );

            // Get all network cards.
            var networkIPs = Dns.GetHostAddresses(Dns.GetHostName()).Where(ip => ip.AddressFamily == AddressFamily.InterNetwork);

            while (true)
            {
                var pcNonce = RandomNumberGenerator.GetBytes(Pairing.NonceLength);
                var discover = _pairing.CreateDiscover(pcNonce);

                foreach (var networkIP in networkIPs)
                {
                    _udpClient.Client.SetSocketOption(
                        SocketOptionLevel.IP,
                        SocketOptionName.MulticastInterface,
                        networkIP.GetAddressBytes()
                    );
                    await _udpClient.SendAsync(discover, discover.Length, endpoint);
                }

                using var cancellationToken = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try
                {
                    while (true)
                    {
                        var result = await _udpClient.ReceiveAsync(cancellationToken.Token);
                        if (!_pairing.VerifyHello(result.Buffer, pcNonce, out var headsetNonce))
                            continue;

                        var start = _pairing.CreateStart(pcNonce, headsetNonce);
                        await _udpClient.SendAsync(start, start.Length, result.RemoteEndPoint);

                        _session = _pairing.CreateSession(pcNonce, headsetNonce);
                        _headset = result.RemoteEndPoint;
                        return _headset;
                    }
                }
                catch (OperationCanceledException)
                {
                    // No headset answered; discover again with a fresh nonce.
                }
            }
        }

        /// <summary>
        /// Waits for the next frame from the headset, answering its pings meanwhile.
        /// </summary>
        public async Task ReceiveAsync(TrackingResult trackingResult)
        {
            while (true)
            {
                UdpReceiveResult result;
                try
                {
                    using var cancellationToken = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    result = await _udpClient.ReceiveAsync(cancellationToken.Token);
                }
                catch
                {
                    throw new ClientDiedException();
                }

                if (_session == null || !result.RemoteEndPoint.Equals(_headset))
                    continue;

                var type = _session.Open(result.Buffer, _frame, out var length);
                if (type == Pairing.Ping)
                {
                    var pong = _session.Seal(Pairing.Pong);
                    await _udpClient.SendAsync(pong, pong.Length, _headset);
                    continue;
                }
                if (type != Pairing.Data)
                    continue;

                if (length != _frame.Length || !_facialDataPayload.IsPayloadValid(_frame))
                    throw new ClientIncorrectDataException();

                if (!_facialDataPayload.GetFaceTrackingData(_frame, out var faceData))
                    return;

                trackingResult.FaceData = faceData;

                if (!_facialDataPayload.GetEyeTrackingData(_frame, out var eyeData))
                    return;

                trackingResult.EyeData = eyeData;
                return;
            }
        }

        /// <summary>
        /// Stops the tracking on the headset and closes the socket.
        /// </summary>
        public void Dispose()
        {
            try
            {
                if (_session != null && _headset != null)
                {
                    var stop = _session.Seal(Pairing.Stop);
                    _udpClient.Send(stop, stop.Length, _headset);
                }
            }
            finally
            {
                _session?.Dispose();
                _udpClient.Dispose();
            }
        }
    }
}
