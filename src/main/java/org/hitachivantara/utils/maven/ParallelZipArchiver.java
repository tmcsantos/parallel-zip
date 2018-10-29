/*
 * Copyright (C) 2018 by Hitachi Vantara
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */


package org.hitachivantara.utils.maven;

import org.codehaus.plexus.archiver.ArchiveEntry;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.ResourceIterator;
import org.codehaus.plexus.archiver.exceptions.EmptyArchiveException;
import org.codehaus.plexus.archiver.util.ResourceUtils;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.components.io.functions.SymlinkDestinationSupplier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component( role = Archiver.class, hint = "zip" )
public class ParallelZipArchiver extends ZipArchiver {

  private FileSystem zipfs;
  private File zipFile;
  private ExecutorService executorService;
  private final List<Future<Integer>> futures = new ArrayList<>();


  public ParallelZipArchiver() {
    super();
    this.archiveType = "zip";
    this.executorService = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
  }

  @Override protected void execute() throws ArchiverException, IOException {
    if ( !checkForced() ) {
      return;
    }

    ResourceIterator iter = getResources();
    if ( !iter.hasNext() && !hasVirtualFiles() ) {
      throw new EmptyArchiveException( "archive cannot be empty" );
    }

    zipFile = getDestFile();

    if ( zipFile == null ) {
      throw new ArchiverException( "You must set the destination " + getArchiveType() + "file." );
    }

    if ( zipFile.exists() && !zipFile.isFile() ) {
      throw new ArchiverException( zipFile + " isn't a file." );
    }

    if ( zipFile.exists() && !zipFile.canWrite() ) {
      throw new ArchiverException( zipFile + " is read-only." );
    }

    getLogger().info( "Using concurrent ZIP compression with Java NIO" );
    getLogger().info( "Building zip: " + zipFile.getAbsolutePath() );
    createZipFileSystem();
    addResources( iter );
  }

  protected final void addResources( ResourceIterator resources ) throws IOException {
    ArchiveEntry entry;
    String name;
    while ( resources.hasNext() ) {
      entry = resources.next();
      // Check if we don't add tar file in itself
      if ( ResourceUtils.isSame( entry.getResource(), zipFile ) ) {
        throw new ArchiverException( "A zip file cannot include itself." );
      }
      name = entry.getName().replace( File.separatorChar, '/' );
      if ( "".equals( name ) ) {
        continue;
      }

      if ( entry.getResource().isDirectory() && !name.endsWith( "/" ) ) {
        name = name + "/";
      }

      zipFile( entry, name );
    }
  }

  protected void zipFile( final ArchiveEntry entry, final String vPath ) throws IOException {
    final boolean isSymlink = entry.getResource() instanceof SymlinkDestinationSupplier;
    final boolean isFile = entry.getResource().isFile();

    final String symlinkTarget =
      isSymlink ? ( (SymlinkDestinationSupplier) entry.getResource() ).getSymlinkDestination() : null;

    getLogger().debug( "adding entry " + vPath );

    if ( !skipWriting ) {
      futures.add( executorService.submit( new Callable<Integer>() {
        @Override public Integer call() throws Exception {
          Path path = zipfs.getPath( vPath );

          if ( !isFile && !isFilesonly() && getIncludeEmptyDirs() ) {
            Files.createDirectories( path );
          }

          if ( isFile ) {
            Path nf = path.getParent();
            if ( nf != null && Files.notExists( nf ) ) {
              Files.createDirectories( nf );
            }

            InputStream inputStream;
            OutputStream outputStream = Files
              .newOutputStream( path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING );

            if ( !isSymlink ) {
              inputStream = entry.getInputStream();
            } else {
              inputStream = new FileInputStream( symlinkTarget );
            }

            try {
              ByteBuffer buf = ByteBuffer.allocateDirect( 1024 * 256 ); //256K
              ReadableByteChannel inChannel = Channels.newChannel( inputStream );
              WritableByteChannel outChannel = Channels.newChannel( outputStream );
              while ( inChannel.read( buf ) >= 0 || buf.position() != 0 ) {
                buf.flip();
                outChannel.write( buf );
                buf.compact();
              }
            } finally {
              inputStream.close();
              outputStream.close();
            }
          }
          return 0;
        }
      } ) );
    }
  }

  @Override protected boolean revert( StringBuffer messageBuffer ) {
    return true;
  }

  private void createZipFileSystem() throws IOException {
    // setup ZipFileSystem
    Map<String, Object> env = new HashMap<>();
    env.put( "create", "true" );
    env.put( "encoding", getEncoding() );
    env.put( "useTempFile", Boolean.TRUE );
    URI zipURI = URI.create( String.format( "jar:file:%s", zipFile.getPath() ) );
    zipfs = FileSystems.newFileSystem( zipURI, env );
  }

  @Override protected void close() throws IOException {
    if ( !executorService.isShutdown() ) {
      try {
        // Make sure we catch any exceptions from parallel phase
        for ( final Future<?> future : futures ) {
          future.get();
        }
        executorService.shutdown();
        executorService
          .awaitTermination( 1000 * 60L, TimeUnit.SECONDS ); // == Infinity. We really *must* wait for this to complete
      } catch ( InterruptedException e ) {
        throw new IOException( "Interrupted exception", e.getCause() );
      } catch ( ExecutionException e ) {
        throw new IOException( "Execution exception", e.getCause() );
      }
    }
    zipfs.close();
  }
}
