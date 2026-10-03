using PicoFacialDataModule.PicoFacialModule.Exceptions;
using PicoFacialDataModule.PicoFacialModule.Interfaces;
using PicoFacialDataModule.PicoFacialModule.Models;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

namespace PicoFacialDataModule.PicoFacialModule
{
    enum PicoFacialDataPayload
    {
        FT_INFO_START,
        PXR_EYE_POSE_START = 384,
        PXR_EYE_POSE_END = PXR_EYE_POSE_START + 200
    }

    public class PicoFacialModuleConnector : IPicoFacialModuleConnector
    {
        private const string MULTICAST_ADDRESS = "239.255.255.250";

        private const string DISCOVER_PAYLOAD = "DISCOVER_DAEMON";

        private const string PING = "MARCO";
        private const string REPLY = "POLO";

        private const string STOP = "STOP";

        private string? _ip;
        private int _port;

        private UdpClient? _udpClient;
        private IPEndPoint? _client;

        private byte[]? _establishBuffer;

        public PicoFacialModuleConnector(int port, string? IP)
        {
            _udpClient = new UdpClient(port)
            {
                EnableBroadcast = true,
                MulticastLoopback = false,
            };

            _ip = IP;
            _port = port;
        }

        ~PicoFacialModuleConnector()
        {
            if (_udpClient != null)
                _udpClient.Dispose();
        }

        /// <summary>
        /// Waits for the tracking module to send data.
        /// </summary>
        public async Task ReceiveAsync(TrackingResult trackingResult)
        {
            byte[] buffer;

            do
            {
                if (_establishBuffer != null)
                {
                    buffer = _establishBuffer;
                    _establishBuffer = null;
                    break;
                }

                try
                {
                    using var cancellationToken = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    buffer = (await _udpClient!.ReceiveAsync(cancellationToken.Token)).Buffer;
                }
                catch
                {
                    throw new ClientDiedException();
                }

            } while (!HasTrackingData(buffer));

            if (buffer.Length < (int)PicoFacialDataPayload.PXR_EYE_POSE_END || buffer.Length > (int)PicoFacialDataPayload.PXR_EYE_POSE_END)
                throw new ClientIncorrectDataException();

            if (!MemoryMarshal.TryRead<PicoFTInfo>(buffer![(int)PicoFacialDataPayload.FT_INFO_START..(int)PicoFacialDataPayload.PXR_EYE_POSE_START], out var faceData))
                return;

            trackingResult.FaceData = faceData;

            if (!MemoryMarshal.TryRead<PxrEyePoseDataV2>(buffer![(int)PicoFacialDataPayload.PXR_EYE_POSE_START..], out var eyeData))
                return;

            trackingResult.EyeData = eyeData;
        }

        /// <summary>
        /// Finds and returns a peer that wants to send tracking data.
        /// </summary>
        public async Task<IPEndPoint> EstablishAsync()
        {
            IPEndPoint endpoint = new IPEndPoint(
                string.IsNullOrEmpty(_ip) ? IPAddress.Parse(MULTICAST_ADDRESS) : IPAddress.Parse(_ip),
                _port
            );

            var discoverPayload = Encoding.UTF8.GetBytes(DISCOVER_PAYLOAD);

            byte[]? reply = null;

            // Get all network cards.
            var networkIPs = Dns.GetHostAddresses(Dns.GetHostName()).Where(ip => ip.AddressFamily == AddressFamily.InterNetwork);

            while (true)
            {
                using var cancellationToken = new CancellationTokenSource(TimeSpan.FromSeconds(2));

                foreach (var networkIP in networkIPs)
                {
                    _udpClient!.Client.SetSocketOption(
                        SocketOptionLevel.IP,
                        SocketOptionName.MulticastInterface,
                        networkIP.GetAddressBytes()
                     );
                    await _udpClient.SendAsync(discoverPayload, discoverPayload.Length, endpoint);
                }

                IPEndPoint? receiver = null;

                try
                {
                    var result = await _udpClient!.ReceiveAsync(cancellationToken.Token);

                    receiver = result.RemoteEndPoint;
                    reply = result.Buffer;
                }
                catch (OperationCanceledException)
                {

                }
                catch (Exception) {
                    throw;
                }

                if (reply != null)
                {
                    _establishBuffer = reply;
                    _client = receiver!;
                    return receiver!;
                }
            }
        }

        /// <summary>
        /// Stops and closes the tracking on the client.
        /// </summary>
        public void Dispose()
        {
            _udpClient!.Send(Encoding.UTF8.GetBytes(STOP), _client);
            _udpClient.Dispose();
        }

        private bool HasTrackingData(byte[] buffer)
        {
            if (buffer == null) return false;

            // Keep-alive ping
            if (buffer.Length == PING.Length + 1)
            {
                _udpClient!.Send(Encoding.UTF8.GetBytes(REPLY), _client);
                return false;
            }

            return true;
        }
    }
}
