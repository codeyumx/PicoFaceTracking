using System.Globalization;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace PicoFacialDataModule.PicoFacialModule
{
    /// <summary>
    /// Pairing by code, PC side (docs/protocol-v2.md, "Pairing"). This module shows a 6-digit code in VRCFaceTracking's
    /// log; the user types it on the headset. SPAKE2 in the 2048-bit MODP group of RFC 3526 turns the code into a new
    /// random pairing key on both sides. The code never crosses the network, and a device that does not know it gets a
    /// single guess per code.
    /// </summary>
    public sealed class PairingExchange
    {
        public const byte Discover = 8;
        public const byte Offer = 9;
        public const byte Start = 10;
        public const byte Response = 11;
        public const byte Confirm = 12;
        public const byte Reject = 13;

        public enum Outcome
        {
            /// <summary>The message does not belong to this pairing attempt.</summary>
            NotForThis,
            WrongCode,
            Paired,
        }

        private const int HeaderLength = Pairing.HeaderLength;
        private const int NonceLength = 16;
        private const int ElementLength = 256;
        private const int ConfirmationLength = 32;
        private const int MaxNameLength = 64;
        private const int ResponseLength = HeaderLength + 2 * NonceLength + ElementLength + ConfirmationLength;

        private static readonly BigInteger P = BigInteger.Parse(
            "00FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374F" +
            "E1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE38" +
            "6BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48" +
            "361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED52907" +
            "7096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C18" +
            "0E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA95" +
            "6AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
            NumberStyles.HexNumber);
        private static readonly BigInteger Q = P >> 1;
        private static readonly BigInteger G = 2;
        private static readonly BigInteger M = HashToGroup("PFT2 pairing M");
        private static readonly BigInteger N = HashToGroup("PFT2 pairing N");
        private static readonly byte[] Magic = "PFT2"u8.ToArray();

        private readonly byte[] _headsetNonce;
        private readonly byte[] _pcNonce;
        private readonly byte[] _codeHash;
        private readonly BigInteger _w;
        private readonly BigInteger _x;
        private readonly byte[] _element;

        /// <summary>The six digits to show to the user.</summary>
        public string Code { get; }

        /// <summary>The START message for the headset, repeated until it answers.</summary>
        public byte[] StartMessage { get; }

        public PairingExchange(byte[] headsetNonce, string pcName)
            : this(headsetNonce, pcName, RandomNumberGenerator.GetInt32(1_000_000).ToString("D6", CultureInfo.InvariantCulture),
                RandomNumberGenerator.GetBytes(NonceLength), RandomExponent())
        {
        }

        /// <summary>With a given code, nonce and secret exponent; for the test vectors.</summary>
        internal PairingExchange(byte[] headsetNonce, string pcName, string code, byte[] pcNonce, BigInteger x)
        {
            _headsetNonce = headsetNonce;
            _pcNonce = pcNonce;
            _x = x;
            Code = code;
            _codeHash = SHA256.HashData(Concat("PFT2 pairing code "u8.ToArray(), Encoding.ASCII.GetBytes(code)));
            _w = new BigInteger(_codeHash, isUnsigned: true, isBigEndian: true) % Q;
            _element = ToElement(BigInteger.ModPow(G, x, P) * BigInteger.ModPow(M, _w, P) % P);

            var name = Name(pcName);
            StartMessage = new byte[HeaderLength + 2 * NonceLength + ElementLength + 1 + name.Length];
            WriteHeader(StartMessage, Start);
            headsetNonce.CopyTo(StartMessage, HeaderLength);
            pcNonce.CopyTo(StartMessage, HeaderLength + NonceLength);
            _element.CopyTo(StartMessage, HeaderLength + 2 * NonceLength);
            StartMessage[HeaderLength + 2 * NonceLength + ElementLength] = (byte)name.Length;
            name.CopyTo(StartMessage, HeaderLength + 2 * NonceLength + ElementLength + 1);
        }

        public byte[] HeadsetNonce => _headsetNonce;

        public static byte[] CreateDiscover()
        {
            var message = new byte[HeaderLength];
            WriteHeader(message, Discover);
            return message;
        }

        /// <summary>Reads a headset's OFFER: the nonce of its pairing attempt and its name.</summary>
        public static bool TryParseOffer(ReadOnlySpan<byte> message, out byte[] headsetNonce, out string headsetName)
        {
            headsetNonce = Array.Empty<byte>();
            headsetName = "";
            const int fixedLength = HeaderLength + NonceLength + 1;
            if (message.Length < fixedLength || Pairing.TypeOf(message) != Offer)
                return false;
            var nameLength = message[fixedLength - 1];
            if (nameLength > MaxNameLength || message.Length != fixedLength + nameLength)
                return false;

            headsetNonce = message.Slice(HeaderLength, NonceLength).ToArray();
            headsetName = Encoding.UTF8.GetString(message.Slice(fixedLength, nameLength));
            return true;
        }

        /// <summary>
        /// Checks the headset's RESPONSE. When the code was right, returns the new pairing key and the CONFIRM to send;
        /// when it was wrong, the REJECT to send.
        /// </summary>
        public Outcome Finish(ReadOnlySpan<byte> response, out byte[] key, out byte[] reply)
        {
            key = Array.Empty<byte>();
            reply = Array.Empty<byte>();
            if (response.Length != ResponseLength || Pairing.TypeOf(response) != Response
                || !response.Slice(HeaderLength, NonceLength).SequenceEqual(_headsetNonce)
                || !response.Slice(HeaderLength + NonceLength, NonceLength).SequenceEqual(_pcNonce))
                return Outcome.NotForThis;

            var s = response.Slice(HeaderLength + 2 * NonceLength, ElementLength).ToArray();
            var sValue = new BigInteger(s, isUnsigned: true, isBigEndian: true);
            if (!IsGroupElement(sValue))
                return Outcome.NotForThis;

            var shared = ToElement(BigInteger.ModPow(sValue * BigInteger.ModPow(N, (Q - _w) % Q, P) % P, _x, P));
            var transcript = SHA256.HashData(Concat("PFT2 pairing"u8.ToArray(), _headsetNonce, _pcNonce, _element, s, shared, _codeHash));
            var nonces = Concat(_headsetNonce, _pcNonce);
            var headsetConfirmation = HMACSHA256.HashData(HMACSHA256.HashData(transcript, "PFT2 confirm HMD"u8), nonces);

            reply = new byte[HeaderLength + 2 * NonceLength + ConfirmationLength];
            nonces.CopyTo(reply, HeaderLength);
            if (!CryptographicOperations.FixedTimeEquals(headsetConfirmation, response[^ConfirmationLength..]))
            {
                reply = reply[..(HeaderLength + 2 * NonceLength)];
                WriteHeader(reply, Reject);
                return Outcome.WrongCode;
            }

            WriteHeader(reply, Confirm);
            HMACSHA256.HashData(HMACSHA256.HashData(transcript, "PFT2 confirm PC"u8), nonces).CopyTo(reply, HeaderLength + 2 * NonceLength);
            key = HMACSHA256.HashData(transcript, "PFT2 pairing key"u8);
            return Outcome.Paired;
        }

        private static BigInteger RandomExponent()
        {
            while (true)
            {
                var x = new BigInteger(RandomNumberGenerator.GetBytes(32), isUnsigned: true, isBigEndian: true) % Q;
                if (!x.IsZero)
                    return x;
            }
        }

        private static bool IsGroupElement(BigInteger value) =>
            value > BigInteger.One && value < P - BigInteger.One && BigInteger.ModPow(value, Q, P).IsOne;

        /// <summary>Hashes a label to an element of the order-Q subgroup whose discrete logarithm nobody knows.</summary>
        private static BigInteger HashToGroup(string label)
        {
            var wide = new byte[9 * 32];
            for (var i = 0; i < 9; i++)
                SHA256.HashData(Concat(Encoding.ASCII.GetBytes(label), new[] { (byte)i })).CopyTo(wide, 32 * i);
            var value = new BigInteger(wide, isUnsigned: true, isBigEndian: true) % P;
            return value * value % P;
        }

        /// <summary>A group element as 256 big-endian bytes.</summary>
        private static byte[] ToElement(BigInteger value)
        {
            var bytes = value.ToByteArray(isUnsigned: true, isBigEndian: true);
            var element = new byte[ElementLength];
            bytes.CopyTo(element, ElementLength - bytes.Length);
            return element;
        }

        private static byte[] Name(string text)
        {
            var bytes = Encoding.UTF8.GetBytes(text);
            var length = Math.Min(bytes.Length, MaxNameLength);
            // Do not cut a UTF-8 sequence in half.
            while (length > 0 && length < bytes.Length && (bytes[length] & 0xc0) == 0x80)
                length--;
            return bytes[..length];
        }

        private static void WriteHeader(byte[] message, byte type)
        {
            Magic.CopyTo(message, 0);
            message[Magic.Length] = type;
        }

        private static byte[] Concat(params byte[][] parts)
        {
            var result = new byte[parts.Sum(part => part.Length)];
            var offset = 0;
            foreach (var part in parts)
            {
                part.CopyTo(result, offset);
                offset += part.Length;
            }
            return result;
        }
    }
}
