/*
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.directory.server.standalone.daemon;


import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * 
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$
 */
public class Bootstrapper
{
    private static final Logger log = LoggerFactory.getLogger( Bootstrapper.class );
    private static final int CLASS_LOOKUP_FAILURE_EXITCODE = -1;
    private static final int INSTANTIATION_FAILURE_EXITCODE = -2;
    private static final int METHOD_LOOKUP_FAILURE_EXITCODE = -3;
    private static final int INITIALIZATION_FAILURE_EXITCODE = -4;
    private static final int START_FAILURE_EXITCODE = -5;
    private static final int STOP_FAILURE_EXITCODE = -6;
    private static final int BOOTSTRAP_PROPLOAD_FAILURE_EXITCODE = -7;
    private static final String BOOTSTRAP_START_CLASS_PROP = "bootstrap.start.class";
    private static final String BOOTSTRAP_STOP_CLASS_PROP = "bootstrap.stop.class";

    private static Bootstrapper instance;
    
    private final Properties bootstrapProperties = new Properties();
    private InstallationLayout install;
    private ClassLoader appLoader;
    private ClassLoader parentLoader;
    private Object bootstrapped;

    
    public void setInstallationLayout( String installationBase )
    {
        install = new InstallationLayout( installationBase );
        install.verifyInstallation();
        try
        {
            bootstrapProperties.load( new FileInputStream( install.getBootstrapperConfigurationFile() ) );
        }
        catch ( Exception e )
        {
            log.error( "", e );
            System.exit( BOOTSTRAP_PROPLOAD_FAILURE_EXITCODE );
        }
    }
    
    
    public void setParentLoader( ClassLoader parentLoader )
    {
        this.parentLoader = parentLoader;
        URL[] jars = install.getAllJars();
        this.appLoader = new URLClassLoader( jars, parentLoader );
        
        if ( log.isDebugEnabled() )
        {
            StringBuffer buf = new StringBuffer();
            buf.append( "urls in app loader: \n" );
            for ( int ii = 0; ii < jars.length; ii++ )
            {
                buf.append( "\t" ).append( jars[ii] ).append( "\n" );
            }
            log.debug( buf.toString() );
        }
    }


    public void callInit( String className )
    {
        Class clazz = null;
        Method op = null;
        
        Thread.currentThread().setContextClassLoader( appLoader );
        try
        {
            clazz = appLoader.loadClass( className );
        }
        catch ( ClassNotFoundException e )
        {
            log.error( "Could not find " + className, e );
            System.exit( CLASS_LOOKUP_FAILURE_EXITCODE );
        }
        
        try
        {
            bootstrapped = clazz.newInstance();
        }
        catch ( Exception e )
        {
            log.error( "Could not instantiate " + className, e );
            System.exit( INSTANTIATION_FAILURE_EXITCODE );
        }
        
        try
        {
            op = clazz.getMethod( "init", new Class[] { InstallationLayout.class } );
        }
        catch ( Exception e )
        {
            log.error( "Could not find init(InstallationLayout) method for " + className, e );
            System.exit( METHOD_LOOKUP_FAILURE_EXITCODE );
        }
        
        try
        {
            op.invoke( bootstrapped, new Object[] { this.install } );
        }
        catch ( Exception e )
        {
            log.error( "Failed on " + className + ".init(InstallationLayout)", e );
            System.exit( INITIALIZATION_FAILURE_EXITCODE );
        }
    }

    
    public void callStart()
    {
        Class clazz = bootstrapped.getClass();
        Method op = null;
        
        try
        {
            op = clazz.getMethod( "start", null );
        }
        catch ( Exception e )
        {
            log.error( "Could not find start() method for " + clazz.getName(), e );
            System.exit( METHOD_LOOKUP_FAILURE_EXITCODE );
        }
        
        try
        {
            op.invoke( bootstrapped, null );
        }
        catch ( Exception e )
        {
            log.error( "Failed on " + clazz.getName() + ".start()", e );
            System.exit( START_FAILURE_EXITCODE );
        }
    }
    

