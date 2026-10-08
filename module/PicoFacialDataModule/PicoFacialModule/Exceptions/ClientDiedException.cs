namespace PicoFacialDataModule.PicoFacialModule.Exceptions
{
    public class ClientDiedException : Exception
    {
        public override string ToString()
        {
            return "The client has died.";
        }
    }
}
