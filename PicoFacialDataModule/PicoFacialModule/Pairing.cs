using System.Buffers.Binary;
using System.Security.Cryptography;

namespace PicoFacialDataModule.PicoFacialModule
{
    /// <summary>
    /// Protocol version 2: a key shared with the headset authenticates the handshake and encrypts the session,
    /// so the headset can be found again when this PC's address changes. See docs/protocol-v2.md.
    /// </summary>
    public sealed class Pairing
    {
        public const byte Discover = 1;
        public const byte Hello = 2;
        public const byte Start = 3;
        public const byte Data = 4;
        public const byte Ping = 5;
        public const byte Pong = 6;
        public const byte Stop = 7;

        public const int HeaderLength = 5;
        public const int NonceLength = 16;
        private const int MacLength = 16;
        private const int CounterLength = 8;
        private const int TagLength = 16;
        private const int KeyLength = 32;
        public const int DiscoverLength = HeaderLength + NonceLength + MacLength;
        public const int HandshakeLength = HeaderLength + 2 * NonceLength + MacLength;
        public const int SessionOverhead = HeaderLength + CounterLength + TagLength;

        private static readonly byte[] Magic = "PFT2"u8.ToArray();
        private static readonly byte[] FromHeadset = { (byte)'H', (byte)'M', (byte)'D', 0 };
        private static readonly byte[] FromPc = { (byte)'P', (byte)'C', 0, 0 };

        /// <summary>Base64 text of the 32-byte key, written when a headset pairs by code (see <see cref="PairingListener"/>).</summary>
        public static string KeyPath => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PicoFacialData", "pairing-key.txt");

        private readonly byte[] _authKey;
        private readonly byte[] _encryptionKey;

        private Pairing(byte[] key)
        {
            _authKey = HMACSHA256.HashData(key, "PFT2 auth"u8);
            _encryptionKey = HMACSHA256.HashData(key, "PFT2 enc"u8);
        }

        /// <summary>The pairing at <see cref="KeyPath"/>, or null when there is no key file.</summary>
        public static Pairing? Load()
        {
            if (!File.Exists(KeyPath))
                return null;

            byte[] key;
            try
            {
                key = Convert.FromBase64String(File.ReadAllText(KeyPath).Trim());
            }
            catch (FormatException)
            {
                throw new InvalidDataException($"{KeyPath} does not contain a Base64 key.");
            }

            if (key.Length != KeyLength)
                throw new InvalidDataException($"{KeyPath} must contain a {KeyLength}-byte key.");

            return new Pairing(key);
        }

        /// <summary>Saves a new key at <see cref="KeyPath"/>, replacing the old one, and returns its pairing.</summary>
        public static Pairing Save(byte[] key)
        {
            if (key.Length != KeyLength)
                throw new ArgumentException($"The key must have {KeyLength} bytes.", nameof(key));

            Directory.CreateDirectory(Path.GetDirectoryName(KeyPath)!);
            var temporary = KeyPath + ".new";
            File.WriteAllText(temporary, Convert.ToBase64String(key));
            File.Move(temporary, KeyPath, overwrite: true);
            return new Pairing(key);
        }

        public static int TypeOf(ReadOnlySpan<byte> message)
        {
            if (message.Length < HeaderLength || !message[..Magic.Length].SequenceEqual(Magic))
                return -1;
            return message[Magic.Length];
        }

        public byte[] CreateDiscover(ReadOnlySpan<byte> pcNonce)
        {
            var message = new byte[DiscoverLength];
            WriteHeader(message, Discover);
            pcNonce.CopyTo(message.AsSpan(HeaderLength));
            Mac(message.AsSpan(0, HeaderLength + NonceLength)).CopyTo(message.AsSpan(HeaderLength + NonceLength));
            return message;
        }

