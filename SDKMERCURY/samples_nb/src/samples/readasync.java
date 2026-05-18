/**
 * Sample program that reads tags in the background and prints the
 * tags found.
 */

// Import the API
package samples;
import com.thingmagic.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;

public class readasync
{
  static SerialPrinter serialPrinter;
  static StringPrinter stringPrinter;
  static TransportListener currentListener;
  static ReadListener rl;
  static ReadExceptionListener exceptionListener;
  // This indicates the read time of async read, i.e., sleep time between start and stop read.
  private static final int SLEEPTIME = 5000;

  static void usage()
  {
    System.out.printf("Usage: Please provide valid arguments, such as:\n"
                + "readasync [-v] [reader-uri] [--ant n[,n...]] \n" +
                  "-v  Verbose: Turn on transport listener\n" +
                  "reader-uri  Reader URI: e.g., \"tmr:///COM1\", \"tmr://astra-2100d3\"\n"
                + "--ant  Antenna List: e.g., \"--ant 1\", \"--ant 1,2\"\n"
                + "Example for UHF: tmr:///com1 --ant 1,2 ; tmr://10.11.115.32 --ant 1,2\n "
                + "Example for HF/LF: 'tmr:///com4'\n ");
    System.exit(1);
  }

   public static void setTrace(Reader r, String args[])
  {
    if (args[0].toLowerCase().equals("on"))
    {
        r.addTransportListener(Reader.simpleTransportListener);
        currentListener = Reader.simpleTransportListener;
    }
    else if (currentListener != null)
    {
        r.removeTransportListener(Reader.simpleTransportListener);
    }
  }

   static class SerialPrinter implements TransportListener
  {
    public void message(boolean tx, byte[] data, int timeout)
    {
      System.out.print(tx ? "Sending: " : "Received:");
      for (int i = 0; i < data.length; i++)
      {
        if (i > 0 && (i & 15) == 0)
          System.out.printf("\n         ");
        System.out.printf(" %02x", data[i]);
      }
      System.out.printf("\n");
    }
  }

  static class StringPrinter implements TransportListener
  {
    public void message(boolean tx, byte[] data, int timeout)
    {
      System.out.println((tx ? "Sending:\n" : "Receiving:\n") +
                         new String(data));
    }
  }
  public static void main(String argv[])
  {
    // Program setup
    Reader r = null;
    int nextarg = 0;
    boolean trace = false;
    int[] antennaList = null;
    // Capture the start time before starting the read.
    long startTime = 0;
    if (argv.length < 1)
      usage();

    if (argv[nextarg].equals("-v"))
    {
      trace = true;
      nextarg++;
    }

    // Create Reader object, connecting to physical device
    try
    {
        String readerURI = argv[nextarg];
        nextarg++;

        for (; nextarg < argv.length; nextarg++)
        {
            String arg = argv[nextarg];
            if (arg.equalsIgnoreCase("--ant"))
            {
                if (antennaList != null)
                {
                    System.out.println("Duplicate argument: --ant specified more than once");
                    usage();
                }
                antennaList = parseAntennaList(argv, nextarg);
                nextarg++;
            }
            else
            {
                System.out.println("Argument " + argv[nextarg] + " is not recognised");
                usage();
            }
        }

        r = Reader.create(readerURI);
        if (trace)
        {
          setTrace(r, new String[] {"on"});
        }
        r.connect();
        if (Reader.Region.UNSPEC == (Reader.Region)r.paramGet("/reader/region/id"))
        {
            Reader.Region[] supportedRegions = (Reader.Region[])r.paramGet(TMConstants.TMR_PARAM_REGION_SUPPORTEDREGIONS);
            if (supportedRegions.length < 1)
            {
                 throw new Exception("Reader doesn't support any regions");
            }
            else
            {
                 r.paramSet("/reader/region/id", supportedRegions[0]);
            }
        }
        String model = (String)r.paramGet("/reader/version/model");
        if (!(model.equalsIgnoreCase("M3e")))
        {
            if (r.isAntDetectEnabled(antennaList))
            {
                System.out.println("Module doesn't has antenna detection support, please provide antenna list");
                r.destroy();
                usage();
            }
        }

        if (!(model.equalsIgnoreCase("M3e")))
        {
            SimpleReadPlan plan = new SimpleReadPlan(antennaList, TagProtocol.GEN2, null, null, 1000);
            r.paramSet(TMConstants.TMR_PARAM_READ_PLAN, plan);
        }
        else
        {
            SimpleReadPlan plan = new SimpleReadPlan(antennaList, TagProtocol.ISO14443A, null, null, 1000);
            r.paramSet(TMConstants.TMR_PARAM_READ_PLAN, plan);
        }
        
        exceptionListener = new TagReadExceptionReceiver();
        r.addReadExceptionListener(exceptionListener);
        // Create and add tag listener
        rl = new PrintListener();
        r.addReadListener(rl);

        // search for tags in the background
        r.startReading();

        startTime = System.currentTimeMillis();

        /** Exit the while loop,
          * 1. When error occurs 
          * 2. When sleep timeout expires
          */
        while(true)
        {
            if((System.currentTimeMillis() - startTime) > SLEEPTIME)
            {
                /* break if sleep timeout expired */
                break;
            }

            /* Exit the process if any error occured */
            if(r.lastReportedException != null)
            {
                exceptionHandler(r);
                /* Can add recovery mechanism here*/
                /* Do some work here */
                System.exit(0);
            }
            Thread.sleep(1);
        }

        r.stopReading();

        r.removeReadListener(rl);
        r.removeReadExceptionListener(exceptionListener);

        // Shut down reader
        r.destroy();
    } 
    catch (ReaderException re)
    {
        // Shut down reader
        if(r!=null)
        {
            r.destroy();
        }
        System.out.println("ReaderException: " + re.getMessage());
    }
    catch (Exception re)
    {
        // Shut down reader
        if(r!=null)
        {
            r.destroy();
        }
        System.out.println("Exception: " + re.getMessage());
    }
  }

