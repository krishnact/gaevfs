/*
 * Copyright 2009 New Atlanta Communications, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.newatlanta.commons.vfs.provider.gae;

import static com.newatlanta.commons.vfs.provider.gae.GaeRandomAccessContent.isDirty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.RandomAccessContent;
import org.apache.commons.vfs.provider.AbstractFileObject;
import org.apache.commons.vfs.provider.AbstractFileSystem;
import org.apache.commons.vfs.util.RandomAccessMode;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.newatlanta.appengine.datastore.CachingDatastoreService;

/**
 * Stores metadata for "files" and "folders" within GaeVFS and manages interactions
 * with the Google App Engine datastore. This is an internal GaeVFS implementation
 * class that is normally not referenced directly, but only indirectly via the
 * <a href="http://commons.apache.org/vfs/apidocs/index.html" target="_blank">Apache
 * Commons VFS API</a>. See {@link GaeVFS} as the entry point for application
 * code that interacts with GaeVFS.
 *
 * @author <a href="mailto:vbonfanti@gmail.com">Vince Bonfanti</a>
 */
public class GaeFileObject extends AbstractFileObject implements Serializable {

    private static final long serialVersionUID = 1L;

    private static DatastoreService datastore = new CachingDatastoreService();

    private static final String ENTITY_KIND = "GaeFileObject";

    // metadata property names
    private static final String FILETYPE = "filetype";
    private static final String LAST_MODIFIED = "last-modified";
    private static final String CHILD_KEYS = "child-keys";
    private static final String BLOCK_KEYS = "block-keys";
    private static final String CONTENT_SIZE = "content-size";
    private static final String BLOCK_SIZE = "block-size";

    private Entity metadata; // the wrapped GAE datastore entity
    private Map<Key, Entity> blockMap = new HashMap<Key, Entity>();

    private boolean isCombinedLocal;

    public GaeFileObject( FileName name, AbstractFileSystem fs ) {
        super( name, fs );
    }
    
    /**
     * Override the superclass implementation to make sure GaeVFS "shadows"
     * exist for local directories.
     */
    @Override
    public FileObject getParent() throws FileSystemException {
        FileObject parent = super.getParent();
        if ( ( parent != null ) && !parent.exists() ) {
            // check for existing local directory
            FileSystemManager manager = getFileSystem().getFileSystemManager();
            FileObject localDir = manager.resolveFile( "file://" +
                    GaeFileNameParser.getRootPath( manager.getBaseFile().getName() ) +
                        parent.getName().getPath() );
            
            if ( localDir.exists() && localDir.getType().hasChildren() ) {
                parent.createFolder(); // make sure GaeVFS "shadow" folder exists
            }
        }
        return parent;
    }

    public void setCombinedLocal( boolean b ) {
        isCombinedLocal = b;
    }

    public void setBlockSize( int size ) throws FileSystemException {
        if ( exists() ) {
            throw new FileSystemException( "Could not set the block size of \"" +
                                    getName() + "\" because it already exists." );
        }
        // exists() guarantees that metadata != null
        metadata.setUnindexedProperty( BLOCK_SIZE, Long.valueOf( size ) );
    }
    
