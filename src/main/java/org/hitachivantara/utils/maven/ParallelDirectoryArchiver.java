package org.hitachivantara.utils.maven;

import org.codehaus.plexus.archiver.ArchiveEntry;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.ResourceIterator;
import org.codehaus.plexus.archiver.dir.DirectoryArchiver;
import org.codehaus.plexus.archiver.exceptions.EmptyArchiveException;
import org.codehaus.plexus.archiver.util.ArchiveEntryUtils;
import org.codehaus.plexus.archiver.util.ResourceUtils;
import org.codehaus.plexus.components.io.attributes.SymlinkUtils;
import org.codehaus.plexus.components.io.functions.SymlinkDestinationSupplier;
import org.codehaus.plexus.components.io.resources.PlexusIoResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ParallelDirectoryArchiver extends DirectoryArchiver {
  private ExecutorService executorService;
  private List<Future<Integer>> futures = new ArrayList<>();

  public ParallelDirectoryArchiver() {
    this.executorService = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
  }

  @Override public void execute() throws ArchiverException, IOException {
    getLogger().info( "Using Java NIO" );
    // Most of this method was copied from org.codehaus.plexus.archiver.tar.TarArchiver
    // and modified to store files in a directory, not a tar archive.
    final ResourceIterator iter = getResources();
    if ( !iter.hasNext() ) {
      throw new EmptyArchiveException( "archive cannot be empty" );
    }

    final File destDirectory = getDestFile();
    if ( destDirectory == null ) {
      throw new ArchiverException( "You must set the destination directory." );
    }
    if ( destDirectory.exists() && !destDirectory.isDirectory() ) {
      throw new ArchiverException( destDirectory + " is not a directory." );
    }
    if ( destDirectory.exists() && !destDirectory.canWrite() ) {
      throw new ArchiverException( destDirectory + " is not writable." );
    }

    getLogger().info( "Copying files to " + destDirectory.getAbsolutePath() );

    while ( iter.hasNext() ) {
      final ArchiveEntry f = iter.next();
      // Check if we don't add directory file in itself
      if ( ResourceUtils.isSame( f.getResource(), destDirectory ) ) {
        throw new ArchiverException( "The destination directory cannot include itself." );
      }
      futures.add( executorService.submit( new Callable<Integer>() {
        @Override public Integer call() throws Exception {
          String fileName = f.getName();
          final String destDir = destDirectory.getCanonicalPath();
          fileName = destDir + File.separator + fileName;
          PlexusIoResource resource = f.getResource();
          if ( resource instanceof SymlinkDestinationSupplier ) {
            String dest = ( (SymlinkDestinationSupplier) resource ).getSymlinkDestination();
            File target = new File( dest );
            SymlinkUtils.createSymbolicLink( new File( fileName ), target );
          } else {
            copyFile( f, fileName );
          }
          return 0;
        }
      } ) );
    }
  }

  @Override protected void copyFile( final ArchiveEntry entry, final String vPath )
    throws ArchiverException, IOException {
    // don't add "" to the archive
    if ( vPath.length() <= 0 ) {
      return;
    }

    final PlexusIoResource in = entry.getResource();
    final File outFile = new File( vPath );
    final Path out = outFile.toPath();

    final long inLastModified = in.getLastModified();
    final long outLastModified = outFile.lastModified();
    if ( ResourceUtils.isUptodate( inLastModified, outLastModified ) ) {
      return;
    }

    if ( in.isFile() ) {
      Files.createDirectories( out.getParent() );
      InputStream inputStream = in.getContents();
      ReadableByteChannel inChannel = Channels.newChannel( inputStream );

      FileChannel outChannel = new FileOutputStream( outFile ).getChannel();
      outChannel.transferFrom( inChannel, 0, in.getSize() );

      setFileModes( entry, outFile, inLastModified );
      outChannel.close();
      inChannel.close();
      inputStream.close();
    } else {
      if ( Files.exists( out ) ) {
        if ( !Files.isDirectory( out ) ) {
          // should we just delete the file and replace it with a directory?
          // throw an exception, let the user delete the file manually.
          throw new ArchiverException(
            "Expected directory and found file at copy destination of " + in.getName() + " to " + outFile );
        }
      }
      Files.createDirectories( out );
      setFileModes( entry, outFile, inLastModified );
    }
  }

  private void setFileModes( ArchiveEntry entry, File outFile, long inLastModified ) {
    if ( !isIgnorePermissions() ) {
      ArchiveEntryUtils.chmod( outFile, entry.getMode() );
    }

    outFile.setLastModified( inLastModified == PlexusIoResource.UNKNOWN_MODIFICATION_DATE
      ? System.currentTimeMillis()
      : inLastModified );
  }

  @Override protected void close() throws IOException {
    super.close();
    if ( !executorService.isShutdown() ) {
      try {
        // Make sure we catch any exceptions from parallel phase
        for ( final Future<?> future : futures ) {
          future.get();
        }
        futures = null;
        executorService.shutdown();
        executorService
          .awaitTermination( 1000 * 60L, TimeUnit.SECONDS ); // == Infinity. We really *must* wait for this to complete
      } catch ( InterruptedException e ) {
        throw new IOException( "Interrupted exception", e.getCause() );
      } catch ( ExecutionException e ) {
        throw new IOException( "Execution exception", e.getCause() );
      }
    }
  }
}
