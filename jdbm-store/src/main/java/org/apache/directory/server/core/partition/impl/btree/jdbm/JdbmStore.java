/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.server.core.partition.impl.btree.jdbm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;

import jdbm.RecordManager;
import jdbm.helper.MRU;
import jdbm.recman.BaseRecordManager;
import jdbm.recman.CacheRecordManager;

import org.apache.directory.server.core.partition.Oid;
import org.apache.directory.server.core.partition.impl.btree.Index;
import org.apache.directory.server.core.partition.impl.btree.IndexAssertion;
import org.apache.directory.server.core.partition.impl.btree.IndexAssertionEnumeration;
import org.apache.directory.server.core.partition.impl.btree.IndexConfiguration;
import org.apache.directory.server.core.partition.impl.btree.IndexNotFoundException;
import org.apache.directory.server.core.partition.impl.btree.IndexRecord;
import org.apache.directory.server.schema.registries.AttributeTypeRegistry;
import org.apache.directory.server.schema.registries.OidRegistry;
import org.apache.directory.server.schema.registries.Registries;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.apache.directory.shared.ldap.exception.LdapNameNotFoundException;
import org.apache.directory.shared.ldap.exception.LdapSchemaViolationException;
import org.apache.directory.shared.ldap.message.AttributeImpl;
import org.apache.directory.shared.ldap.message.AttributesImpl;
import org.apache.directory.shared.ldap.message.ModificationItemImpl;
import org.apache.directory.shared.ldap.message.ResultCodeEnum;
import org.apache.directory.shared.ldap.name.LdapDN;
import org.apache.directory.shared.ldap.schema.AttributeType;
import org.apache.directory.shared.ldap.util.AttributeUtils;
import org.apache.directory.shared.ldap.util.NamespaceTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class JdbmStore
{
    private static final Logger log = LoggerFactory.getLogger( JdbmStore.class );

    private static final int DEFAULT_CACHE_SIZE = 10000;

    /** The objectClass name for aliases: 'alias' */
    private static final String ALIAS_OBJECT = "alias";

    /**
     * The aliased Dn attribute name: aliasedObjectName for LDAP and
     * aliasedEntryName or X.500.
     */
    private static final String ALIAS_ATTRIBUTE = "aliasedObjectName";


    /** the JDBM record manager used by this database */
    private RecordManager recMan;
    /** the normalized suffix DN of this backend database */
    private LdapDN normSuffix;
    /** the user provided suffix DN of this backend database */
    private LdapDN upSuffix;
    /** the working directory to use for files */
    private File workingDirectory;
    /** the master table storing entries by primary key */
    private JdbmMasterTable master;
    /** a map of attribute names to user indices */
    private Map<String, Index> indices;
    /** a map of index names to system indices */
    private Map<String, Index> sysIndices;

    /** true if open */
    private boolean initialized;
    /** true if we sync disks on every write operation */
    private boolean isSyncOnWrite = true;
    

    /** the normalized distinguished name index */
    private Index ndnIdx;
    /** the user provided distinguished name index */
    private Index updnIdx;
    /** the attribute existance index */
    private Index existanceIdx;
    /** the parent child relationship index */
    private Index hierarchyIdx;
    /** the one level scope alias index */
    private Index oneAliasIdx;
    /** the subtree scope alias index */
    private Index subAliasIdx;
    /** a system index on aliasedObjectName attribute */
    private Index aliasIdx;
    
    private static AttributeType OBJECT_CLASS_AT;
    private static AttributeType ALIAS_AT;
    
    private AttributeTypeRegistry attributeTypeRegistry;
    private OidRegistry oidRegistry;

    // ------------------------------------------------------------------------
    // C O N S T R U C T O R S
    // ------------------------------------------------------------------------

    /**
     * Creates a store based on JDBM B+Trees.
     */
    public JdbmStore()
    {
    }

    
    /**
     * Use this method to initialize the indices.  Only call this after
     * the registries and the optimizer have been enabled.  The '2' at the end
     * shows this is the 3rd init method called in the init sequence.
     * 
     * @param indices
     * @throws NamingException
     */
    protected void initIndices2(Set indices ) throws NamingException
    {
        Set<String> sysOidSet = new HashSet<String>();
        sysOidSet.add( Oid.EXISTANCE );
        sysOidSet.add( Oid.HIERARCHY );
        sysOidSet.add( Oid.UPDN );
        sysOidSet.add( Oid.NDN );
        sysOidSet.add( Oid.ONEALIAS );
        sysOidSet.add( Oid.SUBALIAS );
        sysOidSet.add( Oid.ALIAS );

        // Used to calculate the system indices we must automatically add
        Set<String> customAddedSystemIndices = new HashSet<String>();
        
        for ( Iterator ii = indices.iterator(); ii.hasNext(); /**/ )
        {
            /*
             * NOTE
             * ====
             * 
             * The object returned by the indexedAttributes property
             * of the configuration may include just a simple set of <String> 
             * names for the attributes being index OR may include a set 
             * of IndexConfiguration objects.
             * 
             * If the objects are strings extra information about the
             * cacheSize of an index is not available and so the default is
             * used.  If an IndexConfiguration is available then the custom
             * cacheSize is used.
             */
            
            Object nextObject = ii.next();
            String name = null;
            int cacheSize = IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE;
            int numDupLimit = IndexConfiguration.DEFAULT_DUPLICATE_LIMIT;
            
            // no custom cacheSize info is available so default sticks
            if ( nextObject instanceof String ) 
            {
                name = ( String ) nextObject;
                log.warn( "Using default cache size of {} for index on attribute {}", 
                    new Integer( cacheSize ), name );
            }
            // custom cache size is used
            else if ( nextObject instanceof IndexConfiguration )
            {
                IndexConfiguration indexConfiguration = ( IndexConfiguration ) nextObject;
                name = indexConfiguration.getAttributeId();
                cacheSize = indexConfiguration.getCacheSize();
                numDupLimit = indexConfiguration.getDuplicateLimit();
                
                if ( cacheSize <= 0 ) 
                {
                    log.warn( "Cache size {} for index on attribute is null or negative. Using default value.", 
                        new Integer(cacheSize), name );
                    cacheSize = IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE;
                }
                else
                {
                    log.info( "Using cache size of {} for index on attribute {}", 
                        new Integer( cacheSize ), name );
                }
                
                if ( cacheSize <= 0 ) 
                {
                    log.warn( "Duplicate limit {} for index on attribute is null or negative. Using default value.", 
                        new Integer(numDupLimit), name );
                    cacheSize = IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE;
                }
                else
                {
                    log.info( "Using duplicate limit of {} for index on attribute {}", 
                        new Integer( numDupLimit ), name );
                }
            }
            
            String oid = oidRegistry.getOid( name );
            AttributeType type = attributeTypeRegistry.lookup( oid );

            // check if attribute is a system attribute
            if ( sysOidSet.contains( oid ) )
            {
                if ( oid.equals( Oid.EXISTANCE ) )
                {
                    setExistanceIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.EXISTANCE );
                }
                else if ( oid.equals( Oid.HIERARCHY ) )
                {
                    setHierarchyIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.HIERARCHY );
                }
                else if ( oid.equals( Oid.UPDN ) )
                {
                    setUpdnIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.UPDN );
                }
                else if ( oid.equals( Oid.NDN ) )
                {
                    setNdnIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.NDN );
                }
                else if ( oid.equals( Oid.ONEALIAS ) )
                {
                    setOneAliasIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.ONEALIAS );
                }
                else if ( oid.equals( Oid.SUBALIAS ) )
                {
                    setSubAliasIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.SUBALIAS);
                }
                else if ( oid.equals( Oid.ALIAS ) )
                {
                    setAliasIndexOn( type, cacheSize, numDupLimit );
                    customAddedSystemIndices.add( Oid.ALIAS );
                }
                else
                {
                    throw new NamingException( "Unidentified system index " + oid );
                }
            }
            else
            {
                addIndexOn( type, cacheSize, numDupLimit );
            }
        }
        
        // -------------------------------------------------------------------
        // Add all system indices that were not custom configured by iterating
        // through all system index oids and checking of that index is 
        // contained within the customAddedSystemIndices set.  If it is not
        // contained in this set then the system index was not custom 
        // configured above and must be configured with defaults below.
        // -------------------------------------------------------------------
        
        for ( Iterator ii = sysOidSet.iterator(); ii.hasNext(); /**/ )
        {
            String systemIndexName = ( String ) ii.next();
            if ( ! customAddedSystemIndices.contains( systemIndexName ) )
            {
                AttributeType type = attributeTypeRegistry.lookup( systemIndexName );
                log.warn( "Using default cache size of {} for index on attribute {}", 
                    new Integer( IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE ), systemIndexName );
                if ( systemIndexName.equals( Oid.EXISTANCE ) )
                {
                    setExistanceIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE, 
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.HIERARCHY ) )
                {
                    setHierarchyIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.UPDN ) )
                {
                    setUpdnIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.NDN ) )
                {
                    setNdnIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.ONEALIAS ) )
                {
                    setOneAliasIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.SUBALIAS ) )
                {
                    setSubAliasIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else if ( systemIndexName.equals( Oid.ALIAS ) )
                {
                    setAliasIndexOn( type, IndexConfiguration.DEFAULT_INDEX_CACHE_SIZE,
                        IndexConfiguration.DEFAULT_DUPLICATE_LIMIT );
                }
                else
                {
                    throw new NamingException( "Unidentified system index " + systemIndexName );
                }
            }
        }
    }

    
    /**
     * Called last (4th) to check if the suffix entry has been created on disk,
     * and if not it is created.
     *  
     * @param suffix
     * @param entry
     * @throws NamingException
     */
    protected void initSuffixEntry3( String suffix, Attributes entry ) throws NamingException
    {
        // add entry for context, if it does not exist
        Attributes suffixOnDisk = getSuffixEntry();
        if ( suffixOnDisk == null )
        {
            LdapDN dn = new LdapDN( suffix );
            LdapDN normalizedSuffix = LdapDN.normalize( dn, attributeTypeRegistry.getNormalizerMapping() );
            add( normalizedSuffix, entry );
        }
    }

    
    public synchronized void init( JdbmStoreConfiguration config ) throws NamingException
    {
        isSyncOnWrite = config.isSyncOnWrite();

        oidRegistry = config.getOidRegistry();
        attributeTypeRegistry = config.getAttributeTypeRegistry();

        OBJECT_CLASS_AT = attributeTypeRegistry.lookup( SchemaConstants.OBJECT_CLASS_AT );
        ALIAS_AT = attributeTypeRegistry.lookup( ALIAS_ATTRIBUTE );
        
        this.upSuffix = new LdapDN( config.getSuffixDn() );
        this.normSuffix = LdapDN.normalize( upSuffix, attributeTypeRegistry.getNormalizerMapping() );

        workingDirectory = config.getWorkingDirectory();
        workingDirectory.mkdirs();

        try
        {
            String path = workingDirectory.getPath() + File.separator + "master";
            BaseRecordManager base = new BaseRecordManager( path );
            base.disableTransactions();
            
            int cacheSize = config.getCacheSize();
            if ( cacheSize < 0 )
            {
                cacheSize = DEFAULT_CACHE_SIZE;
                log.warn( "Using the default entry cache size of {} for {} partition", 
                    new Integer( cacheSize ), config.getName() );
            }
            else
            {
                log.info( "Using the custom configured cache size of {} for {} partition", 
                    new Integer( cacheSize ), config.getName() );
            }
            recMan = new CacheRecordManager( base, new MRU( cacheSize ) );
        }
        catch ( IOException e )
        {
            NamingException ne = new NamingException( "Could not initialize RecordManager" );
            ne.setRootCause( e );
            throw ne;
        }

        master = new JdbmMasterTable( recMan );
        indices = new HashMap<String,Index>();
        sysIndices = new HashMap<String,Index>();

        initIndices2( config.getIndexedAttributes() );
        initSuffixEntry3( config.getSuffixDn(), config.getContextEntry() );
        
        initialized = true;
    }


    public synchronized void destroy()
    {
        if ( !initialized )
        {
            return;
        }

        List<Index> array = new ArrayList<Index>();
        array.addAll( indices.values() );

        if ( null != ndnIdx )
        {
            array.add( ndnIdx );
        }

        if ( null != updnIdx )
        {
            array.add( updnIdx );
        }

        if ( null != aliasIdx )
        {
            array.add( aliasIdx );
        }

        if ( null != oneAliasIdx )
        {
            array.add( oneAliasIdx );
        }

        if ( null != subAliasIdx )
        {
            array.add( subAliasIdx );
        }

        if ( null != hierarchyIdx )
        {
            array.add( hierarchyIdx );
        }

        if ( null != existanceIdx )
        {
            array.add( existanceIdx );
        }

        Iterator list = array.iterator();

        while ( list.hasNext() )
        {
            Index index = ( Index ) list.next();

            try
            {
                index.close();
            }
            catch ( Throwable t )
            {
                log.error( "Failed to close an index.", t );
            }
        }

        try
        {
            master.close();
        }
        catch ( Throwable t )
        {
            log.error( "Failed to close the master.", t );
        }

        try
        {
            recMan.close();
        }
        catch ( Throwable t )
        {
            log.error( "Failed to close the record manager", t );
        }

        initialized = false;
    }


    public boolean isInitialized()
    {
        return initialized;
    }


    public synchronized void sync() throws NamingException
    {
        if ( !initialized )
        {
            return;
        }

        List<Index> array = new ArrayList<Index>();
        array.addAll( indices.values() );
        array.add( ndnIdx );
        array.add( updnIdx );
        array.add( aliasIdx );
        array.add( oneAliasIdx );
        array.add( subAliasIdx );
        array.add( hierarchyIdx );
        array.add( existanceIdx );

        Iterator list = array.iterator();

        // Sync all user defined indices
        while ( list.hasNext() )
        {
            Index idx = ( Index ) list.next();

            idx.sync();
        }

        master.sync();

        try
        {
            recMan.commit();
        }
        catch ( Throwable t )
        {
            throw ( NamingException ) new NamingException( "Failed to commit changes to the record manager." )
                .initCause( t );
        }
    }


    // ------------------------------------------------------------------------
    // I N D E X   M E T H O D S
    // ------------------------------------------------------------------------

    public void addIndexOn( AttributeType spec, int cacheSize, int numDupLimit ) throws NamingException
    {
        Index idx = new JdbmIndex( spec, workingDirectory, cacheSize, numDupLimit );
        indices.put( spec.getOid(), idx );
    }


    public Index getExistanceIndex()
    {
        return existanceIdx;
    }


    public void setExistanceIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( existanceIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        existanceIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), existanceIdx );
    }


    public Index getHierarchyIndex()
    {
        return hierarchyIdx;
    }


    public void setHierarchyIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( hierarchyIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        hierarchyIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), hierarchyIdx );
    }


    public Index getAliasIndex()
    {
        return aliasIdx;
    }


    public void setAliasIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( aliasIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        aliasIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), aliasIdx );
    }


    public Index getOneAliasIndex()
    {
        return oneAliasIdx;
    }


    public void setOneAliasIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( oneAliasIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        oneAliasIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), oneAliasIdx );
    }


    public Index getSubAliasIndex()
    {
        return subAliasIdx;
    }


    public void setSubAliasIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( subAliasIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        subAliasIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), subAliasIdx );
    }


    public Index getUpdnIndex()
    {
        return updnIdx;
    }


    public void setUpdnIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( updnIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        updnIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), updnIdx );
    }


    public Index getNdnIndex()
    {
        return ndnIdx;
    }


    public void setNdnIndexOn( AttributeType attrType, int cacheSize, int numDupLimit ) throws NamingException
    {
        if ( ndnIdx != null )
        {
            NamingException e = new NamingException( "Index already set!" );
            throw e;
        }

        ndnIdx = new JdbmIndex( attrType, workingDirectory, cacheSize, numDupLimit );
        sysIndices.put( attrType.getOid(), ndnIdx );
    }


    public Iterator getUserIndices()
    {
        return indices.keySet().iterator();
    }


    public Iterator getSystemIndices()
    {
        return sysIndices.keySet().iterator();
    }


    public boolean hasUserIndexOn( String id ) throws NamingException
    {
        return indices.containsKey( oidRegistry.getOid( id ) );
    }


    public boolean hasSystemIndexOn( String id ) throws NamingException
    {
        return sysIndices.containsKey( oidRegistry.getOid( id ) );
    }


    /**
     * @see org.apache.directory.server.core.partition.impl.btree.BTreePartition#getUserIndex(String)
     */
    public Index getUserIndex( String id ) throws IndexNotFoundException
    {
        try
        {
            id = oidRegistry.getOid( id );
        }
        catch ( NamingException e )
        {
            log.error( "Failed to identify OID for: " + id, e );
            throw new IndexNotFoundException( "Failed to identify OID for: " + id, id, e );
        }

        if ( indices.containsKey( id ) )
        {
            return ( Index ) indices.get( id );
        }
        else
        {
            String name = "unknown";
            try
            {
                name = oidRegistry.getPrimaryName( id );
            }
            catch ( NamingException e )
            {
                String msg = "Failed to resolve primary name for " + id + " in user index lookup";
                log.error( msg, e );
                throw new IndexNotFoundException( msg, id, e );
            }
            
            throw new IndexNotFoundException( "A user index on attribute " + id + " (" 
                + name + ") does not exist!" );
        }
    }


    /**
     * @see BTreePartition#getEntryId(String)
     */
    public Index getSystemIndex( String id ) throws IndexNotFoundException
    {
        try
        {
            id = oidRegistry.getOid( id );
        }
        catch ( NamingException e )
        {
            log.error( "Failed to identify OID for: " + id, e );
            throw new IndexNotFoundException( "Failed to identify OID for: " + id, id, e );
        }


        if ( sysIndices.containsKey( id ) )
        {
            return ( Index ) sysIndices.get( id );
        }
        else
        {
            String name = "unknown";
            try
            {
                name = oidRegistry.getPrimaryName( id );
            }
            catch ( NamingException e )
            {
                String msg = "Failed to resolve primary name for " + id + " in user index lookup";
                log.error( msg, e );
                throw new IndexNotFoundException( msg, id, e );
            }
            
            throw new IndexNotFoundException( "A system index on attribute " + id + " (" 
                + name + ") does not exist!" );
        }
    }


    public Long getEntryId( String dn ) throws NamingException
    {
        return (Long)ndnIdx.forwardLookup( dn );
    }


    public String getEntryDn( Long id ) throws NamingException
    {
        return ( String ) ndnIdx.reverseLookup( id );
    }


    public Long getParentId( String dn ) throws NamingException
    {
        Long childId = (Long)ndnIdx.forwardLookup( dn );
        return ( Long ) hierarchyIdx.reverseLookup( childId );
    }


    public Long getParentId( Long childId ) throws NamingException
    {
        return ( Long ) hierarchyIdx.reverseLookup( childId );
    }


    public String getEntryUpdn( Long id ) throws NamingException
    {
        return ( String ) updnIdx.reverseLookup( id );
    }


    public String getEntryUpdn( String dn ) throws NamingException
    {
        Long id = (Long)ndnIdx.forwardLookup( dn );
        return ( String ) updnIdx.reverseLookup( id );
    }


    public int count() throws NamingException
    {
        return master.count();
    }


    /**
     * Removes the index entries for an alias before the entry is deleted from
     * the master table.
     * 
     * @todo Optimize this by walking the hierarchy index instead of the name 
     * @param aliasId the id of the alias entry in the master table
     * @throws NamingException if we cannot delete the indices
     */
    private void dropAliasIndices( Long aliasId ) throws NamingException
    {
        String targetDn = ( String ) aliasIdx.reverseLookup( aliasId );
        Long targetId = getEntryId( targetDn );
        String aliasDn = getEntryDn( aliasId );
        LdapDN ancestorDn = ( LdapDN ) new LdapDN( aliasDn ).getPrefix( 1 );
        Long ancestorId = getEntryId( ancestorDn.toString() );

        /*
         * We cannot just drop all tuples in the one level and subtree indices
         * linking baseIds to the targetId.  If more than one alias refers to
         * the target then droping all tuples with a value of targetId would
         * make all other aliases to the target inconsistent.
         * 
         * We need to walk up the path of alias ancestors until we reach the 
         * upSuffix, deleting each ( ancestorId, targetId ) tuple in the
         * subtree scope alias.  We only need to do this for the direct parent
         * of the alias on the one level subtree.
         */
        oneAliasIdx.drop( ancestorId, targetId );
        subAliasIdx.drop( ancestorId, targetId );

        while ( !ancestorDn.equals( normSuffix ) )
        {
            ancestorDn = ( LdapDN ) ancestorDn.getPrefix( 1 );
            ancestorId = getEntryId( ancestorDn.toString() );

            subAliasIdx.drop( ancestorId, targetId );
        }

        // Drops all alias tuples pointing to the id of the alias to be deleted
        aliasIdx.drop( aliasId );
    }


    /**
     * Adds indices for an aliasEntry to be added to the database while checking
     * for constrained alias constructs like alias cycles and chaining.
     * 
     * @param aliasDn normalized distinguished name for the alias entry
     * @param aliasTarget the user provided aliased entry dn as a string
     * @param aliasId the id of alias entry to add
     * @throws NamingException if index addition fails, of the alias is not 
     * allowed due to chaining or cycle formation.
     */
    private void addAliasIndices( Long aliasId, LdapDN aliasDn, String aliasTarget ) throws NamingException
    {
        LdapDN normalizedAliasTargetDn = null; // Name value of aliasedObjectName
        Long targetId = null; // Id of the aliasedObjectName
        LdapDN ancestorDn = null; // Name of an alias entry relative
        Long ancestorId = null; // Id of an alias entry relative

        // Access aliasedObjectName, normalize it and generate the Name 
        normalizedAliasTargetDn = new LdapDN( aliasTarget );
        normalizedAliasTargetDn.normalize( attributeTypeRegistry.getNormalizerMapping() );

        /*
         * Check For Cycles
         * 
         * Before wasting time to lookup more values we check using the target
         * dn to see if we have the possible formation of an alias cycle.  This
         * happens when the alias refers back to a target that is also a 
         * relative of the alias entry.  For detection we test if the aliased
         * entry Dn starts with the target Dn.  If it does then we know the 
         * aliased target is a relative and we have a perspecitive cycle.
         */
        if ( aliasDn.startsWith( normalizedAliasTargetDn ) )
        {
            if ( aliasDn.equals( normalizedAliasTargetDn ) )
            {
                throw new NamingException( "[36] aliasDereferencingProblem - " + "attempt to create alias to itself." );
            }

            throw new NamingException( "[36] aliasDereferencingProblem - "
                + "attempt to create alias with cycle to relative " + aliasTarget
                + " not allowed from descendent alias " + aliasDn );
        }

        /*
         * Check For Aliases External To Naming Context
         * 
         * id may be null but the alias may be to a valid entry in 
         * another namingContext.  Such aliases are not allowed and we
         * need to point it out to the user instead of saying the target
         * does not exist when it potentially could outside of this upSuffix.
         */
        if ( !normalizedAliasTargetDn.startsWith( normSuffix ) )
        {
            // Complain specifically about aliases to outside naming contexts
            throw new NamingException( "[36] aliasDereferencingProblem -"
                + " the alias points to an entry outside of the " + upSuffix.getUpName()
                + " namingContext to an object whose existance cannot be" + " determined." );
        }

        // L O O K U P   T A R G E T   I D
        targetId = (Long)ndnIdx.forwardLookup( normalizedAliasTargetDn.toNormName() );

        /*
         * Check For Target Existance
         * 
         * We do not allow the creation of inconsistant aliases.  Aliases should
         * not be broken links.  If the target does not exist we start screaming
         */
        if ( null == targetId )
        {
            // Complain about target not existing
            throw new NamingException( "[33] aliasProblem - "
                + "the alias when dereferenced would not name a known object "
                + "the aliasedObjectName must be set to a valid existing " + "entry." );
        }

        /*
         * Detect Direct Alias Chain Creation
         * 
         * Rather than resusitate the target to test if it is an alias and fail
         * due to chaing creation we use the alias index to determine if the
         * target is an alias.  Hence if the alias we are about to create points
         * to another alias as its target in the aliasedObjectName attribute, 
         * then we have a situation where an alias chain is being created.  
         * Alias chaining is not allowed so we throw and exception. 
         */
        if ( null != aliasIdx.reverseLookup( targetId ) )
        {
            // Complain about illegal alias chain
            throw new NamingException( "[36] aliasDereferencingProblem -"
                + " the alias points to another alias.  Alias chaining is" + " not supported by this backend." );
        }

        // Add the alias to the simple alias index
        aliasIdx.add( normalizedAliasTargetDn.getNormName(), aliasId );

        /*
         * Handle One Level Scope Alias Index
         * 
         * The first relative is special with respect to the one level alias
         * index.  If the target is not a sibling of the alias then we add the
         * index entry maping the parent's id to the aliased target id.
         */
        ancestorDn = ( LdapDN ) aliasDn.clone();
        ancestorDn.remove( aliasDn.size() - 1 );
        ancestorId = getEntryId( ancestorDn.toNormName() );

        if ( !NamespaceTools.isSibling( normalizedAliasTargetDn, aliasDn ) )
        {
            oneAliasIdx.add( ancestorId, targetId );
        }

        /*
         * Handle Sub Level Scope Alias Index
         * 
         * Walk the list of relatives from the parents up to the upSuffix, testing
         * to see if the alias' target is a descendant of the relative.  If the
         * alias target is not a descentant of the relative it extends the scope
         * and is added to the sub tree scope alias index.  The upSuffix node is
         * ignored since everything is under its scope.  The first loop 
         * iteration shall handle the parents.
         */
        while ( !ancestorDn.equals( normSuffix ) && null != ancestorId )
        {
            if ( !NamespaceTools.isDescendant( ancestorDn, normalizedAliasTargetDn ) )
            {
                subAliasIdx.add( ancestorId, targetId );
            }

            ancestorDn.remove( ancestorDn.size() - 1 );
            ancestorId = getEntryId( ancestorDn.toNormName() );
        }
    }

    
    public void add( LdapDN normName, Attributes entry ) throws NamingException
    {
        Long id;
        Long parentId = null;

        id = master.getNextId();

        //
        // Suffix entry cannot have a parent since it is the root so it is 
        // capped off using the zero value which no entry can have since 
        // entry sequences start at 1.
        //

        LdapDN parentDn = null;
        if ( normName.equals( normSuffix ) )
        {
            parentId = 0L;
        }
        else
        {
            parentDn = ( LdapDN ) normName.clone();
            parentDn.remove( parentDn.size() - 1 );
            parentId = getEntryId( parentDn.toString() );
        }

        // don't keep going if we cannot find the parent Id
        if ( parentId == null )
        {
            throw new LdapNameNotFoundException( "Id for parent '" + parentDn + "' not found!" );
        }

        Attribute objectClass = AttributeUtils.getAttribute( entry, OBJECT_CLASS_AT );

        if ( objectClass == null )
        {
            String msg = "Entry " + normName.getUpName() + " contains no objectClass attribute: " + entry;
            throw new LdapSchemaViolationException( msg, ResultCodeEnum.OBJECT_CLASS_VIOLATION );
        }

        // Start adding the system indices
        // Why bother doing a lookup if this is not an alias.

        if ( objectClass.contains( ALIAS_OBJECT ) )
        {
            Attribute aliasAttr = AttributeUtils.getAttribute( entry, ALIAS_AT );
            addAliasIndices( id, normName, ( String ) aliasAttr.get() );
        }

        ndnIdx.add( normName.toNormName(), id );
        updnIdx.add( normName.getUpName(), id );
        hierarchyIdx.add( parentId, id );

        // Now work on the user defined indices
        NamingEnumeration list = entry.getIDs();
        while ( list.hasMore() )
        {
            String attributeId = ( String ) list.next();
            String attributeOid = oidRegistry.getOid( attributeId );

            if ( hasUserIndexOn( attributeOid ) )
            {
                Index idx = getUserIndex( attributeOid );
                
                // here lookup by attributeId is ok since we got attributeId from 
                // the entry via the enumeration - it's in there as is for sure
                NamingEnumeration values = entry.get( attributeId ).getAll();

                while ( values.hasMore() )
                {
                    idx.add( values.next(), id );
                }

                // Adds only those attributes that are indexed
                existanceIdx.add( attributeOid, id );
            }
        }

        master.put( entry, id );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    public Attributes lookup( Long id ) throws NamingException
    {
        return master.get( id );
    }


    public void delete( Long id ) throws NamingException
    {
        Attributes entry = lookup( id );
        Long parentId = getParentId( id );
        NamingEnumeration attrs = entry.getIDs();

        Attribute objectClass = AttributeUtils.getAttribute( entry, OBJECT_CLASS_AT );
        if ( objectClass.contains( ALIAS_OBJECT ) )
        {
            dropAliasIndices( id );
        }

        ndnIdx.drop( id );
        updnIdx.drop( id );
        hierarchyIdx.drop( id );

        // Remove parent's reference to entry only if entry is not the upSuffix
        if ( !parentId.equals( 0L ) )
        {
            hierarchyIdx.drop( parentId, id );
        }

        while ( attrs.hasMore() )
        {
            String attributeId = ( ( String ) attrs.next() );
            String attributeOid = oidRegistry.getOid( attributeId );

            if ( hasUserIndexOn( attributeOid ) )
            {
                Index index = getUserIndex( attributeOid );

                // here lookup by attributeId is ok since we got attributeId from 
                // the entry via the enumeration - it's in there as is for sure
                NamingEnumeration values = entry.get( attributeId ).getAll();

                while ( values.hasMore() )
                {
                    index.drop( values.next(), id );
                }

                existanceIdx.drop( attributeOid, id );
            }
        }

        master.delete( id );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    public NamingEnumeration list( Long id ) throws NamingException
    {
        return hierarchyIdx.listIndices( id );
    }


    public int getChildCount( Long id ) throws NamingException
    {
        return hierarchyIdx.count( id );
    }


    public LdapDN getSuffix()
    {
        return normSuffix;
    }

    public LdapDN getUpSuffix()
    {
        return upSuffix;
    }


    public Attributes getSuffixEntry() throws NamingException
    {
        Long id = getEntryId( normSuffix.toNormName() );

        if ( null == id )
        {
            return null;
        }

        return lookup( id );
    }


    public void setProperty( String propertyName, String propertyValue ) throws NamingException
    {
        master.setProperty( propertyName, propertyValue );
    }


    public String getProperty( String propertyName ) throws NamingException
    {
        return master.getProperty( propertyName );
    }


    public Attributes getIndices( Long id ) throws NamingException
    {
        Attributes attributes = new AttributesImpl();

        // Get the distinguishedName to id mapping
        attributes.put( "_nDn", getEntryDn( id ) );
        attributes.put( "_upDn", getEntryUpdn( id ) );
        attributes.put( "_parent", getParentId( id ) );

        // Get all standard index attribute to value mappings
        Iterator idxList = this.indices.values().iterator();
        while ( idxList.hasNext() )
        {
            Index index = ( Index ) idxList.next();
            NamingEnumeration list = index.listReverseIndices( id );
            while ( list.hasMore() )
            {
                IndexRecord rec = ( IndexRecord ) list.next();
                Object val = rec.getIndexKey();
                String attrId = index.getAttribute().getName();
                Attribute attr = attributes.get( attrId );
                if ( attr == null )
                {
                    attr = new AttributeImpl( attrId );
                }
                attr.add( val );
                attributes.put( attr );
            }
        }

        // Get all existance mappings for this id creating a special key
        // that looks like so 'existance[attribute]' and the value is set to id
        NamingEnumeration list = existanceIdx.listReverseIndices( id );
        StringBuffer val = new StringBuffer();
        while ( list.hasMore() )
        {
            IndexRecord rec = ( IndexRecord ) list.next();
            val.append( "_existance[" );
            val.append( rec.getIndexKey() );
            val.append( "]" );

            String valStr = val.toString();
            Attribute attr = attributes.get( valStr );
            if ( attr == null )
            {
                attr = new AttributeImpl( valStr );
            }
            attr.add( rec.getEntryId() );
            attributes.put( attr );
            val.setLength( 0 );
        }

        // Get all parent child mappings for this entry as the parent using the
        // key 'child' with many entries following it.
        list = hierarchyIdx.listIndices( id );
        Attribute childAttr = new AttributeImpl( "_child" );
        attributes.put( childAttr );
        while ( list.hasMore() )
        {
            IndexRecord rec = ( IndexRecord ) list.next();
            childAttr.add( rec.getEntryId() );
        }

        return attributes;
    }


    /**
     * Adds a set of attribute values while affecting the appropriate indices. 
     * The entry is not persisted: it is only changed in anticipation for a put 
     * into the master table.
     *
     * @param id the primary key of the entry
     * @param entry the entry to alter
     * @param mods the attribute and values to add 
     * @throws NamingException if index alteration or attribute addition
     * fails.
     */
    private void add( Long id, Attributes entry, Attribute mods ) throws NamingException
    {
        String modsOid = oidRegistry.getOid( mods.getID() );
        
        if ( hasUserIndexOn( modsOid ) )
        {
            Index idx = getUserIndex( modsOid );
            idx.add( mods, id );

            // If the attr didn't exist for this id add it to existance index
            if ( !existanceIdx.hasValue( modsOid, id ) )
            {
                existanceIdx.add( modsOid, id );
            }
        }

        // add all the values in mods to the same attribute in the entry
        AttributeType type = attributeTypeRegistry.lookup( modsOid );
        Attribute entryAttrToAddTo = AttributeUtils.getAttribute( entry, type );

        if ( entryAttrToAddTo == null )
        {
            entryAttrToAddTo = new AttributeImpl( mods.getID() );
            entry.put( entryAttrToAddTo );
        }

        for ( int ii = 0; ii < mods.size(); ii++ )
        {
            entryAttrToAddTo.add( mods.get( ii ) );
        }

        if ( modsOid.equals( oidRegistry.getOid( ALIAS_ATTRIBUTE ) ) )
        {
            String ndnStr = ( String ) ndnIdx.reverseLookup( id );
            addAliasIndices( id, new LdapDN( ndnStr ), ( String ) mods.get() );
        }
    }


    /**
     * Completely removes the set of values for an attribute having the values 
     * supplied while affecting the appropriate indices.  The entry is not 
     * persisted: it is only changed in anticipation for a put into the master 
     * table.  Note that an empty attribute w/o values will remove all the 
     * values within the entry where as an attribute w/ values will remove those
     * attribute values it contains.
     *
     * @param id the primary key of the entry
     * @param entry the entry to alter
     * @param mods the attribute and its values to delete
     * @throws NamingException if index alteration or attribute modification 
     * fails.
     */
    private void remove( Long id, Attributes entry, Attribute mods ) throws NamingException
    {
        String modsOid = oidRegistry.getOid( mods.getID() );
        
        if ( hasUserIndexOn( modsOid ) )
        {
            Index idx = getUserIndex( modsOid );
            idx.drop( mods, id );

            /* 
             * If no attribute values exist for this entryId in the index then
             * we remove the existance index entry for the removed attribute.
             */
            if ( null == idx.reverseLookup( id ) )
            {
                existanceIdx.drop( modsOid, id );
            }
        }

        AttributeType attrType = attributeTypeRegistry.lookup( modsOid );
        /*
         * If there are no attribute values in the modifications then this 
         * implies the compelete removal of the attribute from the entry. Else
         * we remove individual attribute values from the entry in mods one 
         * at a time.
         */
        if ( mods.size() == 0 )
        {
            AttributeUtils.removeAttribute( attrType, entry );
        }
        else
        {
            Attribute entryAttr = AttributeUtils.getAttribute( entry, attrType );
            NamingEnumeration values = mods.getAll();
            while ( values.hasMore() )
            {
                entryAttr.remove( values.next() );
            }

            // if nothing is left just remove empty attribute
            if ( entryAttr.size() == 0 )
            {
                entry.remove( entryAttr.getID() );
            }
        }

        // Aliases->single valued comp/partial attr removal is not relevant here
        if ( modsOid.equals( oidRegistry.getOid( ALIAS_ATTRIBUTE ) ) )
        {
            dropAliasIndices( id );
        }
    }


    /**
     * Completely replaces the existing set of values for an attribute with the
     * modified values supplied affecting the appropriate indices.  The entry
     * is not persisted: it is only changed in anticipation for a put into the
     * master table.
     *
     * @param id the primary key of the entry
     * @param entry the entry to alter
     * @param mods the replacement attribute and values
     * @throws NamingException if index alteration or attribute modification 
     * fails.
     */
    private void replace( Long id, Attributes entry, Attribute mods ) throws NamingException
    {
        String modsOid = oidRegistry.getOid( mods.getID() );
        
        if ( hasUserIndexOn( modsOid ) )
        {
            Index idx = getUserIndex( modsOid );

            // Drop all existing attribute value index entries and add new ones
            idx.drop( id );
            idx.add( mods, id );

            /* 
             * If no attribute values exist for this entryId in the index then
             * we remove the existance index entry for the removed attribute.
             */
            if ( null == idx.reverseLookup( id ) )
            {
                existanceIdx.drop( modsOid, id );
            }
        }

        String aliasAttributeOid = oidRegistry.getOid( ALIAS_ATTRIBUTE );
        if ( modsOid.equals( aliasAttributeOid ) )
        {
            dropAliasIndices( id );
        }

        // replaces old attributes with new modified ones if they exist
        if ( mods.size() > 0 )
        {
            entry.put( mods );
        }
        else  // removes old attributes if new replacements do not exist
        {
            entry.remove( mods.getID() );
        }

        if ( modsOid.equals( aliasAttributeOid ) && mods.size() > 0 )
        {
            String ndnStr = ( String ) ndnIdx.reverseLookup( id );
            addAliasIndices( id, new LdapDN( ndnStr ), ( String ) mods.get() );
        }
    }


    public void modify( LdapDN dn, int modOp, Attributes mods ) throws NamingException
    {
        NamingEnumeration attrs = null;
        Long id = getEntryId( dn.toString() );
        Attributes entry = master.get( id );

        switch ( modOp )
        {
            case ( DirContext.ADD_ATTRIBUTE  ):
                attrs = mods.getIDs();

                while ( attrs.hasMore() )
                {
                    String attrId = ( String ) attrs.next();
                    Attribute attr = mods.get( attrId );
                    add( id, entry, attr );
                }

                break;
            case ( DirContext.REMOVE_ATTRIBUTE  ):
                attrs = mods.getIDs();

                while ( attrs.hasMore() )
                {
                    String attrId = ( String ) attrs.next();
                    Attribute attr = mods.get( attrId );
                    remove( id, entry, attr );
                }

                break;
            case ( DirContext.REPLACE_ATTRIBUTE  ):
                attrs = mods.getIDs();

                while ( attrs.hasMore() )
                {
                    String attrId = ( String ) attrs.next();
                    Attribute attr = mods.get( attrId );
                    replace( id, entry, attr );
                }

                break;
            default:
                throw new NamingException( "Unidentified modification operation" );
        }

        master.put( entry, id );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    public void modify( LdapDN dn, ModificationItemImpl[] mods ) throws NamingException
    {
        Long id = getEntryId( dn.toString() );
        Attributes entry = master.get( id );

        for ( int ii = 0; ii < mods.length; ii++ )
        {
            Attribute attrMods = mods[ii].getAttribute();

            switch ( mods[ii].getModificationOp() )
            {
                case ( DirContext.ADD_ATTRIBUTE  ):
                    add( id, entry, attrMods );
                    break;
                case ( DirContext.REMOVE_ATTRIBUTE  ):
                    remove( id, entry, attrMods );
                    break;
                case ( DirContext.REPLACE_ATTRIBUTE  ):
                    replace( id, entry, attrMods );
                    break;
                default:
                    throw new NamingException( "Unidentified modification operation" );
            }
        }

        master.put( entry, id );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    /**
     * Changes the relative distinuished name of an entry specified by a 
     * distinguished name with the optional removal of the old Rdn attribute
     * value from the entry.  Name changes propagate down as dn changes to the 
     * descendants of the entry where the Rdn changed. 
     * 
     * An Rdn change operation does not change parent child relationships.  It 
     * merely propagates a name change at a point in the DIT where the Rdn is 
     * changed. The change propagates down the subtree rooted at the 
     * distinguished name specified.
     *
     * @param dn the normalized distinguished name of the entry to alter
     * @param newRdn the new Rdn to set
     * @param deleteOldRdn whether or not to remove the old Rdn attr/val
     * @throws NamingException if there are any errors propagating the name
     *        changes.
     */
    public void modifyRn( LdapDN dn, String newRdn, boolean deleteOldRdn ) throws NamingException
    {
        String newRdnAttr = NamespaceTools.getRdnAttribute( newRdn );
        String newRdnValue = NamespaceTools.getRdnValue( newRdn );
        Long id = getEntryId( dn.toString() );
        Attributes entry = lookup( id );
        LdapDN updn = new LdapDN( getEntryUpdn( id ) );

        /* 
         * H A N D L E   N E W   R D N
         * ====================================================================
         * Add the new Rdn attribute to the entry.  If an index exists on the 
         * new Rdn attribute we add the index for this attribute value pair.
         * Also we make sure that the existance index shows the existance of the
         * new Rdn attribute within this entry.
         */

        String newRdnAttrOid = oidRegistry.getOid( newRdnAttr );
        AttributeType newRdnAttrType = attributeTypeRegistry.lookup( newRdnAttrOid );
        Attribute rdnAttr = AttributeUtils.getAttribute( entry, newRdnAttrType );
        if ( rdnAttr == null )
        {
            rdnAttr = new AttributeImpl( newRdnAttr );
        }

        // add the new Rdn value only if it is not already present in the entry
        if ( !rdnAttr.contains( newRdnValue ) )
        {
            rdnAttr.add( newRdnValue );
        }

        entry.put( rdnAttr );

        if ( hasUserIndexOn( newRdnAttrOid ) )
        {
            Index idx = getUserIndex( newRdnAttrOid );
            idx.add( newRdnValue, id );

            // Make sure the altered entry shows the existance of the new attrib
            if ( !existanceIdx.hasValue( newRdnAttrOid, id ) )
            {
                existanceIdx.add( newRdnAttrOid, id );
            }
        }

        /*
         * H A N D L E   O L D   R D N
         * ====================================================================
         * If the old Rdn is to be removed we need to get the attribute and 
         * value for it.  Keep in mind the old Rdn need not be based on the 
         * same Rdn as the new one.  We remove the Rdn value from the entry
         * and remove the value/id tuple from the index on the old Rdn attr
         * if any.  We also test if the delete of the old Rdn index tuple 
         * removed all the attribute values of the old Rdn using a reverse
         * lookup.  If so that means we blew away the last value of the old 
         * Rdn attribute.  In this case we need to remove the attrName/id 
         * tuple from the existance index.
         */

        if ( deleteOldRdn )
        {
            String oldRdn = updn.get( updn.size() - 1 );
            String oldRdnAttr = NamespaceTools.getRdnAttribute( oldRdn );
            String oldRdnAttrOid = oidRegistry.getOid( oldRdnAttr );
            String oldRdnValue = NamespaceTools.getRdnValue( oldRdn );
            AttributeType oldRdnAttrType = attributeTypeRegistry.lookup( oldRdnAttrOid );
            
            AttributeUtils.getAttribute( entry, oldRdnAttrType ).remove( oldRdnValue );

            if ( hasUserIndexOn( oldRdnAttrOid ) )
            {
                Index idx = getUserIndex( oldRdnAttrOid );
                idx.drop( oldRdnValue, id );

                /*
                 * If there is no value for id in this index due to our
                 * drop above we remove the oldRdnAttr from the existance idx
                 */
                if ( null == idx.reverseLookup( id ) )
                {
                    existanceIdx.drop( oldRdnAttrOid, id );
                }
            }
        }

        /*
         * H A N D L E   D N   C H A N G E
         * ====================================================================
         * 1) Build the new user defined distinguished name
         *      - clone / copy old updn
         *      - remove old upRdn from copy
         *      - add the new upRdn to the copy
         * 2) Make call to recursive modifyDn method to change the names of the
         *    entry and its descendants
         */

        LdapDN newUpdn = ( LdapDN ) updn.clone(); // copy da old updn
        newUpdn.remove( newUpdn.size() - 1 ); // remove old upRdn
        newUpdn.add( newUpdn.size(), newRdn ); // add da new upRdn
        modifyDn( id, newUpdn, false ); // propagate dn changes
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    /*
     * The move operation severs a child from a parent creating a new parent
     * child relationship.  As a consequence the relationships between the 
     * old ancestors of the child and its descendants change.  A descendant is
     *   
     */

    /**
     * Recursively modifies the distinguished name of an entry and the names of
     * its descendants calling itself in the recursion.
     *
     * @param id the primary key of the entry
     * @param updn User provided distinguished name to set as the new DN
     * @param isMove whether or not the name change is due to a move operation
     * which affects alias indices.
     * @throws NamingException if something goes wrong
     */
    private void modifyDn( Long id, LdapDN updn, boolean isMove ) throws NamingException
    {
        String aliasTarget = null;

        // Now we can handle the appropriate name indices for all cases
        ndnIdx.drop( id );
        
        LdapDN normalizedDn = ( updn.isNormalized() ? updn : LdapDN.normalize( updn, attributeTypeRegistry.getNormalizerMapping() ) );
        
        ndnIdx.add( ndnIdx.getNormalized( normalizedDn.toNormName() ), id );

        updnIdx.drop( id );
        updnIdx.add( updn.getUpName(), id );

        /* 
         * Read Alias Index Tuples
         * 
         * If this is a name change due to a move operation then the one and
         * subtree indices for aliases were purged before the aliases were 
         * moved.  Now we must add them for each alias entry we have moved.  
         * 
         * aliasTarget is used as a marker to tell us if we're moving an 
         * alias.  If it is null then the moved entry is not an alias.
         */
        if ( isMove )
        {
            aliasTarget = ( String ) aliasIdx.reverseLookup( id );

            if ( null != aliasTarget )
            {
                addAliasIndices( id, new LdapDN( getEntryDn( id ) ), aliasTarget );
            }
        }

        NamingEnumeration children = list( id );
        while ( children.hasMore() )
        {
            // Get the child and its id
            IndexRecord rec = ( IndexRecord ) children.next();
            Long childId = (Long)rec.getEntryId();

            /* 
             * Calculate the Dn for the child's new name by copying the parents
             * new name and adding the child's old upRdn to new name as its Rdn
             */
            LdapDN childUpdn = ( LdapDN ) updn.clone();
            LdapDN oldUpdn = new LdapDN( getEntryUpdn( childId ) );
            String rdn = oldUpdn.get( oldUpdn.size() - 1 );
            childUpdn.add( childUpdn.size(), rdn );

            // Recursively change the names of the children below
            modifyDn( childId, childUpdn, isMove );
        }
    }


    public void move( LdapDN oldChildDn, LdapDN newParentDn, String newRdn, boolean deleteOldRdn ) throws NamingException
    {
        Long childId = getEntryId( oldChildDn.toString() );
        modifyRn( oldChildDn, newRdn, deleteOldRdn );
        move( oldChildDn, childId, newParentDn );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    public void move( LdapDN oldChildDn, LdapDN newParentDn ) throws NamingException
    {
        Long childId = getEntryId( oldChildDn.toString() );
        move( oldChildDn, childId, newParentDn );
        
        if ( isSyncOnWrite )
        {
            sync();
        }
    }


    /**
     * Moves an entry under a new parent.  The operation causes a shift in the
     * parent child relationships between the old parent, new parent and the 
     * child moved.  All other descendant entries under the child never change
     * their direct parent child relationships.  Hence after the parent child
     * relationship changes are broken at the old parent and set at the new
     * parent a modifyDn operation is conducted to handle name changes 
     * propagating down through the moved child and its descendants.
     * 
     * @param oldChildDn the normalized dn of the child to be moved
     * @param newParentDn the normalized dn of the new parent for the child
     * @throws NamingException if something goes wrong
     */
    private void move( LdapDN oldChildDn, Long childId, LdapDN newParentDn ) throws NamingException
    {
        // Get the child and the new parent to be entries and Ids
        Long newParentId = getEntryId( newParentDn.toString() );
        Long oldParentId = getParentId( childId );

        /*
         * All aliases including and below oldChildDn, will be affected by
         * the move operation with respect to one and subtree indices since 
         * their relationship to ancestors above oldChildDn will be 
         * destroyed.  For each alias below and including oldChildDn we will
         * drop the index tuples mapping ancestor ids above oldChildDn to the
         * respective target ids of the aliases.
         */
        dropMovedAliasIndices( oldChildDn );

        /*
         * Drop the old parent child relationship and add the new one
         * Set the new parent id for the child replacing the old parent id
         */
        hierarchyIdx.drop( oldParentId, childId );
        hierarchyIdx.add( newParentId, childId );

        /*
         * Build the new user provided DN (updn) for the child using the child's
         * user provided RDN & the new parent's UPDN.  Basically add the child's
         * UpRdn String to the tail of the new parent's Updn Name.
         */
        LdapDN childUpdn = new LdapDN( getEntryUpdn( childId ) );
        String childRdn = childUpdn.get( childUpdn.size() - 1 );
        LdapDN newUpdn = new LdapDN( getEntryUpdn( newParentId ) );
        newUpdn.add( newUpdn.size(), childRdn );

        // Call the modifyDn operation with the new updn
        modifyDn( childId, newUpdn, true );
    }


    /**
     * For all aliases including and under the moved base, this method removes
     * one and subtree alias index tuples for old ancestors above the moved base
     * that will no longer be ancestors after the move.
     * 
     * @param movedBase the base at which the move occured - the moved node
     * @throws NamingException if system indices fail
     */
    private void dropMovedAliasIndices( final LdapDN movedBase ) throws NamingException
    {
        // Find all the aliases from movedBase down
        IndexAssertion isBaseDescendant = new IndexAssertion()
        {
            public boolean assertCandidate( IndexRecord rec ) throws NamingException
            {
                String dn = getEntryDn( (Long)rec.getEntryId() );
                if ( dn.endsWith( movedBase.toString() ) )
                {
                    return true;
                }

                return false;
            }
        };

        Long movedBaseId = getEntryId( movedBase.toString() );
        if ( aliasIdx.reverseLookup( movedBaseId ) != null )
        {
            dropAliasIndices( movedBaseId, movedBase );
        }

        NamingEnumeration aliases = new IndexAssertionEnumeration( aliasIdx.listIndices( movedBase.toString(), true ),
            isBaseDescendant );
        while ( aliases.hasMore() )
        {
            IndexRecord entry = ( IndexRecord ) aliases.next();
            dropAliasIndices( (Long)entry.getEntryId(), movedBase );
        }
    }


    /**
     * For the alias id all ancestor one and subtree alias tuples are moved 
     * above the moved base.
     * 
     * @param aliasId the id of the alias 
     * @param movedBase the base where the move occured
     * @throws NamingException if indices fail
     */
    private void dropAliasIndices( Long aliasId, LdapDN movedBase ) throws NamingException
    {
        String targetDn = ( String ) aliasIdx.reverseLookup( aliasId );
        Long targetId = getEntryId( targetDn );
        String aliasDn = getEntryDn( aliasId );

        /*
         * Start droping index tuples with the first ancestor right above the 
         * moved base.  This is the first ancestor effected by the move.
         */
        LdapDN ancestorDn = ( LdapDN ) movedBase.getPrefix( 1 );
        Long ancestorId = getEntryId( ancestorDn.toString() );

        /*
         * We cannot just drop all tuples in the one level and subtree indices
         * linking baseIds to the targetId.  If more than one alias refers to
         * the target then droping all tuples with a value of targetId would
         * make all other aliases to the target inconsistent.
         * 
         * We need to walk up the path of alias ancestors right above the moved 
         * base until we reach the upSuffix, deleting each ( ancestorId,
         * targetId ) tuple in the subtree scope alias.  We only need to do 
         * this for the direct parent of the alias on the one level subtree if
         * the moved base is the alias.
         */
        if ( aliasDn.equals( movedBase.toString() ) )
        {
            oneAliasIdx.drop( ancestorId, targetId );
        }

        subAliasIdx.drop( ancestorId, targetId );

        while ( !ancestorDn.equals( upSuffix ) )
        {
            ancestorDn = ( LdapDN ) ancestorDn.getPrefix( 1 );
            ancestorId = getEntryId( ancestorDn.toString() );

            subAliasIdx.drop( ancestorId, targetId );
        }
    }


    public void initRegistries( Registries registries )
    {
        this.attributeTypeRegistry = registries.getAttributeTypeRegistry();
        this.oidRegistry = registries.getOidRegistry();
    }
}
