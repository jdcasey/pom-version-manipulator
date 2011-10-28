/*
 * Copyright (c) 2011 Red Hat, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see 
 * <http://www.gnu.org/licenses>.
 */

package com.redhat.rcm.version.mgr.session;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.mae.project.key.FullProjectKey;
import org.apache.maven.mae.project.key.ProjectKey;
import org.apache.maven.mae.project.key.VersionlessProjectKey;
import org.apache.maven.mae.project.session.SimpleProjectToolsSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Repository;
import org.apache.maven.project.MavenProject;

import com.redhat.rcm.version.model.Project;
import com.redhat.rcm.version.model.ProjectAncestryGraph;
import com.redhat.rcm.version.util.ActivityLog;

public class VersionManagerSession
    extends SimpleProjectToolsSession
{

    public static final File GLOBAL = new File( "/" );

    private final List<Throwable> errors = new ArrayList<Throwable>();

    private final Map<File, ActivityLog> logs = new LinkedHashMap<File, ActivityLog>();

    private final Map<VersionlessProjectKey, Set<VersionlessProjectKey>> childPluginRefs =
        new HashMap<VersionlessProjectKey, Set<VersionlessProjectKey>>();

    private final Set<VersionlessProjectKey> currentProjects = new HashSet<VersionlessProjectKey>();

    private final File backups;

    private final File downloads;

    private final boolean preserveFiles;

    private final File workspace;

    private final File reports;

    private ProjectAncestryGraph ancestryGraph;

    private final String versionSuffix;

    private File settingsXml;

    private File capturePom;

    private final ManagedInfo managedInfo;

    private final MissingInfo missingInfo;

    public VersionManagerSession( final File workspace, final File reports, final String versionSuffix,
                                  final boolean preserveFiles )
    {
        this.workspace = workspace;
        this.reports = reports;
        this.versionSuffix = versionSuffix;

        backups = new File( workspace, "backups" );
        backups.mkdirs();

        downloads = new File( workspace, "downloads" );
        downloads.mkdirs();

        this.preserveFiles = preserveFiles;

        this.managedInfo = new ManagedInfo( this );
        this.missingInfo = new MissingInfo();
    }

    public String getVersionSuffix()
    {
        return versionSuffix;
    }

    public FullProjectKey getRelocation( final ProjectKey key )
    {
        return managedInfo.getRelocation( key );
    }

    public Map<File, ActivityLog> getLogs()
    {
        return logs;
    }

    public synchronized ActivityLog getLog( final File pom )
    {
        ActivityLog log = logs.get( pom );
        if ( log == null )
        {
            log = new ActivityLog();
            logs.put( pom, log );
        }

        return log;
    }

    public void addUnmanagedPlugin( final File pom, final ReportPlugin plugin )
    {
        Plugin p = new Plugin();
        p.setGroupId( plugin.getGroupId() );
        p.setArtifactId( plugin.getArtifactId() );
        p.setVersion( plugin.getVersion() );

        addUnmanagedPlugin( pom, p );
    }

    public synchronized VersionManagerSession addUnmanagedPlugin( final File pom, final Plugin plugin )
    {
        missingInfo.addUnmanagedPlugin( pom, plugin );

        return this;
    }

    public void addMissingParent( final Project project )
    {
        missingInfo.addMissingParent( project );
    }

    public synchronized VersionManagerSession addMissingDependency( final Project project, final Dependency dep )
    {
        missingInfo.addMissingDependency( project, dep );

        return this;
    }

    public VersionManagerSession addError( final Throwable error )
    {
        errors.add( error );
        return this;
    }

    public File getWorkspace()
    {
        return workspace;
    }

    public File getReports()
    {
        return reports;
    }

    public File getBackups()
    {
        return backups;
    }

    public File getDownloads()
    {
        return downloads;
    }

    public boolean isPreserveFiles()
    {
        return preserveFiles;
    }

    public Map<VersionlessProjectKey, Set<Plugin>> getUnmanagedPluginRefs()
    {
        return missingInfo.getUnmanagedPluginRefs();
    }

    public Map<File, Set<VersionlessProjectKey>> getUnmanagedPlugins()
    {
        return missingInfo.getUnmanagedPlugins();
    }

    public Set<VersionlessProjectKey> getUnmanagedPlugins( final File pom )
    {
        return missingInfo.getUnmanagedPlugins( pom );
    }

    public Set<Project> getProjectsWithMissingParent()
    {
        return missingInfo.getProjectsWithMissingParent();
    }

    public boolean isMissingParent( final Project project )
    {
        return missingInfo.isMissingParent( project );
    }

    public Map<VersionlessProjectKey, Set<Dependency>> getMissingDependencies()
    {
        return missingInfo.getMissingDependencies();
    }

    public Set<Dependency> getMissingDependencies( final VersionlessProjectKey key )
    {
        return missingInfo.getMissingDependencies( key );
    }

    public Map<VersionlessProjectKey, Set<File>> getMissingVersions()
    {
        return missingInfo.getMissingVersions();
    }

    public Set<VersionlessProjectKey> getMissingVersions( final ProjectKey key )
    {
        return missingInfo.getMissingVersions( key );
    }

    public List<Throwable> getErrors()
    {
        return errors;
    }

    public boolean hasDependencyMap()
    {
        return managedInfo.hasDependencyMap();
    }

    public String getArtifactVersion( final ProjectKey key )
    {
        return managedInfo.getArtifactVersion( key );
    }

    public Map<File, Map<VersionlessProjectKey, String>> getMappedDependenciesByBom()
    {
        return managedInfo.getMappedDependenciesByBom();
    }

    public Set<FullProjectKey> getBomCoords()
    {
        return managedInfo.getBomCoords();
    }

    public VersionManagerSession addBOM( final File bom, final MavenProject project )
    {
        managedInfo.addBOM( bom, project );

        return this;
    }

    public VersionManagerSession mapDependency( final File srcBom, final Dependency dep )
    {
        managedInfo.mapDependency( srcBom, dep );

        return this;
    }

    public Relocations getRelocations()
    {
        return managedInfo.getRelocations();
    }

    public VersionManagerSession setToolchain( final File toolchainFile, final MavenProject project )
    {
        managedInfo.setToolchain( toolchainFile, project );

        return this;
    }

    public VersionManagerSession setRemovedPlugins( final Collection<String> removedPlugins )
    {
        managedInfo.setRemovedPlugins( removedPlugins );

        return this;
    }

    public Set<VersionlessProjectKey> getRemovedPlugins()
    {
        return managedInfo.getRemovedPlugins();
    }

    public FullProjectKey getToolchainKey()
    {
        return managedInfo.getToolchainKey();
    }

    public Plugin getManagedPlugin( final VersionlessProjectKey key )
    {
        return managedInfo.getManagedPlugin( key );
    }

    public Map<VersionlessProjectKey, Plugin> getInjectedPlugins()
    {
        return managedInfo.getInjectedPlugins();
    }

    public Set<VersionlessProjectKey> getChildPluginReferences( final VersionlessProjectKey owner )
    {
        Set<VersionlessProjectKey> refs = childPluginRefs.get( owner );
        if ( refs == null )
        {
            refs = Collections.emptySet();
        }

        return refs;
    }

    public VersionManagerSession addChildPluginReference( final VersionlessProjectKey owner,
                                                          final VersionlessProjectKey plugin )
    {
        Set<VersionlessProjectKey> plugins = childPluginRefs.get( owner );
        if ( plugins == null )
        {
            plugins = new HashSet<VersionlessProjectKey>();
            childPluginRefs.put( owner, plugins );
        }

        plugins.add( plugin );

        return this;
    }

    public boolean isBom( final FullProjectKey key )
    {
        return managedInfo.hasBom( key );
    }

    // public boolean hasToolchainAncestor( final Project project )
    // {
    // return toolchainKey == null ? false : getAncestryGraph().hasAncestor( toolchainKey, project );
    // }
    //
    // public boolean hasParentInGraph( final Project project )
    // {
    // return getAncestryGraph().hasParentInGraph( project );
    // }

    public VersionManagerSession addProject( final Project project )
    {
        getAncestryGraph().connect( project );
        currentProjects.add( new VersionlessProjectKey( project.getKey() ) );

        return this;
    }

    private synchronized ProjectAncestryGraph getAncestryGraph()
    {
        if ( ancestryGraph == null )
        {
            ancestryGraph = new ProjectAncestryGraph( managedInfo.getToolchainKey() );
        }

        return ancestryGraph;
    }

    public boolean ancestryGraphContains( final FullProjectKey key )
    {
        return getAncestryGraph().contains( key );
    }

    public void setRemoteRepository( final String remoteRepository )
        throws MalformedURLException
    {
        String id = "vman";

        String u = remoteRepository;
        int idx = u.indexOf( '|' );
        if ( idx > 0 )
        {
            id = u.substring( 0, idx );
            u = u.substring( idx + 1 );
        }

        // URL url = new URL( u );
        //
        // Authentication auth = null;
        //
        // String ui = url.getUserInfo();
        // if ( ui != null )
        // {
        // idx = ui.indexOf( ':' );
        //
        // String user = ui;
        // String password = null;
        //
        // if ( idx > 0 )
        // {
        // user = ui.substring( 0, idx );
        //
        // if ( idx + 1 < ui.length() )
        // {
        // password = ui.substring( idx + 1 );
        // }
        // }
        //
        // auth = new Authentication( user, password );
        // }
        //
        // RemoteRepository repo = new RemoteRepository( id, "default", u );
        // if ( auth != null )
        // {
        // repo.setAuthentication( auth );
        // }

        Repository resolveRepo = new Repository();
        resolveRepo.setId( id );
        resolveRepo.setUrl( u );

        setResolveRepositories( resolveRepo );
        // setRemoteRepositoriesForResolution( Collections.singletonList( repo ) );
    }

    public boolean isToolchainReference( final Parent parent )
    {
        return managedInfo.isToolchainReference( parent );
    }

    public boolean inCurrentSession( final Parent parent )
    {
        return currentProjects.contains( new VersionlessProjectKey( parent ) );
    }

    public void setSettingsXml( final File settingsXml )
    {
        this.settingsXml = settingsXml;
    }

    public File getSettingsXml()
    {
        return settingsXml;
    }

    public void setCapturePom( final File capturePom )
    {
        this.capturePom = capturePom;
    }

    public File getCapturePom()
    {
        return capturePom;
    }

}