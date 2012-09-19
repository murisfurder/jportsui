package jport;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import javax.swing.JOptionPane;
import jport.PortsConstants.EPortStatus;
import jport.common.Util;
import jport.type.CliPortInfo;
import jport.type.PortFactory;
import jport.type.Portable;


/**
 * Reads all from the "PortIndex" file and then status is updated by the Port CLI tool.
 *
 * @author sbaber
 */
public class PortsCatalog
{
    /** This file created by MacPorts. */
    static final private String _PORTS_FILE_NAME = "PortIndex";

    /** As of 2012-09-01, ~15,500 port entries. */
    static final private int _FORECAST_COUNT = 20000;

    static final PortsCatalog NONE = new PortsCatalog( false );

    static
    {}

    /** Time of "PortIndex" file parse. */
    final private long fParse_EpochTimeMillisec = System.currentTimeMillis();

    final private PortsDep             fPortsDep = new PortsDep( this );
    final private PortsVariants        fPortsVariants = new PortsVariants();
    
    /** Key is the case-insensitive port name. */
    final private Map<String,Portable> fCiName_to_PortMap;
    final private PortsInventory       fPortsInventory;

    volatile private PortsDate vPortsDate = null;

    /**
     * For initial NONE catalog.
     *
     * @param ignore unique signature code smell
     */
    private PortsCatalog( final boolean ignore )
    {
        fCiName_to_PortMap = Collections.emptyMap();
        fPortsInventory = new PortsInventory();
    }

    /**
     * Reread and parse the "PortsIndex" file into a new Map.
     */
    PortsCatalog()
    {
        this( PortsConstants.PORTS_PATH + _PORTS_FILE_NAME );
    }

    private PortsCatalog( final String filePathName )
    {
        final Map<String,Portable> ciName_to_PortMap = _parsePortIndex( filePathName ); // *BLOCKS* for disk I/O

        final Set<Portable> allPortSet = new HashSet<Portable>( _FORECAST_COUNT );

        // interrogate the CLI for user's installed Ports status
        final Map<EPortStatus,Set<CliPortInfo>> status_to_CpiSet_Map = PortsCliUtil.cliAllStatus2(); // *BLOCKS* for CLI
        final Map<CliPortInfo,Set<EPortStatus>> cpi_to_StatusSet_Map = CliPortInfo.createInverseMultiMapping( status_to_CpiSet_Map );

        for( final Map.Entry<CliPortInfo,Set<EPortStatus>> entry : cpi_to_StatusSet_Map.entrySet() )
        {
            final CliPortInfo cpi = entry.getKey(); // alias
            final String ciName = cpi.getCaseInsensitiveName();
            final Portable prevPort = ciName_to_PortMap.get( ciName );

            if( prevPort != null )
            {   // always expected to be found
                final Portable cliPort = PortFactory.createFromCli( prevPort, cpi );

                final Set<EPortStatus> statusSet = entry.getValue(); // alias
                for( final EPortStatus statusEnum : statusSet )
                {   // assign each status found in the CLI
                    cliPort.setStatus( statusEnum );
                }

                // MacPorts guarantees only one Active version per Port
                if( statusSet.contains( EPortStatus.ACTIVE ) == true )
                {   // Replace existing index Port with the ACTIVE installed port info version.
                    // That ensures that any ci_named dep Ports refer here-in.
                    ciName_to_PortMap.put( ciName, cliPort );
                }
                else
                {   // copy in other Inactive Port versions and also modify Active status with Outdated, etc.
                    allPortSet.add( cliPort );
                }
            }
            // else port now deprecated?
        }

        // augment with installed ports the included MULTIPLE versions with differing variants
        allPortSet.addAll( ciName_to_PortMap.values() );

        fPortsInventory = new PortsInventory( allPortSet );

        fCiName_to_PortMap = ciName_to_PortMap;
    }

