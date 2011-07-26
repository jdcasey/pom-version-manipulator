/*
 *  Copyright (C) 2011 John Casey.
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *  
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.redhat.rcm.version.mgr;

import static junit.framework.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.spi.Configurator;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.maven.mae.MAEException;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.ModelReader;
import org.codehaus.plexus.util.FileUtils;
import org.junit.After;
import org.junit.Before;

import com.redhat.rcm.version.VManException;
import com.redhat.rcm.version.model.FullProjectKey;

public abstract class AbstractVersionManagerTest
{

    protected VersionManager vman;

    protected final Set<File> toDelete = new HashSet<File>();

    protected File repo;

    protected File workspace;

    protected File reports;

    protected AbstractVersionManagerTest()
    {
    }

    @Before
    public void setupVersionManager()
        throws MAEException
    {
        vman = VersionManager.getInstance();
    }

    protected static void setupLogging( final Map<Class<?>, Level> levels )
    {
        System.out.println( "Setting up logging..." );
        final Configurator log4jConfigurator = new Configurator()
        {
            @Override
            @SuppressWarnings( "unchecked" )
            public void doConfigure( final URL notUsed, final LoggerRepository repo )
            {
                Level defaultLevel = Level.ERROR;

                final ConsoleAppender appender = new ConsoleAppender( new SimpleLayout() );
                appender.setImmediateFlush( true );
                appender.setThreshold( Level.WARN );

                repo.getRootLogger().removeAllAppenders();
                repo.getRootLogger().addAppender( appender );
                repo.getRootLogger().setLevel( defaultLevel );

                Set<String> processed = new HashSet<String>();
                if ( levels != null )
                {
                    for ( Map.Entry<Class<?>, Level> entry : levels.entrySet() )
                    {
                        String name = entry.getKey().getName();

                        Logger logger = repo.getLogger( name );
                        if ( logger != null )
                        {
                            logger.removeAllAppenders();
                            logger.addAppender( appender );
                            logger.setLevel( entry.getValue() );
                        }

                        processed.add( name );
                    }
                }

                final Enumeration<Logger> loggers = repo.getCurrentLoggers();
                while ( loggers.hasMoreElements() )
                {
                    final Logger logger = loggers.nextElement();
                    String name = logger.getName();

                    if ( !processed.contains( name ) )
                    {
                        logger.removeAllAppenders();
                        logger.addAppender( appender );

                        logger.setLevel( defaultLevel );
                        processed.add( name );
                    }
                }
            }
        };

        log4jConfigurator.doConfigure( null, LogManager.getLoggerRepository() );
    }

    @After
    public synchronized void deleteDirs()
    {
        if ( null == System.getProperty( "debug" ) )
        {
            for ( final File f : toDelete )
            {
                if ( f.exists() )
                {
                    try
                    {
                        FileUtils.forceDelete( f );
                    }
                    catch ( final IOException e )
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Before
    public void setupDirs()
        throws IOException
    {
        repo = createTempDir( "repository" );
        workspace = createTempDir( "workspace" );
        reports = createTempDir( "reports" );
    }

    protected void assertNoErrors( VersionManagerSession session )
    {
        Map<File, Set<Throwable>> errors = session.getErrors();
        if ( errors != null && !errors.isEmpty() )
        {
            for ( Map.Entry<File, Set<Throwable>> entry : errors.entrySet() )
            {
                System.out.printf( "%d errors encountered while processing file: %s\n\n", entry.getValue().size(),
                                   entry.getKey() );
                for ( Throwable error : entry.getValue() )
                {
                    error.printStackTrace();
                }
            }

            fail( "See above errors." );
        }
    }

    protected VersionManagerSession modifyRepo( final String... boms )
    {
        final VersionManagerSession session = newVersionManagerSession();

        vman.modifyVersions( repo, "**/*.pom", Arrays.asList( boms ), null, session );
        assertNoErrors( session );
        vman.generateReports( reports, session );

        return session;
    }

    protected VersionManagerSession newVersionManagerSession()
    {
        return new VersionManagerSession( workspace, reports, false, false );
    }

    protected File createTempDir( final String basename )
        throws IOException
    {
        final File temp = File.createTempFile( basename, ".dir" );
        temp.delete();

        temp.mkdirs();

        toDelete.add( temp );

        return temp;
    }

    protected static File getResourceFile( final String path )
    {
        final URL resource = Thread.currentThread().getContextClassLoader().getResource( path );
        if ( resource == null )
        {
            fail( "Resource not found: " + path );
        }

        return new File( resource.getPath() );
    }

    protected static Model loadModel( String path )
        throws IOException
    {
        final File pom = getResourceFile( path );
        return loadModel( pom );
    }

    protected static Model loadModel( File pom )
        throws IOException
    {
        Map<String, Object> options = new HashMap<String, Object>();
        options.put( ModelReader.IS_STRICT, Boolean.FALSE.toString() );

        return new DefaultModelReader().read( pom, options );
    }

    protected static FullProjectKey loadProjectKey( String path )
        throws VManException, IOException
    {
        Model model = loadModel( path );

        return new FullProjectKey( model );
    }

    protected static FullProjectKey loadProjectKey( File pom )
        throws VManException, IOException
    {
        Model model = loadModel( pom );

        return new FullProjectKey( model );
    }

    protected static Set<Model> loadModels( Set<File> poms )
        throws VManException, IOException
    {
        Set<Model> models = new LinkedHashSet<Model>( poms.size() );
        for ( File pom : poms )
        {
            models.add( loadModel( pom ) );
        }

        return models;
    }

}