    public void callStop( String className )
    {
        Class clazz = null;
        Method op = null;
        
        try
        {
            clazz = appLoader.loadClass( className );
        }
        catch ( ClassNotFoundException e )
        {
            log.error( "Could not find " + className, e );
            System.exit( CLASS_LOOKUP_FAILURE_EXITCODE );
        }
        
        try
        {
            bootstrapped = clazz.newInstance();
        }
        catch ( Exception e )
        {
            log.error( "Could not instantiate " + className, e );
            System.exit( INSTANTIATION_FAILURE_EXITCODE );
        }
        
        try
        {
            op = clazz.getMethod( "stop", null );
        }
        catch ( Exception e )
        {
            log.error( "Could not find stop() method for " + className, e );
            System.exit( METHOD_LOOKUP_FAILURE_EXITCODE );
        }
        
        try
        {
            op.invoke( bootstrapped, null );
        }
        catch ( Exception e )
        {
            log.error( "Failed on " + className + ".stop()", e );
            System.exit( STOP_FAILURE_EXITCODE );
        }
    }

    
    // -----------------------------------------------------------------------
    
    
    public void init( String[] args )
    {
        if ( log.isDebugEnabled() )
        {
            StringBuffer buf = new StringBuffer();
            buf.append( "init(String[]) called with args: \n" );
            for ( int ii = 0; ii < args.length; ii++ )
            {
                buf.append( "\t" ).append( args[ii] ).append( "\n" );
            }
            log.debug( buf.toString() );
        }

        if ( install == null )
        {
            log.debug( "install was null: initializing it using first argument" );
            setInstallationLayout( args[0] );
            log.debug( "install initialized" );
        }
        else
        {
            log.debug( "install was not null" );
        }

        if ( parentLoader == null )
        {
            log.info( "Trying to get handle on system classloader as the parent" );
            setParentLoader( Thread.currentThread().getContextClassLoader() );
            log.info( "parentLoader = " + parentLoader );
        }
        
        callInit( bootstrapProperties.getProperty( BOOTSTRAP_START_CLASS_PROP, null ) );
        
        while( true )
        {
            try
            {
                Thread.sleep( 2000 );
            }
            catch ( InterruptedException e )
            {
                e.printStackTrace();
            }

            log.debug( "tick-tock" );
        }
    }
    
    
    public void stop() throws Exception
    {
        log.debug( "stop() called" );
        callStop( bootstrapProperties.getProperty( BOOTSTRAP_STOP_CLASS_PROP, null )  );
    }


    public void destroy()
    {
        log.debug( "destroy() called" );
    }


    public void start()
    {
        log.debug( "start() called" );
        Thread.currentThread().setContextClassLoader( parentLoader );
        callStart();
    }


    public void start( String[] args )
    {
        log.debug( "start(String[]) called" );
        Thread.currentThread().setContextClassLoader( this.parentLoader );
        
        if ( install == null && args.length > 0 )
        {
            setInstallationLayout( args[0] );
            setParentLoader( Thread.currentThread().getContextClassLoader() );
        }
    }

    
    // ------------------------------------------------------------------------
    // The main()
    // ------------------------------------------------------------------------


    public static void main( String[] args )
    {
        log.debug( "main(String[]) called" );
        
        // Noticed that some starts with jar2exe.exe pass in a null arguement list
        if ( args == null )
        {
            System.err.println( "Arguements are null - how come?" );
            log.error( "main() args were null shutting down!" );
            printHelp();
            System.exit( 1 );
        }

        if ( log.isDebugEnabled() )
        {
            log.debug( "main() recieved args:" );
            for ( int ii = 0; ii < args.length; ii++ )
            {
                log.debug( "args[" + ii + "] = " + args[ii] );
            }
        }

        if ( args.length > 1 )
        {
            if ( instance == null )
            {
                instance = new Bootstrapper();
                instance.setInstallationLayout( args[0] );
                instance.setParentLoader( Bootstrapper.class.getClassLoader() );
            }
        }
        else
        {
            String msg = "Server exiting without required installation.home or command.name.";
            System.err.println( msg );
            log.error( msg );
            printHelp();
            System.exit( 1 );
        }

        String command = null;
        if ( args.length > 0 )
        {
            command = args[args.length - 1];
        }
        else
        {
            String msg = "No command to start or stop was given?";
            System.err.println( msg );
            log.error( msg );
            printHelp();
            System.exit( 2 );
        }

        try
        {
            if ( command.equalsIgnoreCase( "start" ) )
            {
                log.debug( "calling init(String[]) from main(String[])" );
                instance.init( args );

                log.debug( "calling start(String[]) from main(String[])" );
                instance.start( args );
            }
            else if ( command.equalsIgnoreCase( "stop" ) )
            {
                log.debug( "calling stop() from main(String[])" );
                instance.stop();
                instance.destroy();
            }
            else
            {
                log.error( "Unrecognized command " + command );
                printHelp();
                System.exit( 3 );
            }
        }
        catch ( Throwable t )
        {
            t.printStackTrace();
            System.exit( 4 );
        }
    }


    private static void printHelp()
    {
        System.err.println("java -jar bootstrap.jar <app.home> <command.name>");
    }
}
