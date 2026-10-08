namespace PicoFacialDataModule.PicoFacialModule.Exceptions
{
    public class ClientIncorrectDataException : Exception
    {
        public override string ToString()
        {
            return "The client sent incorrect data.";
        }
    }
}
