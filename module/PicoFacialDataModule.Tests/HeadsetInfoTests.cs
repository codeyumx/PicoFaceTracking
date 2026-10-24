using PicoFacialDataModule.PicoFacialModule;
using Xunit;

namespace PicoFacialDataModule.Tests
{
    /// <summary>The INFO plaintext comes from the headset; a malformed one must not become a model name.</summary>
    public class HeadsetInfoTests
    {
        [Fact]
        public void ModelIsRead()
        {
            Assert.Equal("PICO 4 Pro", Pairing.ParseInfo(new byte[] { 10 }.Concat("PICO 4 Pro"u8.ToArray()).ToArray()));
        }

        [Fact]
        public void MalformedInfoIsRejected()
        {
            var info = new byte[] { 10 }.Concat("PICO 4 Pro"u8.ToArray()).ToArray();
            Assert.Null(Pairing.ParseInfo(info[..^1]));                                        // shorter than its length byte
            Assert.Null(Pairing.ParseInfo(new byte[] { 65 }.Concat(new byte[65]).ToArray()));  // over 64 bytes
            Assert.Null(Pairing.ParseInfo(new byte[] { 1, 0xC3 }));                             // cut UTF-8
            Assert.Null(Pairing.ParseInfo(ReadOnlySpan<byte>.Empty));
        }
    }
}