    public int getBlockSize() throws FileSystemException {
        Long blockSize = (Long)metadata.getProperty( BLOCK_SIZE );
        if ( blockSize == null ) {
            throw new FileSystemException( "Could not get the block size of \"" +
                                                getName() + "\"" );
        }
        return blockSize.intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Key> getBlockKeys() throws FileSystemException {
        List<Key> blockKeys = (List<Key>)metadata.getProperty( BLOCK_KEYS );
        if ( blockKeys == null ) {
            blockKeys = new ArrayList<Key>();
            metadata.setUnindexedProperty( BLOCK_KEYS, blockKeys );
        }
        return blockKeys;
    }

    @SuppressWarnings("unchecked")
    private List<Key> getChildKeys() throws FileSystemException {
        if ( !getType().hasChildren() ) {
            throw new FileSystemException( "vfs.provider/list-children-not-folder.error",
                                                getName() );
        }
        return (List<Key>)metadata.getProperty( CHILD_KEYS );
    }

    // FileType is not a valid property type, so store the name
    private FileType getEntityFileType() {
        String typeName = (String)metadata.getProperty( FILETYPE );
        if ( typeName != null ) {
            if ( typeName.equals( FileType.FILE.getName() ) ) {
                return FileType.FILE;
            }
            if ( typeName.equals( FileType.FOLDER.getName() ) ) {
                return FileType.FOLDER;
            }
        }
        return FileType.IMAGINARY;
    }

    /**
     * Attaches this file object to its file resource.  This method is called
     * before any of the doBlah() or onBlah() methods.  Sub-classes can use
     * this method to perform lazy initialization.
     */
    @Override
    protected void doAttach() throws FileSystemException {
        if ( metadata == null ) {
            getMetaData( createKey() );
        }
        injectType( getEntityFileType() );
    }

    private synchronized void getMetaData( Key key ) throws FileSystemException {
        try {
            metadata = datastore.get( key );
        } catch ( EntityNotFoundException e ) {
            metadata = new Entity( ENTITY_KIND, key.getName() );
            setBlockSize( GaeVFS.getBlockSize() );
        }
    }

    private Key createKey() throws FileSystemException {
        return createKey( getName() );
    }

    private Key createKey( FileName fileName ) throws FileSystemException {
        // key name is relative path from the webapp root directory
        return KeyFactory.createKey( ENTITY_KIND, fileName.getPath() );
    }

    /**
     * Detaches this file object from its file resource.
     * 
     * Called when this file is closed.  Note that the file object may be
     * reused later, so should be able to be reattached.
     */
    @Override
    protected void doDetach() throws FileSystemException {
        metadata = null;
        blockMap.clear();
    }

    /**
     * Returns the file type. The main use of this method is to determine if the
     * file exists. As long as we always set the superclass type via injectType(),
     * this method never gets invoked (which is a good thing, because it's expensive).
     */
    @Override
    protected FileType doGetType() {
        try {
            // the only way to check if the metadata exists is to try to read it
            if ( ( metadata != null ) && ( datastore.get( metadata.getKey() ) != null ) ) {
                return getName().getType();
            }
        } catch ( EntityNotFoundException e ) {
        }
        return FileType.IMAGINARY; // file doesn't exist
    }

    /**
     * Lists the children of this file.  Is only called if {@link #doGetType}
     * returns {@link FileType#FOLDER}.  The return value of this method
     * is cached, so the implementation can be expensive.
     * 
     * GAE note: this method only lists the GAE children, and not the local
     * children. But, with the current superclass implementation, this method
     * is never invoked if doListChildrenResolved() is implemented (see below).
     */
    @Override
    protected String[] doListChildren() throws FileSystemException {
        List<Key> childKeys = getChildKeys();
        if ( ( childKeys == null ) || ( childKeys.size() == 0 ) ) {
            return new String[ 0 ];
        }
        String[] childNames = new String[ childKeys.size() ];
        int i = 0;
        for ( Key childKey : childKeys ) {
            childNames[ i++ ] = childKey.getName();
        }
        return childNames;
    }

    /**
     * Lists the children of this file.  Is only called if {@link #doGetType}
     * returns {@link FileType#FOLDER}.  The return value of this method
     * is cached, so the implementation can be expensive.<br>
     * Other than <code>doListChildren</code> you could return FileObject's to
     * e.g. reinitialize the type of the file.<br>
     */
    @Override
    protected FileObject[] doListChildrenResolved() throws FileSystemException {
        List<Key> childKeys = getChildKeys();
        FileObject[] localChildren = getLocalChildren();
        if ( ( childKeys == null ) || ( childKeys.size() == 0 ) ) {
            return localChildren;
        }
        FileObject[] children = new FileObject[ localChildren.length + childKeys.size() ];

        if ( localChildren.length > 0 ) {
            System.arraycopy( localChildren, 0, children, 0, localChildren.length );
        }
        int i = localChildren.length;

        for ( Key child : childKeys ) {
            children[ i++ ] = resolveFile( child.getName() );
        }
        return children;
    }

    private FileObject[] getLocalChildren() throws FileSystemException {
        if ( isCombinedLocal ) {
            GaeFileName fileName = (GaeFileName)getName();
            String localUri = "file://" + fileName.getRootPath() + fileName.getPath();
            FileObject localFile = getFileSystem().getFileSystemManager().resolveFile( localUri );
            if ( localFile.exists() ) {
                return localFile.getChildren();
            }
        }
        return new FileObject[ 0 ];
    }

    /**
     * Deletes the file.
     */
    @Override
    protected void doDelete() {
        // the real work of deleting happens in onChange(), but we need a
        // do-nothing implementation to override the superclass, which throws
        // an exception
    }

    /**
     * Renames the file. If a folder, recursively rename the children.
     */
    @Override
    protected void doRename( FileObject newfile ) throws FileSystemException {
        if ( this.getType().hasChildren() ) { // rename the children
            for ( FileObject child : this.getChildren() ) {
                String newChildPath = child.getName().getPath().replace(
                        this.getName().getPath(), newfile.getName().getPath() );
                child.moveTo( resolveFile( newChildPath ) );
            }
            newfile.createFolder(); // TODO: is this redundant?
        } else {
            // TODO: the following code is a bit messy for several reasons:
            //   1) the call to getBlock(int) results in a call to createFile(),
            //      which seems inappropriate at that point
            //   2) the call to datastore.put()for the entity blocks seems like
            //      it should happen when the file is created and should be done
            //      along with the metadata
            //   3) the final call to createFile() seems redundant
            // But, it works.
            GaeFileObject newGaeFile = (GaeFileObject)newfile;
            // new file might have been detached during rename of parent folder
            if ( newGaeFile.metadata == null ) {
                newGaeFile.doAttach();
            }
            int numBlocks = getBlockKeys().size(); // copy contents to the new file
            for ( int i = 0; i < numBlocks; i++ ) {
                newGaeFile.getBlock( i ).setPropertiesFrom( this.getBlock( i ) );
            }
            // TODO: is this the best place to write block entities? dirty flag
            // needs to be set for blocks if written elsewhere
            datastore.put( newGaeFile.blockMap.values() );
            newGaeFile.metadata.setProperty( CONTENT_SIZE, this.metadata.getProperty( CONTENT_SIZE ) );
            newGaeFile.createFile(); // TODO: is this redundant?
        }
    }

    /**
     * Creates this file as a folder.  Is only called when:
     * <ul>
     * <li>{@link #doGetType} returns {@link FileType#IMAGINARY}.
     * <li>The parent folder exists and is writeable, or this file is the
     * root of the file system.
     * </ul>
     * <p/> 
     */
    @Override
    protected void doCreateFolder() throws FileSystemException {
        // an important side-effect of getType() is that it causes this object
        // to be attached; if not attached, then onChange() doesn't get invoked
        if ( getType() != FileType.FOLDER ) {
            injectType( FileType.FOLDER ); // always inject before putEntity()
            metadata.removeProperty( BLOCK_SIZE ); // not needed for folders
        }
        // onChange() will be invoked after this to put the metadata
    }

    /**
     * Called when the children of this file change.
     */
    protected void onChildrenChanged( FileName child, FileType newType ) throws FileSystemException {
        Key childKey = createKey( child );
        List<Key> childKeys = getChildKeys();
        if ( newType == FileType.IMAGINARY ) { // child being deleted
            if ( childKeys != null ) {
                childKeys.remove( childKey );
                if ( childKeys.size() == 0 ) {
                    metadata.removeProperty( CHILD_KEYS );
                }
            }
        } else { // child being added
            if ( childKeys == null ) {
                childKeys = new ArrayList<Key>();
                childKeys.add( childKey );
                metadata.setUnindexedProperty( CHILD_KEYS, childKeys );
            } else if ( !childKeys.contains( childKey ) ) {
                childKeys.add( childKey );
            }
        }
        putMetaData();
    }

    /**
     * Called when the type or content of this file changes, or when it is created
     * or deleted.
     */
    @Override
    protected void onChange() throws FileSystemException {
        if ( getType() == FileType.IMAGINARY ) { // file/folder is being deleted
            if ( getName().getType().hasContent() ) {
                deleteBlocks();
            }
            deleteMetaData();
        } else { // file/folder is being created or modified
            putMetaData(); // TODO: put blocks and metadata as single operation?
        }
    }

    private void deleteMetaData() throws FileSystemException {
        datastore.delete( metadata.getKey() );
        // metadata.getProperties().clear(); // see issue #1395
        Object[] properties = metadata.getProperties().keySet().toArray();
        for ( int i = 0; i < properties.length; i++ ) {
            metadata.removeProperty( properties[ i ].toString() );
        }
        setBlockSize( GaeVFS.getBlockSize() );
    }

    /**
     * Write the metadata to the GAE datastore. Make sure the file type is set
     * correctly and update the last modified time.
     */
    private synchronized void putMetaData() throws FileSystemException {
        metadata.setProperty( FILETYPE, getType().getName() );
        doSetLastModTime( System.currentTimeMillis() );
        datastore.put( metadata );
    }

    /**
     * Returns the last modified time of this file.
     */
    @Override
    protected long doGetLastModifiedTime() {
        Long lastModified = (Long)metadata.getProperty( LAST_MODIFIED );
        return ( lastModified != null ? lastModified.longValue() : 0 );
    }

    /**
     * Sets the last modified time of this file.
     */
    @Override
    protected boolean doSetLastModTime( long modtime ) {
        metadata.setProperty( LAST_MODIFIED, Long.valueOf( modtime ) );
        return true;
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected long doGetContentSize() throws FileSystemException {
        if ( !getType().hasContent() ) {
            throw new FileSystemException( "vfs.provider/get-size-not-file.error", getName() );
        }
        Long contentSize = (Long)metadata.getProperty( CONTENT_SIZE );
        return ( contentSize != null ? contentSize.longValue() : 0 );
    }

    /**
     * Intended for use by GaeRandomAccessContent.
     */
    void updateContentSize( long newSize ) throws FileSystemException {
        updateContentSize( newSize, false );
    }

    void updateContentSize( long newSize, boolean force ) throws FileSystemException {
        if ( force || ( newSize > doGetContentSize() ) ) {
            metadata.setProperty( CONTENT_SIZE, Long.valueOf( newSize ) );
        }
    }

    /**
     * Creates an input stream to read the file content from.
     * 
     * The returned stream does not have to be buffered.
     */
    @Override
    protected InputStream doGetInputStream() throws IOException {
        if ( !getType().hasContent() ) {
            throw new FileSystemException( "vfs.provider/read-not-file.error", getName() );
        }
        return new GaeRandomAccessContent( this, RandomAccessMode.READ,
                                                getBlockSize() ).getInputStream();
    }

    /**
     * Creates access to the file for random i/o. Is only called if doGetType()
     * returns FileType.FILE
     * 
     * It is guaranteed that there are no open output streams for this file
     * when this method is called.
     */
    protected RandomAccessContent doGetRandomAccessContent( RandomAccessMode mode )
            throws IOException {
        return new GaeRandomAccessContent( this, mode, getBlockSize() );
    }

    /**
     * Creates an output stream to write the file content to.
     * 
     * It is guaranteed that there are no open stream (input or output) for
     * this file when this method is called.
     * 
     * The returned stream does not have to be buffered.
     */
    @Override
    protected OutputStream doGetOutputStream( boolean bAppend ) throws IOException {
        return new GaeRandomAccessContent( this, RandomAccessMode.READWRITE, getBlockSize(),
                                            bAppend && exists() ? doGetContentSize() : 0 );
    }
    
    /**
     * will be called after this file-object closed all its streams.
     */
    @Override
    protected void notifyAllStreamsClosed() {
        List<Entity> dirtyBlocks = getDirtyBlocks();
        if ( !dirtyBlocks.isEmpty() ) {
            int blocksPerPut = 1;
            try {
                blocksPerPut = ( GaeVFS.MAX_BLOCK_SIZE * 1024 ) / getBlockSize();
            } catch ( FileSystemException e ) {
            }
            doSetLastModTime( System.currentTimeMillis() );
            dirtyBlocks.add( 0, metadata );
            int fromIndex = 0;
            while ( fromIndex < dirtyBlocks.size() ) {
                int toIndex = Math.min( fromIndex + blocksPerPut, dirtyBlocks.size() );
                datastore.put( dirtyBlocks.subList( fromIndex, toIndex ) );
                fromIndex = toIndex;
            }
        }
    }

    /***************************************************************************
     * The following methods related to blocks are for use by                  * 
     * GaeRandomAccessContent.                                                 *
     ***************************************************************************/
    
    // TODO: need to test random access operations that cause sparse blocks
    
    Entity getBlock( int i ) throws FileSystemException {
        if ( !exists() ) {
            createFile(); // TODO: why is this being done here?
        }
        List<Key> blockKeys = getBlockKeys();
        Entity block = null;
        if ( i < blockKeys.size() ) { // existing key
            block = blockMap.get( blockKeys.get( i ) );
            if ( block == null ) {
                block = getBlock( i, blockKeys );
                if ( block == null ) {
                    blockKeys.remove( i );
                    block = createBlock( blockKeys, i );
                }
            }
        } else { // i >= blockKeys.size()
            for ( int j = blockKeys.size(); j <= i; j++ ) {
                block = createBlock( blockKeys, j );
            }
        }
        return block;
    }
    
    /**
     * Populates the blockMap and returns the specified block.
     * 
     * TODO: limit the number of blocks in memory at one time?
     */
    private Entity getBlock( int i, List<Key> blockKeys ) {
        if ( blockKeys.size() <= 10 ) { // get all blocks
            blockMap.putAll( datastore.get( blockKeys ) );
        } else { // get 10 blocks starting at i
            blockMap.putAll( datastore.get( blockKeys.subList( i,
                                    Math.min( i + 10, blockKeys.size() ) ) ) );
        }
        return blockMap.get( blockKeys.get( i ) );
    }

    private Entity createBlock( List<Key> blockKeys, int i ) {
        Entity block = new Entity( ENTITY_KIND, "block." + i, metadata.getKey() );
        blockKeys.add( i, block.getKey() );
        blockMap.put( block.getKey(), block );
        return block;
    }
    
    private List<Entity> getDirtyBlocks() {
        List<Entity> dirtyBlocks = new ArrayList<Entity>();
        for ( Entity block : blockMap.values() ) {
            if ( isDirty( block, true ) ) {
                dirtyBlocks.add( block );
            }
        }
        return dirtyBlocks;
    }

    private void deleteBlocks() throws FileSystemException {
        List<Key> blockKeys = getBlockKeys();
        if ( !blockKeys.isEmpty() ) {
            datastore.delete( blockKeys );
        }
    }

    /**
     * Truncate blocks up to but exclusive of the specified index.
     */
    void deleteBlocks( int stopIndex ) throws FileSystemException {
        List<Key> blockKeys = getBlockKeys();
        if ( blockKeys.size() > ( stopIndex + 1 ) ) {
            List<Key> deleteKeyList = new ArrayList<Key>();
            for ( int i = blockKeys.size() - 1; i > stopIndex; i-- ) {
                deleteKeyList.add( blockKeys.remove( i ) );
            }
            datastore.delete( deleteKeyList );
            putMetaData();
        }
    }

    protected void finalize() throws Throwable {
        if ( getFileSystem() != null ) { // avoid NPE in super.finalize()
            super.finalize();
        }
    }
}
