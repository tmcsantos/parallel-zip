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

import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.components.io.fileselectors.FileInfo;

import java.io.File;
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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

@Component( role = UnArchiver.class, hint = "zip" )
public class ParallelZipUnArchiver extends AbstractParallelZipUnArchiver {

  private FileSystem zipfs;
  private ExecutorService executorService;
  private List<Future<Integer>> futures = new ArrayList<>();

  public ParallelZipUnArchiver() {
    this.executorService = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
  }

  public ParallelZipUnArchiver( File sourceFile ) {
    super( sourceFile );
    this.executorService = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
  }

  @Override protected void execute() throws ArchiverException {
    File zipFile = getSourceFile();
    File destDirectory = getDestDirectory();
    getLogger().info( "Using concurrent ZIP unpacking with Java NIO" );
    getLogger().debug( "Expanding " + zipFile + " into " + destDirectory );

    try {
      zipfs = createZipFileSystem();
      Iterable<Path> rootPaths = zipfs.getRootDirectories();
      for ( final Path path : rootPaths ) {
        Files.walkFileTree( path, new SimpleFileVisitor<Path>() {
          @Override public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException {
            FileInfo fileInfo = new ZipEntryFileInfo( dir, path );
            extractFile( fileInfo );
            return FileVisitResult.CONTINUE;
          }

          @Override public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException {
            FileInfo fileInfo = new ZipEntryFileInfo( file, path );
            extractFile( fileInfo );
            return FileVisitResult.CONTINUE;
          }
        } );
      }
    } catch ( IOException ioe ) {
      throw new ArchiverException( "Error while expanding " + zipFile.getAbsolutePath(), ioe );
    }
    close();
  }

  @Override protected void execute( String path, File outputDirectory ) throws ArchiverException {
    File zipFile = getSourceFile();

    try {
      zipfs = createZipFileSystem();
      Path p = zipfs.getPath( path );
      FileInfo fileInfo = new ZipEntryFileInfo( p, outputDirectory.toPath() );
      extractFile( fileInfo );
    } catch ( IOException e ) {
      throw new ArchiverException( "Error while expanding " + zipFile.getAbsolutePath(), e );
    }
    close();
  }

  private void close() throws ArchiverException {
    try {
      if ( !executorService.isShutdown() ) {
        // Make sure we catch any exceptions from parallel phase
        for ( final Future<?> future : futures ) {
          future.get();
        }
      }
      executorService.shutdown();
      executorService
        .awaitTermination( 1000 * 60L, TimeUnit.SECONDS ); // == Infinity. We really *must* wait for this to complete
      futures = null;
      zipfs.close();
    } catch ( InterruptedException e ) {
      throw new ArchiverException( "Interrupted exception", e.getCause() );
    } catch ( ExecutionException e ) {
      throw new ArchiverException( "Execution exception", e.getCause() );
    } catch ( IOException e ) {
      throw new ArchiverException( "IO exception", e.getCause() );
    }
  }

  private void extractFile( final FileInfo fileInfo ) {
    futures.add( executorService.submit( new Callable<Integer>() {
      @Override public Integer call() throws Exception {
        if (!isSelected( fileInfo )) {
          return 0;
        }

        Path targetPath = getDestDirectory().toPath().resolve( fileInfo.getName() );
        if ( fileInfo.isDirectory() ) {
          Files.createDirectories( targetPath );
          return 0;
        }

        Files.createDirectories( targetPath.getParent() );
        InputStream inputStream = fileInfo.getContents();
        OutputStream outputStream = Files
          .newOutputStream( targetPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING );

        try {
          ByteBuffer buf = ByteBuffer.allocateDirect( 1024 * 254 ); //254K
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
        return 0;
      }
    } ) );
  }

  private FileSystem createZipFileSystem() throws IOException {
    // setup ZipFileSystem
    Map<String, Object> env = new HashMap<>();
    env.put( "create", "false" );
    env.put( "encoding", "UTF8" );
    URI zipURI = URI.create( String.format( "jar:file:%s", getSourceFile().getPath() ) );
    return FileSystems.newFileSystem( zipURI, env );
  }

  private static class ZipEntryFileInfo implements FileInfo {
    private Path zipEntry;
    private Path rootPath;

    public ZipEntryFileInfo(Path zipEntry, Path root) {
      this.zipEntry = zipEntry;
      this.rootPath = root;
    }

    @Override public String getName() {
      // Make sure that we conserve the hierarchy of files and folders inside the zip
      return rootPath.relativize( zipEntry ).toString();
    }

    @Override public InputStream getContents() throws IOException {
      return Files.newInputStream( zipEntry, StandardOpenOption.READ );
    }

    @Override public boolean isFile() {
      return Files.isRegularFile( zipEntry );
    }

    @Override public boolean isDirectory() {
      return Files.isDirectory( zipEntry );
    }

    @Override public boolean isSymbolicLink() {
      return Files.isSymbolicLink( zipEntry );
    }
  }
}