  static class PrintListener implements ReadListener
  {
    public void tagRead(Reader r, TagReadData tr)
    {
      System.out.println("Background read: " + tr.toString());
    }

  }

  static class TagReadExceptionReceiver implements ReadExceptionListener
  {
        String strDateFormat = "M/d/yyyy h:m:s a";
        SimpleDateFormat sdf = new SimpleDateFormat(strDateFormat);
        public void tagReadException(com.thingmagic.Reader r, ReaderException re)
        {
            String format = sdf.format(Calendar.getInstance().getTime());
            String currentReportedException = re.getMessage();
            if(r.lastReportedException == null || (r.lastReportedException != null && 
                    !(r.lastReportedException.getMessage().equalsIgnoreCase(currentReportedException))))    
            {
                System.out.println("Reader Exception: " + re.getMessage() + " Occured on :" + format);
            }
            r.lastReportedException = re;
        }
    }
    // Function to handle different exception received
    static void exceptionHandler(Reader r)
    {
        ReaderException re = r.lastReportedException;
        if(re.getMessage().equalsIgnoreCase("The reader received a valid command with an unsupported or invalid parameter")
           || re.getMessage().equalsIgnoreCase("Unimplemented feature."))
        {
            r.stopReading();
            r.destroy();
        }
        else if(re.getMessage().equalsIgnoreCase("Timeout"))
        {
         ((SerialReader)r).TMR_flush();
         r.destroy();
        }
        else
        {
            /* Do not send stop read for unknown errors */
            r.destroy();
        }
    }
  
  static  int[] parseAntennaList(String[] args,int argPosition)
    {
        int[] antennaList = null;
        try
        {
            String argument = args[argPosition + 1];
            String[] antennas = argument.split(",");
            int i = 0;
            antennaList = new int[antennas.length];
            for (String ant : antennas)
            {
                antennaList[i] = Integer.parseInt(ant);
                i++;
            }
        }
        catch (IndexOutOfBoundsException ex)
        {
            System.out.println("Missing argument after " + args[argPosition]);
            usage();
        }
        catch (Exception ex)
        {
            System.out.println("Invalid argument at position " + (argPosition + 1) + ". " + ex.getMessage());
            usage();
        }
        return antennaList;
    }
  
}