        /// <summary>Whether the message is a HELLO for this DISCOVER; returns the headset's nonce.</summary>
        public bool VerifyHello(ReadOnlySpan<byte> message, ReadOnlySpan<byte> pcNonce, out byte[] headsetNonce)
        {
            headsetNonce = Array.Empty<byte>();
            if (message.Length != HandshakeLength || TypeOf(message) != Hello)
                return false;

            var signed = message[..(HeaderLength + 2 * NonceLength)];
            if (!CryptographicOperations.FixedTimeEquals(Mac(signed), message[signed.Length..]))
                return false;
            if (!CryptographicOperations.FixedTimeEquals(message.Slice(HeaderLength, NonceLength), pcNonce))
                return false;

            headsetNonce = message.Slice(HeaderLength + NonceLength, NonceLength).ToArray();
            return true;
        }

        public byte[] CreateStart(ReadOnlySpan<byte> pcNonce, ReadOnlySpan<byte> headsetNonce)
        {
            var message = new byte[HandshakeLength];
            WriteHeader(message, Start);
            pcNonce.CopyTo(message.AsSpan(HeaderLength));
            headsetNonce.CopyTo(message.AsSpan(HeaderLength + NonceLength));
            Mac(message.AsSpan(0, HeaderLength + 2 * NonceLength)).CopyTo(message.AsSpan(HeaderLength + 2 * NonceLength));
            return message;
        }

        public Session CreateSession(ReadOnlySpan<byte> pcNonce, ReadOnlySpan<byte> headsetNonce)
        {
            Span<byte> nonces = stackalloc byte[2 * NonceLength];
            pcNonce.CopyTo(nonces);
            headsetNonce.CopyTo(nonces[NonceLength..]);
            return new Session(HMACSHA256.HashData(_encryptionKey, nonces));
        }

        private ReadOnlySpan<byte> Mac(ReadOnlySpan<byte> data) => HMACSHA256.HashData(_authKey, data).AsSpan(0, MacLength);

        private static void WriteHeader(Span<byte> message, byte type)
        {
            Magic.CopyTo(message);
            message[Magic.Length] = type;
        }

        /// <summary>One encrypted session: AES-256-GCM with a counter nonce per direction.</summary>
        public sealed class Session : IDisposable
        {
            private readonly AesGcm _aes;
            private readonly byte[] _nonce = new byte[12];
            private ulong _sent;
            private ulong _received;

            internal Session(byte[] key)
            {
                _aes = new AesGcm(key, TagLength);
            }

            /// <summary>
            /// Opens a message from the headset into <paramref name="plaintext"/>. Returns its type, or -1 when the
            /// message is not authentic, is a replay, or does not fit.
            /// </summary>
            public int Open(ReadOnlySpan<byte> message, Span<byte> plaintext, out int plaintextLength)
            {
                plaintextLength = 0;
                var type = TypeOf(message);
                if (type < 0 || message.Length < SessionOverhead)
                    return -1;

                var counter = BinaryPrimitives.ReadUInt64LittleEndian(message.Slice(HeaderLength, CounterLength));
                var length = message.Length - SessionOverhead;
                if (counter <= _received || length > plaintext.Length)
                    return -1;

                SetNonce(FromHeadset, counter);
                try
                {
                    _aes.Decrypt(
                        _nonce,
                        message.Slice(HeaderLength + CounterLength, length),
                        message[^TagLength..],
                        plaintext[..length],
                        message[..(HeaderLength + CounterLength)]);
                }
                catch (CryptographicException)
                {
                    return -1;
                }

                _received = counter;
                plaintextLength = length;
                return type;
            }

            /// <summary>Creates a message to the headset without a body (PONG, STOP).</summary>
            public byte[] Seal(byte type)
            {
                _sent++;
                var message = new byte[SessionOverhead];
                WriteHeader(message, type);
                BinaryPrimitives.WriteUInt64LittleEndian(message.AsSpan(HeaderLength, CounterLength), _sent);
                SetNonce(FromPc, _sent);
                _aes.Encrypt(
                    _nonce,
                    ReadOnlySpan<byte>.Empty,
                    Span<byte>.Empty,
                    message.AsSpan(HeaderLength + CounterLength, TagLength),
                    message.AsSpan(0, HeaderLength + CounterLength));
                return message;
            }

            private void SetNonce(byte[] direction, ulong counter)
            {
                direction.CopyTo(_nonce, 0);
                BinaryPrimitives.WriteUInt64LittleEndian(_nonce.AsSpan(4), counter);
            }

            public void Dispose() => _aes.Dispose();
        }
    }
}