    static private Map<String,Portable> _parsePortIndex( final String filePathName )
    {
        final Map<String,Portable> map = new HashMap<String,Portable>( _FORECAST_COUNT );

        final File filePath = ( PortsConstants.HAS_MAC_PORTS == true )
                ? new File( filePathName )
                : new File( _PORTS_FILE_NAME ); // fall back to project folder for dev work

        if( filePath.exists() == false )
        {   // Port index file not found
            JOptionPane.showMessageDialog( null, filePathName +"\n does not seem to exist." );
            System.exit( 1 );
        }
        else
        {   // found Port index
            if( PortsConstants.DEBUG ) System.out.println( PortsCatalog.class.getSimpleName() +" OPTIMIZATION="+ PortsConstants.OPTIMIZATION );
            final long startMillisec = System.currentTimeMillis();

            if( PortsConstants.OPTIMIZATION )
            {   // Scanner uses regex, this is 2x faster on startup -AND- accommodates multi-line port info
                try
                {
                    final byte[] bytes = Util.retreiveFileBytes( filePath ); //? assumes UTF-8 encoding when constructing the String from bytes?
                    int p = 0;
                    int q = 1;
                    while( q < bytes.length )
                    {   // get port name and info length line indexes
                        while( bytes[ q ] != '\n' && q < bytes.length )
                        {
                            q++;
                        }

                        // extract info length
                        int size = 0;
                        int magnitude = 1;
                        int r = q - 1;
                        do
                        {   // parse positive integer
                            final int digit = bytes[ r ] - (byte)'0';
                            size += digit * magnitude;
                            magnitude *= 10;
                            r--;
                        }
                        while( bytes[ r ] != 0x20 ); // space

                        // offset from that short line
                        // final String shortLine = new String( bytes, p, q - p );
                        p = q + 1;
                        q = p + 1;

                        // jump ahead by the info length
                        q += size;
                        q -= 2;

                        // wait...what, sometimes the info length is too short!?
                        while( bytes[ q ] != '\n' && q < bytes.length )
                        {
                            q++;
                        }

                        // longer info line
                        final String text = new String( bytes, p, q - p ); // convert from bytes
                        p = q + 1;
                        q = p + 1;

                        final Portable port = PortFactory.createFromPortIndexFile( text );
                        if( port != Portable.NONE )
                        {   // text parsed ok
                            final String ci_portName = port.getCaseInsensitiveName();

                            // no name collisions occurred, this means we only get the lastest version from the file
                            if( PortsConstants.DEBUG && map.containsKey( ci_portName ) ) { System.out.println( port ); }

                            map.put( ci_portName, port );
                        }
                    }
                }
                catch( IOException ex )
                {
                    ex.printStackTrace();
                }
            }
            else
            {   // 2x slower but 10x more maintainable
                // misses the case where the port info crosses multiple lines
                try
                {
                    final Scanner scanner = new Scanner( filePath, "UTF-8" ); // *THROWS* FileNotFoundException
                    while( scanner.hasNext() == true ) // scan.useDelimiter( "\\n" ) <- this regex works but is not needed
                    {   // Scanner default is to read file line-by-line
                        final String line = scanner.nextLine(); // some lines are empty ""
                        if( line.length() > 40 )
                        {
                            final Portable port = PortFactory.createFromPortIndexFile( line );
                            if( port != Portable.NONE )
                            {   // line parsed ok
                                final String ci_portName = port.getCaseInsensitiveName();

                                // no name collisions occur, this means we only get the lastest version from the file
                                if( PortsConstants.DEBUG && map.containsKey( ci_portName ) ) { System.out.println( port ); }

                                map.put( ci_portName, port );
                            }
                        }
                        else
                        {   // wrong, just junk '\n' inside a {} Needs to keep going
                            // System.out.println( line );
                        }
                    }
                    scanner.close();
                }
                catch( FileNotFoundException ex ) // handled above with File.exist()
                {}
            }

            if( PortsConstants.DEBUG ) System.out.println( PortsCatalog.class.getSimpleName() +"->constructor parse ms="+ ( System.currentTimeMillis() - startMillisec ) );
        }

        return map;
    }

    public PortsDep getDeps() { return fPortsDep; }

    public PortsVariants getPortsVariants() { return fPortsVariants; }

    public PortsInventory getPortsInventory() { return fPortsInventory; }

    /**
     * Look up a port by case-insensitive name.
     *
     * @param portName treated as case-insensitive
     * @return Portable.NONE if not found, there are a couple dozen of these
     */
    public Portable parse( final String portName )
    {
        final Portable port = fCiName_to_PortMap.get( portName.toLowerCase() );
        return ( port != null )
                ? port
                : Portable.NONE;
    }

//    /* *
//     * Compares old port entries to freshly CLI interrogated ports.
//     * Called after "port echo $PSEUDO_NAME".
//     *
//     * @param fromCliChangeSet updated information from CLI is a CliPort
//     */
//    synchronized private Set<Portable> inform( final Set<Portable> fromCliChangeSet )
//    {
//        for( final Portable cliPort : fromCliChangeSet )
//        {
//            if( PortsConstants.DEBUG ) System.out.println( PortsCatalog.class.getName() +".inform("+  cliPort +')' );
//
//            final Portable prevPort = fCiName_to_PortMap.get( cliPort.getCaseInsensitiveName() );
//            fCiName_to_PortMap.put( cliPort.getCaseInsensitiveName(), cliPort );
//
//            final int i = Util.indexOfIdentity( prevPort, fAllPorts );
//            if( i != Util.INVALID_INDEX )
//            {   // valid index
//                fAllPorts[ i ] = cliPort;
//                //... port changed event for detail views?
//            }
//        }
//
//        return fromCliChangeSet;
//    }

