using System.Globalization;
using System.Numerics;
using PicoFacialDataModule.PicoFacialModule;
using Xunit;

namespace PicoFacialDataModule.Tests
{
    /// <summary>
    /// Interoperability with the headset's side of pairing by code. The Pico Face Tracking app checks the same vectors
    /// (from the reference implementation in docs/protocol-v2.md), so both sides agree on every byte.
    /// </summary>
    public class PairingExchangeTests
    {
        private static readonly BigInteger X = BigInteger.Parse("00588f835f5a7685809e6cc1b335a4c013a7416e7b36f245a509e299d2dabce893", NumberStyles.HexNumber);
        private static readonly byte[] PcNonce = Convert.FromHexString("11111111111111111111111111111111");
        private static readonly byte[] HeadsetNonce = Convert.FromHexString("22222222222222222222222222222222");
        private static readonly byte[] Start = Convert.FromHexString("504654320a22222222222222222222222222222222111111111111111111111111111111113d04d2e15cd211f9c29067e23f48e6267d81a47638d5264f5317679261da1c01f5f53fcd84a657d561cb8149b55b481d4f81b105a265647c325382358c6aa089826e8c35f561e6fd7bf092b50645b6ef6926ef9faf93aab74e1c63a46f79b757ac419beca68455740d54c7a80b0f96873c8cd57701f8ce466c984741fcd2c0b635afdf514b1ab3a3769aeb5b1106285c145b6a68e4f5c93d9cc7cca037eba2d51da85347a5b344470f507d635b9fee717f527b0cfa26f65a431d3536cd2f58be5e732edbe2d412d8c5617f7c7f8c85aa8bf84988259f4d1898e4d631f01fe3905545ca5ae85c5e601c840b55e9be4abb597a8059fc8f70d2307a50b72a98378907544553542d5043");
        private static readonly byte[] Response = Convert.FromHexString("504654320b2222222222222222222222222222222211111111111111111111111111111111c5aa39b4335e1c56817f55797aaf39be57e6ca0da566a1179fcd7c90597c6c5cf7a9e63c2895cdd399ee72ad439bbe5ea32e01959ffb3b4c1ee3eeec7e5ccfba34c64642511e046e208b9b1cfa0e8263165a9804ed7cffb6cb7be04878656d4282f52b5d3f02de75761d7b093535d2be0511a9f1802a18c2cf3af5e1cc19ade749faae777b03f2ebc1ae9498a4bad0d654d4e580175598a0c7f446205bfc24df70134133b34dc1dab59a1bbb3009d812a96852d109f8b7be7c77c5b3785892927cb886178687d0fd0502e992a37df598c6f9ed177917a3a1829bf71893ce35c8a30e83cff945c1fbae37c643a347a606a48d19ed14f88b7ae33d7aefba9f1cc183d158acff7fce1d54931e18af948cc07015e738a20cbcbaa7b6edfe6c6858e7");
        private static readonly byte[] Confirm = Convert.FromHexString("504654320c2222222222222222222222222222222211111111111111111111111111111111fb3d5f2f01ff9509a3818980cfd33d78fcffe7030d56af9d99dce833d0e93d47");
        private static readonly byte[] Key = Convert.FromBase64String("9kvcbV9h9d4NGThcdJKY0JAA8B33pShjBIbEs71mfHo=");
        private static readonly byte[] WrongCodeResponse = Convert.FromHexString("504654320b2222222222222222222222222222222211111111111111111111111111111111a2492fbfca04798b622bd5376db935f1cee6b0c68cffa448aecc66ec1d14acfd8c51ba57dec1820656275708b6d47c4596fcbd6ff3eb9c49da8e5e1a6f0f6f55bae294a1be3682a6b62a2e9e0eea0b5fa7292282e8ecb58eb3547ca9eddb975a3a8dcf21ddc9a5bf6875592f5e56e10807503f12ecf44fe3c1c9509049c5032d2faef7f39c2c301c5eed12e1b69f0ece9d126849c010dd35e8728d9ec588fe0d37ed162e442e2757dc5a918aca23b07d1280df34acb7bf56c7363b3fd598a6d62f753a2af55edc7e11ad261d1dcd669e2348df462d70c938794c570f12c5f97ec77a6ef7c52c60e874d8a8a534f242c699bb9ab2ecb5c658a2f2072c608504754e4d0a8c436818cf2460cde424f091c98adebd31b47b38e1f61663142783be57");

        private static PairingExchange Exchange() => new(HeadsetNonce, "TEST-PC", "123456", PcNonce, X);

        [Fact]
        public void StartMatchesTheHeadsetsVector()
        {
            Assert.Equal(Start, Exchange().StartMessage);
        }

        [Fact]
        public void RightCodeGivesTheHeadsetsKey()
        {
            Assert.Equal(PairingExchange.Outcome.Paired, Exchange().Finish(Response, out var key, out var reply));
            Assert.Equal(Confirm, reply);
            Assert.Equal(Key, key);
        }

        [Fact]
        public void WrongCodeIsRejectedWithoutAKey()
        {
            Assert.Equal(PairingExchange.Outcome.WrongCode, Exchange().Finish(WrongCodeResponse, out var key, out var reply));
            Assert.Empty(key);
            Assert.Equal(Convert.FromHexString("504654320d").Concat(HeadsetNonce).Concat(PcNonce), reply);
        }

        [Fact]
        public void ResponseForAnotherAttemptIsIgnored()
        {
            var other = (byte[])Response.Clone();
            other[5] ^= 1;
            Assert.Equal(PairingExchange.Outcome.NotForThis, Exchange().Finish(other, out _, out _));

            // 1 is not a valid element: it would make the shared secret independent of the headset's secret.
            var identity = (byte[])Response.Clone();
            Array.Clear(identity, 37, 256);
            identity[37 + 255] = 1;
            Assert.Equal(PairingExchange.Outcome.NotForThis, Exchange().Finish(identity, out _, out _));
        }

        [Fact]
        public void OfferIsRead()
        {
            var offer = Convert.FromHexString("5046543209").Concat(HeadsetNonce).Concat(new byte[] { 5 }).Concat("A8110"u8.ToArray()).ToArray();
            Assert.True(PairingExchange.TryParseOffer(offer, out var nonce, out var name));
            Assert.Equal(HeadsetNonce, nonce);
            Assert.Equal("A8110", name);
            Assert.False(PairingExchange.TryParseOffer(offer[..^1], out _, out _));
        }
    }
}
