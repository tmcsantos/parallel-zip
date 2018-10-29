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
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.component.annotations.Component;

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

@Component( role = UnArchiver.class, hint = "zip")
public class ParallelZipUnArchiver extends ZipUnArchiver {

  private FileSystem zipfs;
  private File zipFile;
  private File destDirectory;
  private ExecutorService executorService;
  private final List<Future<Integer>> futures = new ArrayList<>();


  public ParallelZipUnArchiver() {
    this.executorService = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
  }

  @Override protected void execute() throws ArchiverException {
    zipFile = getSourceFile();
    destDirectory = getDestDirectory();
    getLogger().info( "Using concurrent ZIP unpacking with Java NIO" );
    getLogger().debug( "Expanding " + zipFile + " into " + destDirectory );

    try {
      createZipFileSystem();
      Iterable<Path> rootPaths = zipfs.getRootDirectories();
      for ( final Path path : rootPaths ) {
        Files.walkFileTree( path, new SimpleFileVisitor<Path>() {
          @Override public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException {
            extractFile( dir, path );
            return FileVisitResult.CONTINUE;
          }

          @Override public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException {
            extractFile( file, path );
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
    zipFile = getSourceFile();

    try {
      createZipFileSystem();
      Path p = zipfs.getPath( path );
      extractFile( p, p );
    } catch ( IOException e ) {
      throw new ArchiverException( "Error while expanding " + zipFile.getAbsolutePath(), e );
    }

  }

  private void close() throws ArchiverException {
    try {
      if ( !executorService.isShutdown() ) {
        // Make sure we catch any exceptions from parallel phase
        for ( final Future<?> future : futures ) {
          future.get();
        }
        executorService.shutdown();
        executorService
          .awaitTermination( 1000 * 60L, TimeUnit.SECONDS ); // == Infinity. We really *must* wait for this to complete
      }
      zipfs.close();
    } catch ( InterruptedException e ) {
      throw new ArchiverException( "Interrupted exception", e.getCause() );
    } catch ( ExecutionException e ) {
      throw new ArchiverException( "Execution exception", e.getCause() );
    } catch ( IOException e ) {
      throw new ArchiverException( "IO exception", e.getCause() );
    }
  }

  private void extractFile( final Path file, final Path root ) {
    futures.add( executorService.submit( new Callable<Integer>() {
      @Override public Integer call() throws Exception {
        // Make sure that we conserve the hierarchy of files and folders inside the zip
        Path relativePathInZip = root.relativize( file );
        Path targetPath = destDirectory.toPath().resolve( relativePathInZip.toString() );
        if (Files.isDirectory( file )) {
          Files.createDirectories( targetPath );
          return 0;
        }

        Files.createDirectories( targetPath.getParent() );
        InputStream inputStream = Files.newInputStream( file, StandardOpenOption.READ );
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

  private void createZipFileSystem() throws IOException {
    // setup ZipFileSystem
    Map<String, Object> env = new HashMap<>();
    env.put( "create", "false" );
    env.put( "encoding", "UTF8" );
    //    env.put( "useTempFile", Boolean.TRUE );
    URI zipURI = URI.create( String.format( "jar:file:%s", zipFile.getPath() ) );
    zipfs = FileSystems.newFileSystem( zipURI, env );
  }

}