    /* *
     *
     * @return in alphabetical order, all ports described in the "PortIndex" file
     */
//    synchronized public Portable[] getAllPorts() { return fAllPorts; }

    public long getModificationEpoch( final Portable port )
    {
        return ( vPortsDate != null )
                ? vPortsDate.getModificationEpoch( port )
                : -1L;
    }

    /**
     * Lengthy operation.
     */
    void scanDates()
    {
        vPortsDate = new PortsDate( this );
    }

    public long getLastSyncEpoch()
    {
        if( vPortsDate != null )
        {
            return vPortsDate.getLastSyncEpoch();
        }
        else
        {
            return -1L;
        }
    }

    /**
     * TESTING
     *
     * @param args
     */
    static public void main( String[] args )
    {
        // test
        final long startMillisec = System.currentTimeMillis();
        final PortsCatalog portsCatalog = new PortsCatalog();
//        final Portable[] dependPorts = portsCatalog.getDeps().getFullDependenciesOf( portsCatalog.parse( "graphviz" ) );
        // OR -> portsCatalog.parse( "graphviz" ).buildFullDependencies()
        System.out.println( PortsCatalog.class.getSimpleName() +".main() ms="+ ( System.currentTimeMillis() - startMillisec ) );
  //      System.out.println( dependPorts.length +"="+ Arrays.toString( dependPorts ) );
    }
}



// "graphviz" -> 87=[apr, apr-util, autoconf, automake, bzip2, cairo, cmake, curl-ca-bundle, cyrus-sasl2, db46, expat, fontconfig, freetype, gd2, gdbm, gettext, ghostscript, glib2, gobject-introspection, gperf, groff, gts, help2man, jasper, jbig2dec, jbigkit, jpeg, kerberos5, lcms2, libedit, libffi, libiconv, libidn, libLASi, libpaper, libpixman, libpng, libtool, libxml2, m4, ncurses, neon, netpbm, openssl, p5.12-locale-gettext, pango, perl5, perl5.12, pkgconfig, psutils, python27, python_select, serf1, sqlite3, subversion, texinfo, tiff, unzip, urw-fonts, Xft2, xorg-bigreqsproto, xorg-inputproto, xorg-kbproto, xorg-libice, xorg-libpthread-stubs, xorg-libsm, xorg-libX11, xorg-libXau, xorg-libXaw, xorg-libxcb, xorg-libXdmcp, xorg-libXext, xorg-libXmu, xorg-libXt, xorg-renderproto, xorg-util-macros, xorg-xcb-proto, xorg-xcb-util, xorg-xcmiscproto, xorg-xextproto, xorg-xf86bigfontproto, xorg-xproto, xorg-xtrans, xpm, xrender, xz, zlib]
// missing?
//
// CLI -> port echo rdepof:graphviz | wc -l
// 87
//    apr
//    apr-util
//    autoconf
//    automake
//    bzip2
//    cairo
//    cmake
//    curl-ca-bundle
//    cyrus-sasl2
//    db46
//    expat
//    fontconfig
//    freetype
//    gd2
//    gdbm
//    gettext
//    ghostscript
//    glib2
//    gobject-introspection
//    gperf
//    groff
//    gts
//    help2man
//    jasper
//    jbig2dec
//    jbigkit
//    jpeg
//    kerberos5
//    lcms2
//    libedit
//    libffi
//    libiconv
//    libidn
//    libLASi
//    libpaper
//    libpixman
//    libpng
//    libtool
//    libxml2
//    m4
//    ncurses
//    neon
//    netpbm
//    openssl
//    p5.12-locale-gettext
//    pango
//    perl5
//    perl5.12
//    pkgconfig
//    psutils
//    python27
//    python_select
//    serf1
//    sqlite3
//    subversion
//    texinfo
//    tiff
//    unzip
//    urw-fonts
//    Xft2
//    xorg-bigreqsproto
//    xorg-inputproto
//    xorg-kbproto
//    xorg-libice
//    xorg-libpthread-stubs
//    xorg-libsm
//    xorg-libX11
//    xorg-libXau
//    xorg-libXaw
//    xorg-libxcb
//    xorg-libXdmcp
//    xorg-libXext
//    xorg-libXmu
//    xorg-libXt
//    xorg-renderproto
//    xorg-util-macros
//    xorg-xcb-proto
//    xorg-xcb-util
//    xorg-xcmiscproto
//    xorg-xextproto
//    xorg-xf86bigfontproto
//    xorg-xproto
//    xorg-xtrans
//    xpm
//    xrender
//    xz
//    zlib
