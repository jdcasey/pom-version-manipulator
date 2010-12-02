/*
 *  Copyright (C) 2010 John Casey.
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

import org.apache.log4j.Logger;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.WriterFactory;
import org.commonjava.emb.EMBException;
import org.commonjava.emb.app.AbstractEMBApplication;

import com.redhat.rcm.version.VManException;
import com.redhat.rcm.version.report.Report;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component( role = VersionManager.class )
public class VersionManager
    extends AbstractEMBApplication
{

    private static final Logger LOGGER = Logger.getLogger( VersionManager.class );

    @Requirement
    private ModelReader modelReader;

    @Requirement( role = Report.class )
    private Map<String, Report> reports;

    public VersionManager()
        throws EMBException
    {
        load();
    }

    public VersionManager( final ModelReader modelReader )
    {
        this.modelReader = modelReader;
    }

    public void generateReports( final File reportsDir, final VersionManagerSession sessionData )
    {
        if ( reports != null )
        {
            final Set<String> ids = new HashSet<String>();
            for ( final Map.Entry<String, Report> entry : reports.entrySet() )
            {
                final String id = entry.getKey();
                final Report report = entry.getValue();

                if ( !id.endsWith( "_" ) )
                {
                    try
                    {
                        ids.add( id );
                        report.generate( reportsDir, sessionData );
                    }
                    catch ( final VManException e )
                    {
                        LOGGER.error( "Failed to generate report: " + id, e );
                    }
                }
            }

            LOGGER.info( "Wrote reports: [" + StringUtils.join( ids.iterator(), ", " ) + "] to:\n\t" + reportsDir );
        }
    }

    public void modifyVersions( final File dir, final String pomNamePattern, final File bom,
                                final VersionManagerSession session )
    {
        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir( dir );
        scanner.addDefaultExcludes();
        scanner.setIncludes( new String[] { pomNamePattern } );

        scanner.scan();

        mapBOMDependencyManagement( bom, session );

        final String[] includedSubpaths = scanner.getIncludedFiles();
        for ( final String subpath : includedSubpaths )
        {
            final File pom = new File( dir, subpath );
            modVersions( pom, dir, session, session.isPreserveDirs() );
        }

        LOGGER.info( "Modifying POM versions in directory.\n\n\tDirectory: " + dir + "\n\tBOM: " + bom
                        + "\n\tPOM Backups: " + session.getBackups() + "\n\n" );
    }

    public void modifyVersions( final File pom, final File bom, final VersionManagerSession session )
    {
        mapBOMDependencyManagement( bom, session );
        final File out = modVersions( pom, pom.getParentFile(), session, true );
        if ( out != null )
        {
            LOGGER.info( "Modified POM versions.\n\n\tPOM: " + out + "\n\tBOM: " + bom + "\n\tPOM Backups: "
                            + session.getBackups() + "\n\n" );
        }
    }

    private File modVersions( final File pom, final File basedir, final VersionManagerSession session,
                              final boolean preserveDirs )
    {
        final Map<String, String> depMap = session.getDependencyMap();
        final Model model = buildModel( pom, session );

        boolean changed = modifyCoord( model, depMap, pom, session );
        if ( model.getDependencies() != null )
        {
            for ( final Dependency dep : model.getDependencies() )
            {
                changed = modifyDep( dep, depMap, pom, session, false ) || changed;
            }
        }

        if ( model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null )
        {
            for ( final Dependency dep : model.getDependencyManagement().getDependencies() )
            {
                changed = modifyDep( dep, depMap, pom, session, true ) || changed;
            }
        }

        if ( changed )
        {
            return writePom( model, pom, basedir, session, preserveDirs );
        }

        return null;
    }

    private File writePom( final Model model, final File pom, final File basedir, final VersionManagerSession session,
                           final boolean preserveDirs )
    {
        File backup = pom;

        String version = model.getVersion();
        if ( version == null && model.getParent() != null )
        {
            version = model.getParent().getVersion();
        }

        File outDir = pom.getParentFile();
        if ( !preserveDirs )
        {
            if ( outDir != null && !outDir.getName().equals( version ) )
            {
                try
                {
                    outDir = outDir.getCanonicalFile();
                }
                catch ( final IOException e )
                {
                    outDir = outDir.getAbsoluteFile();
                }

                final File parentDir = outDir.getParentFile();
                File newDir = null;

                if ( parentDir != null )
                {
                    newDir = new File( parentDir, version );
                }
                else
                {
                    newDir = new File( version );
                }

                outDir.renameTo( newDir );
                outDir = newDir;
            }
        }

        final File out = new File( outDir, model.getArtifactId() + "-" + version + ".pom" );

        final File backupDir = session.getBackups();
        if ( backupDir != null )
        {
            String path = pom.getParent();
            path = path.substring( basedir.getPath().length() );

            final File dir = new File( backupDir, path );
            if ( !dir.exists() && !dir.mkdirs() )
            {
                session.setError( pom, new VManException( "Failed to create backup subdirectory: %s" ) );
                return null;
            }

            backup = new File( dir, pom.getName() );
            try
            {
                session.getLog( pom ).add( "Writing backup: %s", backup );
                FileUtils.copyFile( pom, backup );
            }
            catch ( final IOException e )
            {
                session.setError( pom,
                                  new VManException( "Error making backup of POM: %s.\n\tTarget: %s\n\tReason: %s", e,
                                                     pom, backup, e.getMessage() ) );
                return null;
            }
        }

        Writer writer = null;
        try
        {
            session.getLog( pom ).add( "Writing modified POM: %s", out );
            writer = WriterFactory.newXmlWriter( out );
            new MavenXpp3Writer().write( writer, model );

            if ( !out.equals( pom ) )
            {
                session.getLog( pom ).add( "Deleting original POM: %s", pom );
                pom.delete();
            }
        }
        catch ( final IOException e )
        {
            session.setError( pom,
                              new VManException( "Failed to write modified POM to: %s\n\tReason: %s", e, out,
                                                 e.getMessage() ) );
        }
        finally
        {
            IOUtil.close( writer );
        }

        return out;
    }

    private boolean modifyDep( final Dependency dep, final Map<String, String> depMap, final File pom,
                               final VersionManagerSession session, final boolean isManaged )
    {
        boolean changed = false;

        final String key = dep.getManagementKey();
        String version = dep.getVersion();

        if ( version == null )
        {
            session.getLog( pom ).add( "NOT changing version for: %s%s. Version is inherited.", key,
                                       isManaged ? " [MANAGED]" : "" );
            return false;
        }

        version = depMap.get( key );
        if ( version != null )
        {
            if ( !version.equals( dep.getVersion() ) )
            {
                session.getLog( pom ).add( "Changing version for: %s%s.\n\tFrom: %s\n\tTo: %s.", key,
                                           isManaged ? " [MANAGED]" : "", dep.getVersion(), version );
                dep.setVersion( version );
                changed = true;
            }
            else
            {
                session.getLog( pom ).add( "Version for: %s%s is already correct.", key, isManaged ? " [MANAGED]" : "" );
            }
        }
        else
        {
            session.addMissingVersion( pom, key );
        }

        return changed;
    }

    private boolean modifyCoord( final Model model, final Map<String, String> depMap, final File pom,
                                 final VersionManagerSession session )
    {
        boolean changed = false;
        final Parent parent = model.getParent();

        String groupId = model.getGroupId();
        if ( groupId == null && parent != null )
        {
            groupId = parent.getGroupId();
        }

        if ( model.getVersion() != null )
        {
            final String key = groupId + ":" + model.getArtifactId() + ":pom";

            String version = model.getVersion();
            version = depMap.get( key );
            if ( version != null )
            {
                if ( !version.equals( model.getVersion() ) )
                {
                    session.getLog( pom ).add( "Changing POM version from: %s to: %s", model.getVersion(), version );
                    model.setVersion( version );
                    changed = true;
                }
                else
                {
                    session.getLog( pom ).add( "POM version is already in line with BOM: %s", model.getVersion() );
                }
            }
            else
            {
                session.addMissingVersion( pom, key );
                session.getLog( pom ).add( "POM version is missing in BOM: %s", key );
            }
        }

        if ( parent != null )
        {
            final String key = parent.getGroupId() + ":" + parent.getArtifactId() + ":pom";

            String version = parent.getVersion();
            if ( version == null )
            {
                session.setError( pom, new VManException( "INVALID POM: Missing parent version." ) );
                return false;
            }

            version = depMap.get( key );
            if ( version != null )
            {
                if ( !version.equals( model.getVersion() ) )
                {
                    session.getLog( pom ).add( "Changing POM parent (%s) version\n\tFrom: %s\n\tTo: %s", key,
                                               parent.getVersion(), version );
                    parent.setVersion( version );
                    changed = true;
                }
                else
                {
                    session.getLog( pom ).add( "POM parent (%s) version is correct: %s", key, parent.getVersion() );
                }
            }
            else
            {
                session.addMissingVersion( pom, key );
                session.getLog( pom ).add( "POM version is missing in BOM: %s", key );
            }
        }

        return changed;
    }

    private Map<String, String> mapBOMDependencyManagement( final File bom, final VersionManagerSession session )
    {
        Map<String, String> depMap = session.getDependencyMap();
        if ( depMap == null )
        {
            final Model model = buildModel( bom, session );
            depMap = new HashMap<String, String>();

            if ( model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null )
            {
                for ( final Dependency dep : model.getDependencyManagement().getDependencies() )
                {
                    depMap.put( dep.getGroupId() + ":" + dep.getArtifactId() + ":pom", dep.getVersion() );
                    depMap.put( dep.getManagementKey(), dep.getVersion() );
                }
            }

            session.setDependencyMap( depMap );
        }

        return depMap;
    }

    private Model buildModel( final File pom, final VersionManagerSession session )
    {
        final DefaultModelBuildingRequest mbr = new DefaultModelBuildingRequest();
        mbr.setPomFile( pom );

        final Map<String, ?> options = new HashMap<String, Object>();

        try
        {
            return modelReader.read( pom, options );
        }
        catch ( final IOException e )
        {
            session.setError( pom, e );
        }

        return null;
    }

    public String getId()
    {
        return "rh.vmod";
    }

    public String getName()
    {
        return "RedHat POM Version Modifier";
    }

}
